package dev.donut.client.gametest;

import static dev.donut.client.gametest.BaseFinderTestHarness.require;
import dev.donut.client.chunkfinder.SuspectObjects;
import dev.donut.client.chunkfinder.SuspectObjects.Category;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** Spectre object counts, formation grouping, live growth and received block updates. */
public final class ChunkFinderGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        int originalDistance = context.computeOnClient(client -> client.options.renderDistance().get());
        context.runOnClient(client -> client.options.renderDistance().set(6));
        context.getInput().resizeWindow(1280, 800);
        try (var test = new BaseFinderTestHarness(context, "base-finder-objects"); var world = context.worldBuilder().create()) {
            var settings = test.config.chunkFinder;
            test.config.clickGuiStyle.panelsColor = 0x4AB5FF; // The alert must stay dark with a blue workspace preference.
            settings.method = "Activity clues";
            fixtures(world.getServer());
            context.waitTicks(5);
            world.getClientWorld().waitForChunksRender();
            context.waitFor(client -> client.player.onGround() && Math.abs(client.player.getY() - 65) < .01);
            require(test.scanner.snapshot().isEmpty(), "Disabled Base Finder produced markers");
            context.runOnClient(client -> settings.enabled = true);
            test.awaitCount(Category.GEODES, 1);
            test.awaitCount(Category.CONTAINERS, 3);
            test.awaitCount(Category.DISPLAYS, 1);
            require(test.count(Category.HOPPERS) == 1, "Hopper missing");
            require(test.count(Category.MACHINES) == 2, "Furnace/piston count incorrect");
            require(test.count(Category.REDSTONE) == 2, "Wire/comparator count incorrect");
            require(test.count(Category.ENDER_STORAGE) == 1, "Ender chest missing");
            require(test.count(Category.SPAWNERS) == 2, "Spawner/trial spawner count incorrect");
            context.runOnClient(client -> {
                var report = test.scanner.activityReport(ChunkPos.asLong(0, 0));
                require(report != null && report.score() == 6, "Bee evidence did not use Spectre's conservative points");
                var formation = test.scanner.snapshot().stream().filter(m -> m.category() == Category.GEODES).findFirst().orElseThrow();
                require(formation.bounds().minX == 15 && formation.bounds().maxX == 19,
                        "Amethyst formation split at a chunk border");
                require(formation.bounds().minY == 70 && formation.bounds().maxY == 72,
                        "Formation bounds became a chunk-height column");
                require(formation.detail().contains("12 mature / 4 buds - 75%"), "Crystal maturity breakdown incorrect");
                require(formation.icons().getOrDefault(SuspectObjects.Icon.item(Items.SMALL_AMETHYST_BUD), 0) == 1,
                        "Small bud icon/count was substituted");
                require(formation.icons().getOrDefault(SuspectObjects.Icon.item(Items.MEDIUM_AMETHYST_BUD), 0) == 1,
                        "Medium bud icon/count was substituted");
                require(formation.icons().getOrDefault(SuspectObjects.Icon.item(Items.LARGE_AMETHYST_BUD), 0) == 2,
                        "Large bud icon/count was substituted");
            });
            context.waitFor(client -> test.scanner.snapshot().stream().filter(m -> m.detail().contains("blocks long")).count() == 4);
            for (String length : new String[]{"18 blocks", "15 blocks", "20 blocks", "24 blocks"}) {
                context.runOnClient(client -> require(test.scanner.snapshot().stream().anyMatch(m -> m.detail().startsWith(length)),
                        "Missing spatial growth length: " + length));
            }
            test.aim(new Vec3(10.5, 65.5, 8.5));
            require(test.focus() != null && test.focus().target().category() == Category.MACHINES, "Look-to-inspect missed the furnace");
            test.screenshot("spectre-basefinder-01-object-panel");
            GuiTestInput.assertGlassReady(context);
            test.aim(new Vec3(16.5, 71, 31.5));
            require(test.focus() != null && test.focus().target().category() == Category.GEODES, "Look-to-inspect missed the geode");
            test.screenshot("spectre-basefinder-02-geode-panel");
            test.aim(new Vec3(-4.5, 76, 26.5));
            require(test.focus() != null && test.focus().target().label().equals("Cave vines growth"), "Look-to-inspect missed the cave vines");
            test.screenshot("spectre-basefinder-03-growth-panel");

            context.runOnClient(client -> settings.caveVineLength = 25);
            context.waitFor(client -> test.scanner.snapshot().stream().noneMatch(m -> m.label().equals("Cave vines growth")));
            context.runOnClient(client -> settings.caveVineLength = 20);
            context.waitFor(client -> test.scanner.snapshot().stream().anyMatch(m -> m.label().equals("Cave vines growth")));
            world.getServer().runCommand("fill -5 66 26 -5 88 26 minecraft:cave_vines_plant[berries=true]");
            world.getServer().runCommand("setblock -5 65 26 minecraft:cave_vines[age=25,berries=true]");
            context.waitTicks(8);
            context.runOnClient(client -> {
                var vine = test.scanner.snapshot().stream().filter(m -> m.label().equals("Cave vines growth")).findFirst().orElseThrow();
                require(vine.icons().getOrDefault(SuspectObjects.CAVE_VINES_ICON, 0) == 24,
                        "Berries changed vine length or substituted a berry inventory icon");
            });
            context.runOnClient(client -> settings.maturePercent = 80);
            test.awaitCount(Category.GEODES, 0);
            context.runOnClient(client -> settings.maturePercent = 75);
            test.awaitCount(Category.GEODES, 1);
            world.getServer().runCommand("setblock 15 71 30 minecraft:medium_amethyst_bud[facing=up]");
            test.awaitCount(Category.GEODES, 0);
            context.runOnClient(client -> settings.smartAdjustment = false);
            test.awaitCount(Category.GEODES, 2);
            context.runOnClient(client -> settings.smartAdjustment = true);
            world.getServer().runCommand("setblock 15 71 30 minecraft:amethyst_cluster[facing=up]");
            test.awaitCount(Category.GEODES, 1);
            context.runOnClient(client -> settings.amethyst = false);
            test.awaitCount(Category.GEODES, 0);
            context.runOnClient(client -> settings.amethyst = true);
            test.awaitCount(Category.GEODES, 1);
            context.runOnClient(client -> settings.objects.put("hoppers", false));
            test.awaitCount(Category.HOPPERS, 0);
            context.runOnClient(client -> settings.objects.put("hoppers", true));
            test.awaitCount(Category.HOPPERS, 1);
            world.getServer().runCommand("setblock 2 65 8 minecraft:air");
            test.awaitCount(Category.CONTAINERS, 2);

            // Classic's bud-support test crosses from x=79 into the adjacent chunk at x=80.
            world.getServer().runOnServer(mc -> { mc.overworld().getChunk(4, 0); mc.overworld().getChunk(5, 0); });
            world.getServer().runCommand("setblock 66 0 5 minecraft:stone");
            world.getServer().runCommand("fill 66 64 5 77 64 5 minecraft:stone");
            world.getServer().runCommand("fill 66 65 5 77 65 5 minecraft:amethyst_cluster[facing=up]");
            context.runOnClient(client -> { settings.method = "Classic"; settings.simulationDistance = 2; settings.sensitivity = 6; });
            context.waitFor(client -> test.scanner.visibleChunks().containsKey(ChunkPos.asLong(4, 0)));
            context.runOnClient(client -> settings.sensitivity = 20);
            context.waitFor(client -> !test.scanner.visibleChunks().containsKey(ChunkPos.asLong(4, 0)));
            context.runOnClient(client -> settings.sensitivity = 6);
            context.waitFor(client -> test.scanner.visibleChunks().containsKey(ChunkPos.asLong(4, 0)));
            world.getServer().runCommand("setblock 80 65 5 minecraft:budding_amethyst");
            world.getServer().runCommand("setblock 79 65 5 minecraft:small_amethyst_bud[facing=west]");
            context.waitFor(client -> !test.scanner.visibleChunks().containsKey(ChunkPos.asLong(4, 0)));
            world.getServer().runCommand("setblock 80 65 5 minecraft:stone");
            context.waitFor(client -> test.scanner.visibleChunks().containsKey(ChunkPos.asLong(4, 0)));
            context.runOnClient(client -> { settings.method = "Activity clues"; settings.objectRange = 16; });
            context.waitTicks(8);
            context.runOnClient(client -> {
                var eye = client.player.getEyePosition();
                require(test.scanner.snapshot().stream().allMatch(m -> m.bounds().distanceToSqr(eye) <= 16 * 16), "Object range retained distant markers");
                settings.enabled = false;
            });
            context.waitTicks(2);
            require(test.scanner.snapshot().isEmpty() && test.scanner.visibleChunks().isEmpty(), "Disable retained discovery data");
            context.runOnClient(client -> { settings.enabled = true; settings.objectRange = 128; });
            test.awaitCount(Category.CONTAINERS, 2);
            world.getServer().runCommand("tp @a 600.5 65 600.5");
            context.waitTicks(100);
            context.waitFor(client -> test.scanner.snapshot().isEmpty());
            context.runOnClient(client -> require(test.scanner.activityReport(ChunkPos.asLong(0, 0)) == null,
                    "Unloaded chunk retained its activity report"));
        } finally {
            context.runOnClient(client -> client.options.renderDistance().set(originalDistance));
        }
    }

    private static void fixtures(TestServerContext server) {
        server.runCommand("gamemode creative @a");
        server.runCommand("gamerule minecraft:random_tick_speed 0");
        server.runCommand("time set noon");
        server.runCommand("weather clear");
        server.runCommand("fill -16 64 -8 47 64 47 minecraft:stone");
        server.runCommand("fill -16 0 -16 47 0 47 minecraft:stone");
        server.runCommand("tp @a 5.5 65 -5.5 0 5");
        for (String command : new String[]{"2 65 8 chest", "4 65 8 trapped_chest", "6 65 8 barrel",
                "8 65 8 hopper", "10 65 8 furnace", "12 65 8 piston", "2 65 10 redstone_wire",
                "4 65 10 comparator", "6 65 10 ender_chest", "8 65 10 spawner", "10 65 10 trial_spawner",
                "12 65 10 beehive", "14 65 10 bee_nest[honey_level=5]"}) server.runCommand("setblock " + command.replaceFirst(" ([a-z_]+(?:\\[.*\\])?)$", " minecraft:$1"));
        server.runCommand("summon minecraft:armor_stand 7.5 65 14.5");
        for (int x : new int[]{-10, -8}) {
            int top = x == -10 ? 82 : 67;
            server.runCommand("fill " + (x-1) + " 65 19 " + (x+1) + " " + (top+1) + " 21 minecraft:glass");
            server.runCommand("fill " + x + " 65 20 " + x + " " + (top+1) + " 20 minecraft:water");
            server.runCommand("fill " + x + " 65 20 " + x + " " + (top-1) + " 20 minecraft:kelp_plant");
            server.runCommand("setblock " + x + " " + top + " 20 minecraft:kelp[age=25]");
        }
        for (int x : new int[]{-5, -3}) {
            server.runCommand("setblock " + x + " 64 20 minecraft:dirt");
            server.runCommand("fill " + x + " 65 20 " + x + " " + (x == -5 ? 79 : 68) + " 20 minecraft:bamboo[age=1,stage=1]");
        }
        server.runCommand("fill -12 65 27 -12 84 27 minecraft:stone");
        server.runCommand("fill -12 65 26 -12 84 26 minecraft:vine[south=true]");
        server.runCommand("setblock -5 89 26 minecraft:stone");
        server.runCommand("fill -5 66 26 -5 88 26 minecraft:cave_vines_plant");
        server.runCommand("setblock -5 65 26 minecraft:cave_vines[age=25]");
        server.runCommand("fill 15 70 30 18 70 33 minecraft:budding_amethyst");
        server.runCommand("fill 15 71 30 17 71 33 minecraft:amethyst_cluster[facing=up]");
        server.runCommand("setblock 18 71 30 minecraft:small_amethyst_bud[facing=up]");
        server.runCommand("setblock 18 71 31 minecraft:medium_amethyst_bud[facing=up]");
        server.runCommand("fill 18 71 32 18 71 33 minecraft:large_amethyst_bud[facing=up]");
        server.runCommand("fill 25 70 30 26 70 30 minecraft:budding_amethyst");
        server.runCommand("fill 25 71 30 26 71 30 minecraft:small_amethyst_bud[facing=up]");
    }
}
