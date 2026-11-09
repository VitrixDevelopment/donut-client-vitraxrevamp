package dev.donut.client.gametest;

import dev.donut.client.chunkfinder.ChunkFinderScanner;
import dev.donut.client.chunkfinder.SuspectObjects;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.gui.BaseFinderPanel;
import dev.donut.client.render.HighlightRenderer;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

final class BaseFinderTestHarness implements AutoCloseable {
    final ClientConfig config = new ClientConfig();
    final ChunkFinderScanner scanner = new ChunkFinderScanner(config);
    final ClientGameTestContext context;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final HighlightRenderer renderer;

    BaseFinderTestHarness(ClientGameTestContext context, String id) {
        this.context = context;
        renderer = context.computeOnClient(client -> {
            var created = new HighlightRenderer(config, scanner);
            created.register();
            ClientTickEvents.END_CLIENT_TICK.register(mc -> { if (active.get()) scanner.tick(mc); });
            HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("donut-gametest", id),
                    (graphics, delta) -> { if (active.get()) BaseFinderPanel.render(graphics, config, scanner); });
            return created;
        });
    }

    int count(SuspectObjects.Category category) {
        return context.computeOnClient(client -> (int) scanner.snapshot().stream().filter(m -> m.category() == category).count());
    }

    void awaitCount(SuspectObjects.Category category, int count) {
        context.waitFor(client -> scanner.snapshot().stream().filter(m -> m.category() == category).count() == count);
    }

    void aim(Vec3 target) {
        context.runOnClient(client -> {
            Vec3 direction = target.subtract(client.player.getEyePosition());
            float yaw = (float) Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90;
            float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
            client.player.setYRot(yaw); client.player.yRotO = yaw;
            client.player.setXRot(pitch); client.player.xRotO = pitch;
        });
        context.waitTicks(4);
    }

    SuspectObjects.Focus focus() {
        return context.computeOnClient(client -> {
            var camera = client.gameRenderer.getMainCamera();
            var direction = camera.forwardVector();
            return SuspectObjects.focus(scanner.snapshot(), camera.position(), new Vec3(direction.x(), direction.y(), direction.z()), config.chunkFinder.objectRange);
        });
    }

    void screenshot(String name) {
        context.runOnClient(client -> { client.getToastManager().clear(); client.gui.getChat().clearMessages(false); });
        context.waitTick();
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    @Override public void close() {
        active.set(false);
        context.runOnClient(client -> { config.chunkFinder.enabled = false; scanner.clear(); renderer.close(); });
    }

    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
