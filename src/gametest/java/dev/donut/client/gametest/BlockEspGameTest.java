package dev.donut.client.gametest;

import dev.donut.client.blockesp.BlockEspController;
import dev.donut.client.blockesp.OreMarker;
import dev.donut.client.blockesp.VanillaOreGenerator;
import dev.donut.client.config.BlockEspSettings;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.gui.BlockEspHud;
import dev.donut.client.render.HighlightRenderer;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.LoggerFactory;

/** Fresh seeded vanilla chunks, exact variant/Y filtering, cancellation, display and lifecycle. */
public final class BlockEspGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var config = new ClientConfig();
        config.blockEsp.radius = 1;
        Path scratch = FabricLoader.getInstance().getGameDir().resolve(".cache/blockesp-runtime-test");
        var scanner = new BlockEspController(config, scratch);
        var active = new AtomicBoolean(true);
        context.getInput().resizeWindow(1280, 800);
        int originalScale = context.computeOnClient(mc -> mc.options.guiScale().get());
        var renderer = context.computeOnClient(mc -> {
            mc.options.guiScale().set(1); mc.resizeDisplay();
            var result = new HighlightRenderer(config, null, null, null, scanner); result.register();
            ClientTickEvents.END_CLIENT_TICK.register(client -> { if (active.get()) scanner.tick(client); });
            HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("donut-gametest", "blockesp"),
                    (graphics, delta) -> { if (active.get()) BlockEspHud.render(graphics, config, scanner); });
            return result;
        });
        try (var world = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(settings -> {
            settings.setSeed(BlockEspSettings.DEFAULT_SEED);
            settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            settings.setAllowCommands(true);
        }).create()) {
            var server = world.getServer();
            require(server.computeOnServer(mc -> mc.overworld().getSeed()) == 6608149111735331168L, "World lost seed precision");
            server.runOnServer(mc -> {
                mc.overworld().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SEND_COMMAND_FEEDBACK, false, mc);
                var player = mc.getPlayerList().getPlayers().getFirst();
                player.getAbilities().flying = true; player.onUpdateAbilities();
            });
            server.runCommand("tp @a -23.5 140 -23.5");
            context.waitTicks(8);
            require(scanner.snapshot().isEmpty(), "Disabled BlockESP emitted markers");
            Set<OreMarker> expected = server.computeOnServer(mc -> {
                var result = new HashSet<OreMarker>();
                for (int z = -3; z <= -1; z++) for (int x = -3; x <= -1; x++)
                    result.addAll(VanillaOreGenerator.findOres(mc.overworld().getChunk(x, z)));
                return Set.copyOf(result);
            });
            LoggerFactory.getLogger("Donut/BlockESPTest").info("Fresh vanilla comparison: {} target ores across 9 negative-coordinate chunks", expected.size());
            context.runOnClient(mc -> config.blockEsp.enabled = true);
            context.waitFor(mc -> {
                require(!scanner.status().startsWith("Prediction failed"), scanner.status());
                return scanner.completedChunks() == 9;
            }, 2400);
            context.runOnClient(mc -> require(new HashSet<>(scanner.snapshot()).equals(expected),
                    "Seed prediction disagrees with fresh vanilla chunks: expected " + expected + ", actual " + scanner.snapshot()));

            // Both exact variants must survive scanning above and below Y=0, at negative world coordinates.
            server.runOnServer(mc -> {
                var chunk = mc.overworld().getChunk(-7, -9);
                BlockPos emerald = new BlockPos(-110, -17, -142), coal = new BlockPos(-109, 5, -142);
                BlockPos regularEmerald = new BlockPos(-108, -17, -142), regularCoal = new BlockPos(-107, 5, -142);
                chunk.setBlockState(emerald, Blocks.DEEPSLATE_EMERALD_ORE.defaultBlockState(), 0);
                chunk.setBlockState(coal, Blocks.DEEPSLATE_COAL_ORE.defaultBlockState(), 0);
                chunk.setBlockState(regularEmerald, Blocks.EMERALD_ORE.defaultBlockState(), 0);
                chunk.setBlockState(regularCoal, Blocks.COAL_ORE.defaultBlockState(), 0);
                var hits = VanillaOreGenerator.findOres(chunk);
                require(hits.contains(new OreMarker(emerald, OreMarker.Kind.EMERALD)), "Missing deepslate emerald below Y=0");
                require(hits.contains(new OreMarker(coal, OreMarker.Kind.COAL)), "Missing deepslate coal above Y=0");
                require(hits.stream().noneMatch(hit -> hit.pos().equals(regularEmerald) || hit.pos().equals(regularCoal)), "Ordinary ores matched");
            });
            context.runOnClient(mc -> { config.blockEsp.coal = false; });
            context.waitTicks(2);
            require(context.computeOnClient(mc -> scanner.snapshot().stream().noneMatch(marker -> marker.kind() == OreMarker.Kind.COAL)), "Coal filter retained coal");
            context.runOnClient(mc -> config.blockEsp.coal = true);
            context.waitTicks(2);
            require(!expected.isEmpty(), "Fresh comparison must include actual generated target ores");
            var target = expected.iterator().next().pos();
            int cameraX = target.getX() + 8, cameraY = target.getY() + 8, cameraZ = target.getZ() + 8;
            server.runCommand("fill " + (cameraX - 1) + " " + (cameraY - 1) + " " + (cameraZ - 1) + " "
                    + (cameraX + 1) + " " + (cameraY + 2) + " " + (cameraZ + 1) + " minecraft:air");
            server.runCommand("tp @a " + (cameraX + .5) + " " + cameraY + " " + (cameraZ + .5) + " 135 40");
            server.runCommand("effect give @a minecraft:night_vision infinite 0 true");
            context.runOnClient(mc -> config.blockEsp.tracers = true);
            context.waitTicks(15);
            world.getClientWorld().waitForChunksRender();
            context.takeScreenshot(TestScreenshotOptions.of("blockesp-01-predictions").withSize(1280, 800));

            // A new seed invalidates results immediately; disabling must suppress a still-running old job.
            context.runOnClient(mc -> { config.blockEsp.seed = "-1"; scanner.tick(mc); config.blockEsp.enabled = false; scanner.tick(mc); });
            context.waitTicks(30);
            require(scanner.snapshot().isEmpty(), "Stale prediction appeared after disable/seed change");
            context.runOnClient(mc -> { config.blockEsp.seed = "invalid"; config.blockEsp.enabled = true; });
            context.waitTicks(2);
            require(scanner.snapshot().isEmpty() && scanner.status().contains("seed"), "Invalid seed was silently substituted");
            context.runOnClient(mc -> config.blockEsp.enabled = false);
        } finally {
            active.set(false);
            context.runOnClient(mc -> { scanner.close(); renderer.close(); mc.options.guiScale().set(originalScale); mc.resizeDisplay(); });
        }
        require(scanner.snapshot().isEmpty(), "Disconnect retained prediction markers");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
