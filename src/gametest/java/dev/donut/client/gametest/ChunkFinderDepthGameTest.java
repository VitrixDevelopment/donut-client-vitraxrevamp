package dev.donut.client.gametest;

import static dev.donut.client.gametest.BaseFinderTestHarness.require;
import dev.donut.client.chunkfinder.GeodeGlowScan;
import dev.donut.client.chunkfinder.SuspectObjects.Category;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.phys.Vec3;

/** Below-zero formations and synthetic server light packets matching ANBS's anomaly signature. */
public final class ChunkFinderDepthGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 800);
        try (var test = new BaseFinderTestHarness(context, "base-finder-depth"); var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("fill -4 -59 -8 4 -59 0 minecraft:stone");
            server.runCommand("tp @a 0.5 -58 -5.5 0 0");
            server.runCommand("fill -1 -33 4 1 -33 4 minecraft:budding_amethyst");
            server.runCommand("fill -1 -32 4 1 -32 4 minecraft:amethyst_cluster[facing=up]");
            server.runCommand("setblock -3 -63 3 minecraft:beehive");
            server.runCommand("setblock 4 -58 2 minecraft:chest");
            context.waitTicks(5);
            world.getClientWorld().waitForChunksRender();
            context.waitFor(client -> client.player.onGround() && Math.abs(client.player.getY() + 58) < .01);
            context.runOnClient(client -> {
                var settings = test.config.chunkFinder;
                settings.enabled = true;
                settings.method = "ANBS+ Scan";
                settings.simulationDistance = 2;
                settings.sensitivity = 1;
                settings.matureClusters = 2;
            });
            test.awaitCount(Category.GEODES, 1);
            test.awaitCount(Category.CONTAINERS, 1);
            context.runOnClient(client -> {
                var formation = test.scanner.snapshot().stream().filter(m -> m.category() == Category.GEODES).findFirst().orElseThrow();
                require(formation.bounds().minX == -1 && formation.bounds().maxX == 2,
                        "Negative-coordinate formation split across the chunk boundary");
                require(formation.bounds().minY == -33 && formation.bounds().maxY == -31,
                        "Below-zero geometry lost its actual height");
                require(test.scanner.snapshot().stream().anyMatch(m -> m.category() == Category.GROWTH && m.bounds().minY == -63),
                        "Bottom-world bee clue was missed");
            });
            require(test.count(Category.ANOMALIES) == 0, "Ordinary vanilla light produced ANBS anomalies");
            sendLight(server, true);
            context.waitFor(client -> client.level.getLightEngine().getLayerListener(net.minecraft.world.level.LightLayer.BLOCK)
                    .getLightValue(new BlockPos(4, -50, 8)) == 4);
            context.runOnClient(client -> {
                int cells = new GeodeGlowScan().scan(client.level, client.level.getChunk(0, 0), null);
                require(cells == 18, "ANBS light signature had " + cells + " edge cells instead of 18");
            });
            test.awaitCount(Category.ANOMALIES, 18);
            test.aim(new Vec3(4.5, -50.5, 8.5));
            require(test.focus() != null && test.focus().target().category() == Category.ANOMALIES,
                    "Light anomaly could not be inspected");
            test.screenshot("spectre-basefinder-04-anbs-light-fixture");
            context.runOnClient(client -> test.config.chunkFinder.method = "Activity clues");
            test.awaitCount(Category.ANOMALIES, 0);
            context.runOnClient(client -> test.config.chunkFinder.method = "ANBS+ Scan");
            test.awaitCount(Category.ANOMALIES, 18);
            sendLight(server, false);
            test.awaitCount(Category.ANOMALIES, 0);
            server.runCommand("setblock 4 -58 2 minecraft:air");
            test.awaitCount(Category.CONTAINERS, 0);
            context.runOnClient(client -> test.config.chunkFinder.enabled = false);
            context.waitTicks(2);
            require(test.scanner.snapshot().isEmpty() && test.scanner.visibleChunks().isEmpty(), "Disable retained light findings");
        }
    }

    /** This disposable fixture changes transmitted light only; server terrain/light remain vanilla. */
    private static void sendLight(TestServerContext server, boolean anomaly) {
        server.runOnServer(mc -> {
            var level = mc.overworld();
            var engine = level.getChunkSource().getLightEngine();
            var packet = new ClientboundLightUpdatePacket(new ChunkPos(0, 0), engine, null, null);
            var data = packet.getLightData();
            data.getBlockYMask().clear();
            data.getEmptyBlockYMask().clear();
            data.getBlockUpdates().clear();
            int section = -4 - engine.getMinLightSection();
            if (anomaly) {
                var layer = new DataLayer();
                for (int x : new int[]{4, 8, 12}) layer.set(x, -50 & 15, 8, 4);
                data.getBlockYMask().set(section);
                data.getBlockUpdates().add(layer.getData());
            } else data.getEmptyBlockYMask().set(section);
            mc.getPlayerList().getPlayers().getFirst().connection.send(packet);
        });
    }
}
