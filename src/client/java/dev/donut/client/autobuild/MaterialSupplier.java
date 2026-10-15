package dev.donut.client.autobuild;

import dev.donut.client.config.AutoBuilderSettings;
import dev.donut.client.mixin.BuilderLocalPlayerAccessor;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Resupplies the builder without leaving the build: places and empties shulker boxes carried in
 * the inventory, and otherwise buys the missing material through {@code /ah <item name>} after
 * verifying the listing item and an affordable price on both the listing and the confirmation.
 * All access is on the client thread and every menu interaction is bounded by a deadline.
 */
public final class MaterialSupplier {
    private enum Phase {
        IDLE, PLACE_AIM, PLACE_WAIT, OPEN_AIM, OPEN_WAIT, WITHDRAW, CLOSE_MENU,
        AH_SEARCH, AH_WAIT, AH_SCAN, AH_CONFIRM, AH_VERIFY
    }

    private static final Pattern CONFIRM_BUTTON = Pattern.compile("(?i)\\b(confirm|purchase|buy now|buy|yes)\\b");

    private final List<BlockPos> placedShulkers = new ArrayList<>();
    private final Set<BlockPos> checkedShulkers = new HashSet<>();
    private AutoBuilderSettings settings;
    private Phase phase = Phase.IDLE;
    private Item item;
    private int need, countBefore, clicks, nameAttempt, now;
    private BlockPos avoid, shulkerPos;
    private Predicate<BlockPos> reserved = pos -> false;
    private PlacementPlanner.Plan plan;
    private float yaw, pitch;
    private boolean sneaking;
    private int deadline;
    private String status = "", failure;
    private AbstractContainerMenu watchedMenu;
    private double listingPrice;
    private int listingCount;
    private String query = "";

    public MaterialSupplier(AutoBuilderSettings settings) { this.settings = settings; }
    public void useSettings(AutoBuilderSettings value) { settings = value; }

    public boolean active() { return phase != Phase.IDLE; }
    public String status() { return status; }
    public String failure() { return failure; }
    public int placedShulkers() { return placedShulkers.size(); }
    /** True while this supplier owns the open container screen instead of the user. */
    public boolean handlingMenu(Minecraft client) {
        return active() && client.screen instanceof AbstractContainerScreen<?>;
    }

    /** Resets the machine and closes any menu it opened. Safe to call while paused or cancelled. */
    public void abort(Minecraft client) {
        if (client != null && client.player != null
                && client.player.containerMenu != client.player.inventoryMenu) closeMenu(client);
        phase = Phase.IDLE;
        plan = null;
        failure = null;
        status = "";
        clicks = 0;
    }

    /**
     * Begins resupply for {@code need} items. Prefers carried shulker boxes, then placed ones,
     * then the auction house when buying is enabled. Returns false when nothing can be tried.
     */
    public boolean start(Minecraft client, Item item, int need, BlockPos avoid, Predicate<BlockPos> reserved) {
        if (active() || item == null || client.player == null || client.level == null) return false;
        this.item = item;
        this.need = Math.max(1, need);
        this.avoid = avoid == null ? BlockPos.ZERO : avoid.immutable();
        this.reserved = reserved == null ? pos -> false : reserved;
        this.failure = null;
        this.checkedShulkers.clear();
        this.clicks = 0;
        this.nameAttempt = 0;
        countBefore = countItems(client, item);
        if (countItems(client, item) >= this.need) return false;
        if (settings.autoShulker && (hasShulker(client) || nextShulker(client) != null)) {
            beginShulker(client);
            return true;
        }
        if (settings.autoBuy) {
            beginSearch(client);
            return true;
        }
        return false;
    }

    public void tick(Minecraft client, BuilderNavigator navigator) {
        if (phase == Phase.IDLE || client.player == null || client.level == null || client.gameMode == null) return;
        now++;
        if (phase == Phase.CLOSE_MENU || phase == Phase.WITHDRAW || phase == Phase.AH_SCAN
                || phase == Phase.AH_CONFIRM || phase == Phase.AH_VERIFY || phase == Phase.AH_WAIT) {
            navigator.hold(client, false);
        } else {
            navigator.hold(client, sneaking);
        }
        switch (phase) {
            case PLACE_AIM -> tickPlaceAim(client);
            case PLACE_WAIT -> tickPlaceWait(client);
            case OPEN_AIM -> tickOpenAim(client);
            case OPEN_WAIT -> tickOpenWait(client);
            case WITHDRAW -> tickWithdraw(client);
            case CLOSE_MENU -> tickClose(client);
            case AH_SEARCH -> tickSearch(client);
            case AH_WAIT -> tickAhWait(client);
            case AH_SCAN -> tickAhScan(client);
            case AH_CONFIRM -> tickAhConfirm(client);
            case AH_VERIFY -> tickAhVerify(client);
            default -> { }
        }
    }

