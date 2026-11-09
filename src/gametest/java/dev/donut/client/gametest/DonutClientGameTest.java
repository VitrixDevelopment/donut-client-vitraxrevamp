package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.gui.DonutScreen;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

/** Exercises real GUI input, remaining module controls and presentation in a disposable flat world. */
public final class DonutClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientConfig config = new ClientConfig();
        AtomicInteger saves = new AtomicInteger();
        context.getInput().resizeWindow(1920, 1080);
        try (var world = context.worldBuilder().create()) {
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("fill -16 64 -8 16 64 16 minecraft:smooth_stone");
            createGlassScene(world.getServer());
            context.waitTicks(5);
            world.getClientWorld().waitForChunksRender();
            GuiTestInput.openAnimated(context, () -> new DonutScreen(config, saves::incrementAndGet));
            GuiTestInput.assertGlassReady(context);
            GuiTestInput.assertModuleRows(context, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            require(!GuiTestInput.visible(context, "module0") && !GuiTestInput.visible(context, "module1"),
                    "Removed module tiles remain in the workspace");
            GuiTestInput.assertSelectionAnimation(context, 5);
            require(!config.fullbright.enabled && saves.get() == 0, "Selecting a module changed its state");
            assertDonutAnimation(context, config);
            GuiTestInput.click(context, "enable5");
            require(config.fullbright.enabled, "Fullbright switch did not update its setting");
            GuiTestInput.click(context, "favorite5");
            GuiTestInput.click(context, "tab1");
            require(config.fullbright.favorite && GuiTestInput.visible(context, "module5")
                    && !GuiTestInput.visible(context, "module2"), "Favorites did not filter remaining modules");
            GuiTestInput.click(context, "tab5");
            GuiTestInput.click(context, "search");
            GuiTestInput.type(context, "camera");
            require(GuiTestInput.visible(context, "module2") && !GuiTestInput.visible(context, "module5"),
                    "Search did not filter remaining modules");
            GuiTestInput.click(context, "tab5");
            GuiTestInput.assertContentClipped(context);
            screenshot(context, "donut-source-only-workspace");
            GuiTestInput.closeAnimated(context);
            GuiTestInput.assertGlassReleased(context);
        }
    }


    /** A colored facade makes the scene blur and translucent panels visible in the final preview. */
    private static void createGlassScene(TestServerContext server) {
        server.runCommand("fill -16 65 -5 16 80 -5 minecraft:polished_diorite");
        server.runCommand("fill -15 66 -4 -9 79 -4 minecraft:cyan_terracotta");
        server.runCommand("fill -7 66 -4 -1 79 -4 minecraft:white_concrete");
        server.runCommand("fill 1 66 -4 7 79 -4 minecraft:orange_terracotta");
        server.runCommand("fill 9 66 -4 15 79 -4 minecraft:blue_concrete");
        server.runCommand("fill -16 72 -3 16 72 -3 minecraft:polished_diorite");
        server.runCommand("tp @a 0.5 65 12.5 180 -6");
    }



    private static Path screenshot(ClientGameTestContext context, String name) {
        return context.takeScreenshot(TestScreenshotOptions.of(name));
    }

    private static void assertDonutAnimation(ClientGameTestContext context, ClientConfig config) {
        Path first = screenshot(context, "donut-logo-spin-a");
        context.waitTicks(12);
        Path second = screenshot(context, "donut-logo-spin-b");
        require(changed(first, second, 1134, 220, 100, 100) > 100,
                "The category-header donut did not spin");
        require(changed(first, second, 322, 148, 28, 28) > 8,
                "The name-logo donut did not spin");
        context.runOnClient(client -> config.clickGuiStyle.animationSpeed = 0);
        context.waitTicks(2);
        Path stillA = screenshot(context, "donut-logo-reduced-motion-a");
        context.waitTicks(12);
        Path stillB = screenshot(context, "donut-logo-reduced-motion-b");
        require(changedArt(stillA, stillB, 1134, 220, 100, 100) == 0
                        && changedArt(stillA, stillB, 322, 148, 28, 28) == 0,
                "The donut ignored reduced-motion settings");
        context.runOnClient(client -> config.clickGuiStyle.animationSpeed = 1);
    }

    private static int changedArt(Path a, Path b, int x, int y, int w, int h) {
        try {
            BufferedImage first = ImageIO.read(a.toFile()), second = ImageIO.read(b.toFile());
            int count = 0, sampled = 0;
            for (int py = y; py < y + h; py++) for (int px = x; px < x + w; px++) {
                int color = first.getRGB(px, py), r = color >> 16 & 255, g = color >> 8 & 255, blue = color & 255;
                if (r > 100 && (r > g * 1.25 && blue > g * 1.1 || r > g * 1.4 && r > blue * 1.5)) {
                    sampled++;
                    if (color != second.getRGB(px, py)) count++;
                }
            }
            require(sampled > (w > 30 ? 100 : 8), "The donut pixel check did not sample its visible surface");
            return count;
        } catch (IOException error) { throw new AssertionError(error); }
    }

    private static int changed(Path a, Path b, int x, int y, int w, int h) {
        try {
            BufferedImage first = ImageIO.read(a.toFile()), second = ImageIO.read(b.toFile());
            int count = 0;
            for (int py = y; py < y + h; py++) for (int px = x; px < x + w; px++) {
                if (first.getRGB(px, py) != second.getRGB(px, py)) count++;
            }
            return count;
        } catch (IOException error) { throw new AssertionError(error); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