    // --- Shulker boxes -------------------------------------------------------------------

    private void beginShulker(Minecraft client) {
        BlockPos placed = nextShulker(client);
        if (placed != null) {
            shulkerPos = placed;
            status = "Opening a shulker box at " + coordinates(placed) + "...";
            phase = Phase.OPEN_AIM;
            return;
        }
        status = "Placing a shulker box for " + item.getName().getString() + "...";
        phase = Phase.PLACE_AIM;
        if (!preparePlacement(client)) fail("No room to place a shulker box nearby.");
    }

    private boolean preparePlacement(Minecraft client) {
        int slot = shulkerSlot(client);
        if (slot < 0) return false;
        if (slot >= 9) {
            int selected = client.player.getInventory().getSelectedSlot();
            client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, slot, selected,
                    ClickType.SWAP, client.player);
            slot = selected;
        }
        client.player.getInventory().setSelectedSlot(slot);
        ItemStack stack = client.player.getMainHandItem();
        for (BlockPos spot : placementSpots(client)) {
            PlacementPlanner.Plan candidate = PlacementPlanner.plan(client, spot, defaultState(stack), stack, true);
            if (candidate == null) continue;
            plan = candidate;
            shulkerPos = spot;
            yaw = candidate.yaw();
            pitch = candidate.pitch();
            sneaking = true;
            deadline = Integer.MAX_VALUE;
            return true;
        }
        return false;
    }

    private void tickPlaceAim(Minecraft client) {
        if (plan == null) {
            if (!preparePlacement(client)) fail("No room to place a shulker box nearby.");
            return;
        }
        client.player.setYRot(yaw);
        client.player.setXRot(pitch);
        if (deadline == Integer.MAX_VALUE) deadline = now + 30;
        if (!aligned(client, yaw, pitch, true)) {
            if (now > deadline) fail("Could not align to place a shulker box.");
            return;
        }
        var result = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, plan.hit());
        if (result.consumesAction()) client.player.swing(InteractionHand.MAIN_HAND);
        plan = null;
        deadline = now + 60;
        status = "Waiting for the shulker box at " + coordinates(shulkerPos) + "...";
        phase = Phase.PLACE_WAIT;
    }

    private void tickPlaceWait(Minecraft client) {
        BlockState state = client.level.getBlockState(shulkerPos);
        if (state.getBlock() instanceof ShulkerBoxBlock) {
            placedShulkers.add(shulkerPos);
            status = "Opening the shulker box at " + coordinates(shulkerPos) + "...";
            phase = Phase.OPEN_AIM;
            deadline = Integer.MAX_VALUE;
            return;
        }
        if (now > deadline) fail("The shulker box placement did not confirm.");
    }

    private void tickOpenAim(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 point = Vec3.atCenterOf(shulkerPos);
        Vec3 delta = point.subtract(eye);
        yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        pitch = (float) Math.toDegrees(Math.atan2(-delta.y, Math.hypot(delta.x, delta.z)));
        client.player.setYRot(yaw);
        client.player.setXRot(pitch);
        sneaking = false;
        if (deadline == Integer.MAX_VALUE) deadline = now + 30;
        if (!aligned(client, yaw, pitch, false)) {
            if (now > deadline) fail("Could not align to open the shulker box.");
            return;
        }
        var hit = new BlockHitResult(point, Direction.UP, shulkerPos, false);
        var result = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) client.player.swing(InteractionHand.MAIN_HAND);
        deadline = now + 60;
        status = "Waiting for the shulker box menu...";
        phase = Phase.OPEN_WAIT;
    }

    private void tickOpenWait(Minecraft client) {
        if (client.player.containerMenu != client.player.inventoryMenu) {
            watchedMenu = client.player.containerMenu;
            deadline = now + 80;
            status = "Taking " + item.getName().getString() + " from the shulker box...";
            phase = Phase.WITHDRAW;
            return;
        }
        if (now > deadline) fail("Could not open the shulker box at " + coordinates(shulkerPos) + ".");
    }

    private void tickWithdraw(Minecraft client) {
        AbstractContainerMenu menu = client.player.containerMenu;
        if (menu == client.player.inventoryMenu) { phase = Phase.CLOSE_MENU; return; }
        boolean moved = false;
        for (int i = 0; i < menu.slots.size() && countItems(client, item) < need; i++) {
            var slot = menu.slots.get(i);
            if (slot.container == client.player.getInventory() || !slot.getItem().is(item)) continue;
            if (clicks++ > 72) break;
            client.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, client.player);
            moved = true;
        }
        if (!moved || countItems(client, item) >= need) phase = Phase.CLOSE_MENU;
        if (now > deadline) phase = Phase.CLOSE_MENU;
    }

    private void tickClose(Minecraft client) {
        closeMenu(client);
        if (countItems(client, item) > countBefore) { succeed(); return; }
        if (shulkerPos != null) checkedShulkers.add(shulkerPos);
        BlockPos next = nextShulker(client);
        if (settings.autoShulker && next != null) {
            shulkerPos = next;
            deadline = Integer.MAX_VALUE;
            status = "Opening a shulker box at " + coordinates(next) + "...";
            phase = Phase.OPEN_AIM;
            return;
        }
        if (settings.autoBuy) { beginSearch(client); return; }
        fail("No shulker box holds " + item.getName().getString() + ". Add materials, then Resume.");
    }

    // --- Auction house -------------------------------------------------------------------

    private void beginSearch(Minecraft client) {
        query = nameAttempt == 0
                ? new ItemStack(item).getHoverName().getString()
                : BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
        status = "Searching /ah for " + query + "...";
        deadline = now + 100;
        phase = Phase.AH_SEARCH;
    }

    private void tickSearch(Minecraft client) {
        client.player.connection.sendCommand("ah " + query);
        deadline = now + 100;
        status = "Waiting for the auction house...";
        phase = Phase.AH_WAIT;
    }

    private void tickAhWait(Minecraft client) {
        if (client.player.containerMenu != client.player.inventoryMenu) {
            watchedMenu = client.player.containerMenu;
            status = "Checking auction listings for " + item.getName().getString() + "...";
            phase = Phase.AH_SCAN;
            return;
        }
        if (now > deadline) {
            nameAttempt++;
            if (nameAttempt < 2) beginSearch(client);
            else fail("The auction house did not open for /ah " + query + ".");
        }
    }

    private void tickAhScan(Minecraft client) {
        AbstractContainerMenu menu = client.player.containerMenu;
        if (menu == client.player.inventoryMenu) { fail("The auction house closed before a listing was found."); return; }
        var bestSlot = new int[]{-1};
        var best = new double[]{Double.POSITIVE_INFINITY};
        var bestCount = new int[]{1};
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            if (slot.container == client.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (!stack.is(item)) continue;
            OptionalPrice price = priceOf(stack, client);
            if (price == null) continue;
            double unit = AhPriceParser.unitPrice(price.value, stack.getCount());
            if (unit > settings.maxAhPrice) continue;
            if (unit < best[0]) { best[0] = unit; bestSlot[0] = slot.index; bestCount[0] = stack.getCount(); }
        }
        if (bestSlot[0] < 0) {
            closeMenu(client);
            if (nameAttempt < 1) { nameAttempt = 1; beginSearch(client); return; }
            fail("No affordable " + item.getName().getString() + " listing under "
                    + (long) settings.maxAhPrice + " coins per item.");
            return;
        }
        listingPrice = best[0] * bestCount[0];
        listingCount = bestCount[0];
        countBefore = countItems(client, item);
        watchedMenu = menu;
        clicks++;
        client.gameMode.handleInventoryMouseClick(menu.containerId, bestSlot[0], 0, ClickType.PICKUP, client.player);
        deadline = now + 80;
        status = "Buying " + item.getName().getString() + " for " + (long) listingPrice + " coins...";
        phase = Phase.AH_CONFIRM;
    }

    private void tickAhConfirm(Minecraft client) {
        AbstractContainerMenu menu = client.player.containerMenu;
        if (menu == client.player.inventoryMenu) { phase = Phase.AH_VERIFY; deadline = now + 80; return; }
        if (menu == watchedMenu) {
            if (now > deadline) { closeMenu(client); fail("The auction house did not respond to the purchase."); }
            return;
        }
        // A confirmation menu opened: re-verify the item and price before confirming anything.
        watchedMenu = menu;
        OptionalPrice confirmed = null;
        int confirmSlot = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            if (slot.container == client.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (stack.is(item)) {
                if (confirmed == null) confirmed = priceOf(stack, client);
                continue;
            }
            // Never treat the listed item's own buy lore as the confirmation control.
            if (confirmSlot < 0 && CONFIRM_BUTTON.matcher(describe(stack, client)).find()) confirmSlot = slot.index;
        }
        if (confirmed == null) {
            closeMenu(client);
            fail("Refused the purchase: the confirmation does not show " + item.getName().getString() + ".");
            return;
        }
        double unit = AhPriceParser.unitPrice(confirmed.value, listingCount);
        if (unit > settings.maxAhPrice || Math.abs(confirmed.value - listingPrice) > Math.max(1, listingPrice * .02)) {
            closeMenu(client);
            fail("Refused the purchase: price " + (long) confirmed.value + " coins differs from the listing or is too expensive.");
            return;
        }
        if (confirmSlot < 0) {
            closeMenu(client);
            fail("Refused the purchase: no confirmation button was found.");
            return;
        }
        clicks++;
        client.gameMode.handleInventoryMouseClick(menu.containerId, confirmSlot, 0, ClickType.PICKUP, client.player);
        deadline = now + 80;
        status = "Confirming the purchase of " + item.getName().getString() + "...";
        phase = Phase.AH_VERIFY;
    }

    private void tickAhVerify(Minecraft client) {
        if (countItems(client, item) > countBefore) {
            if (client.player.containerMenu != client.player.inventoryMenu) closeMenu(client);
            succeed();
            return;
        }
        if (now > deadline) {
            if (client.player.containerMenu != client.player.inventoryMenu) closeMenu(client);
            fail("The purchase of " + item.getName().getString() + " did not complete.");
        }
    }

    // --- Shared helpers ------------------------------------------------------------------

    private void succeed() {
        abort(null);
    }

    private void fail(String message) {
        failure = message;
        phase = Phase.IDLE;
        plan = null;
        status = "";
    }

    private void closeMenu(Minecraft client) {
        if (client.player == null) return;
        if (client.player.containerMenu != client.player.inventoryMenu) {
            client.player.connection.send(new ServerboundContainerClosePacket(client.player.containerMenu.containerId));
            client.player.containerMenu = client.player.inventoryMenu;
        }
        if (client.screen instanceof AbstractContainerScreen<?>) client.setScreen(null);
    }

    private BlockPos nextShulker(Minecraft client) {
        BlockPos nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (BlockPos pos : placedShulkers) {
            if (checkedShulkers.contains(pos)) continue;
            double candidate = client.player.position().distanceToSqr(Vec3.atCenterOf(pos));
            if (candidate < distance) { distance = candidate; nearest = pos; }
        }
        return nearest;
    }

    private boolean hasShulker(Minecraft client) { return shulkerSlot(client) >= 0; }

    private int shulkerSlot(Minecraft client) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock)
                return i;
        }
        return -1;
    }

    private List<BlockPos> placementSpots(Minecraft client) {
        List<BlockPos> spots = new ArrayList<>();
        BlockPos base = client.player.blockPosition();
        for (int radius = 1; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    spots.add(base.offset(dx, 0, dz));
                }
            }
        }
        spots.removeIf(pos -> !usableSpot(client, pos));
        spots.sort(Comparator.comparingDouble(pos -> client.player.position().distanceToSqr(Vec3.atCenterOf(pos))));
        return spots;
    }

    private boolean usableSpot(Minecraft client, BlockPos pos) {
        if (pos.equals(avoid) || reserved.test(pos) || !MaterialTracker.isLoaded(client.level, pos)
                || !MaterialTracker.isLoaded(client.level, pos.below()) || !client.level.getWorldBorder().isWithinBounds(pos))
            return false;
        if (!client.level.getBlockState(pos).canBeReplaced()) return false;
        var ground = client.level.getBlockState(pos.below());
        return ground.isCollisionShapeFullBlock(client.level, pos.below());
    }

    private static BlockState defaultState(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock().defaultBlockState()
                : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }

    private static int countItems(Minecraft client, Item item) {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static boolean aligned(Minecraft client, float yaw, float pitch, boolean sneak) {
        var sent = (BuilderLocalPlayerAccessor) client.player;
        return Math.abs(Mth.wrapDegrees(sent.donut$lastSentYaw() - yaw)) <= .001f
                && Math.abs(sent.donut$lastSentPitch() - pitch) <= .001f
                && sent.donut$lastSentInput() != null && sent.donut$lastSentInput().shift() == sneak;
    }

    private record OptionalPrice(double value) { }

    private OptionalPrice priceOf(ItemStack stack, Minecraft client) {
        var parsed = AhPriceParser.parse(describeLines(stack, client));
        return parsed.isPresent() ? new OptionalPrice(parsed.getAsDouble()) : null;
    }

    private String describe(ItemStack stack, Minecraft client) {
        return String.join("\n", describeLines(stack, client));
    }

    private List<String> describeLines(ItemStack stack, Minecraft client) {
        List<String> lines = new ArrayList<>();
        lines.add(stack.getHoverName().getString());
        for (var line : stack.getTooltipLines(Item.TooltipContext.EMPTY, client.player, TooltipFlag.NORMAL))
            lines.add(line.getString());
        return lines;
    }

    private static String coordinates(BlockPos pos) { return pos.getX() + ", " + pos.getY() + ", " + pos.getZ(); }
}
