package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.gui.ProgressBarEditorScreen;
import dev.donut.client.gui.ProgressBarHud;
import dev.donut.client.progress.ProgressTimers;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/** Streaming category, manual milestones, saved placement and actual shader-backed HUD pixels. */
public final class ProgressBarGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientConfig config = new ClientConfig();
        AtomicInteger saves = new AtomicInteger();
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        boolean originalHideGui = context.computeOnClient(client -> client.options.hideGui);
        Path fixture = context.computeOnClient(client -> client.gameDirectory.toPath()
                .resolve("config/progress-bar-gametest.json"));
        ConfigStore store = new ConfigStore(fixture);
        Runnable save = () -> {
            saves.incrementAndGet();
            store.save(config);
        };
        context.runOnClient(client -> {
            client.options.guiScale().set(1);
            client.options.hideGui = false;
            client.resizeDisplay();
        });
        context.getInput().resizeWindow(1280, 800);

        try (var world = context.worldBuilder().create()) {
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("time set noon");
            world.getServer().runCommand("fill -12 64 -12 12 64 5 minecraft:light_gray_concrete");
            world.getServer().runCommand("fill -12 64 -12 12 75 -8 minecraft:light_gray_concrete");
            world.getServer().runCommand("tp @a 0.5 65 0.5 180 0");
            context.waitTicks(5);
            world.getClientWorld().waitForChunksRender();
            context.waitFor(client -> client.player != null && client.player.onGround()
                    && Math.abs(client.player.getY() - 65) < .01);
            GuiTestInput.openAnimated(context, () -> new DonutScreen(config, save));
            GuiTestInput.click(context, "tab0");
            GuiTestInput.click(context, "search");
            GuiTestInput.type(context, "progress");
            require(!GuiTestInput.visible(context, "module7"), "Progress Bar appeared in Visual modules");
            GuiTestInput.clickWithFade(context, "tab3", "contentProgress");
            GuiTestInput.click(context, "search");
            GuiTestInput.type(context, "progress");
            require(!GuiTestInput.visible(context, "module7"), "Progress Bar appeared in Blatant modules");
            GuiTestInput.clickWithFade(context, "tab4", "contentProgress");
            require(GuiTestInput.visible(context, "module7"), "Progress Bar is missing from Streaming");
            require(!GuiTestInput.visible(context, "module0"), "Streaming contains unrelated Visual modules");
            GuiTestInput.assertModuleRows(context, 7);
            GuiTestInput.assertSelectionAnimation(context, 7);
            GuiTestInput.rightClick(context, "module7");
            require(!config.progressBar.enabled && saves.get() == 0,
                    "Opening Progress Bar settings changed or saved its activation state");

            GuiTestInput.click(context, "enable7");
            require(config.progressBar.enabled, "Progress Bar's switch did not enable the overlay");
            GuiTestInput.click(context, "m7.shinyOutline");
            require(!config.progressBar.shinyOutline, "The shiny outline toggle did not update");
            var defaultBounds = ProgressBarHud.layout(1280, 800, config);
            GuiTestInput.click(context, "m7.overlayScale");
            require(Math.abs(config.progressBar.scale - 1.25) < .001,
                    "The overlay size slider did not select its 125% midpoint");
            var resizedBounds = ProgressBarHud.layout(1280, 800, config);
            require(Math.abs(resizedBounds.width() - defaultBounds.width() * 1.25) < .01
                            && Math.abs(resizedBounds.height() - defaultBounds.height() * 1.25) < .01
                            && Math.abs(store.load().progressBar.scale - 1.25) < .001,
                    "Overlay size did not change the rendered bounds and save its setting");
            testBasesFoundInput(context, config, store, saves);
            testTimerInput(context, config, store);
            GuiTestInput.click(context, "m7.stage1");
            require(config.progressBar.completedStages == 1, "Overworld milestone was not marked manually");
            GuiTestInput.click(context, "m7.stage3");
            require(config.progressBar.completedStages == 3, "End milestone did not include previous stages");
            GuiTestInput.click(context, "m7.stage2");
            require(config.progressBar.completedStages == 1, "Clicking a reached stage did not roll progress back");
            GuiTestInput.click(context, "m7.resetProgress");
            require(config.progressBar.completedStages == 0, "Reset did not clear all progress");
            GuiTestInput.click(context, "m7.stage2");
            require(store.load().progressBar.completedStages == 2 && !store.load().progressBar.shinyOutline,
                    "Manual progress and outline style were not persisted");
            GuiTestInput.assertContentClipped(context);
            screenshot(context, "progressbar-01-streaming-settings");

            GuiTestInput.click(context, "favorite7");
            GuiTestInput.clickWithFade(context, "tab1", "contentProgress");
            require(config.progressBar.favorite && GuiTestInput.visible(context, "module7"),
                    "Progress Bar did not appear in Favorites");
            GuiTestInput.clickWithFade(context, "tab4", "contentProgress");
            GuiTestInput.clickOpeningScreen(context, "m7.moveOverlay");
            context.waitForScreen(ProgressBarEditorScreen.class);
            int beforeMove = saves.get();
            double oldX = config.progressBar.x, oldY = config.progressBar.y;
            context.runOnClient(client -> {
                var bounds = ProgressBarHud.layout(client.getWindow().getGuiScaledWidth(),
                        client.getWindow().getGuiScaledHeight(), config);
                var screen = (ProgressBarEditorScreen) client.screen;
                double x = bounds.x() + bounds.width() / 2, y = bounds.y() + bounds.height() - 12 * bounds.scale();
                var press = mouse(x, y);
                require(screen.mouseClicked(press, false), "The counter footer did not begin dragging the complete overlay");
                require(screen.mouseDragged(mouse(x + 144, y + 96), 144, 96),
                        "The overlay editor did not consume a drag");
                screen.mouseReleased(mouse(x + 144, y + 96));
            });
            require(config.progressBar.x > oldX && config.progressBar.y > oldY,
                    "Dragging did not update the overlay's screen position");
            require(saves.get() > beforeMove && store.load().progressBar.x == config.progressBar.x
                            && store.load().progressBar.y == config.progressBar.y,
                    "Dropping the overlay did not save its placement");
            screenshot(context, "progressbar-02-dragged-empty-editor");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitForScreen(DonutScreen.class);
            GuiTestInput.settle(context);

            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeDisplay();
                assertSizeRangeAndMovement(config);
                assertLayout(config, 160, 60);
                config.progressBar.x = 1;
                config.progressBar.y = 1;
                assertLayout(config, 640, 400);
            });
            GuiTestInput.settle(context);
            GuiTestInput.click(context, "m7.shinyOutline");
            require(config.progressBar.shinyOutline, "Scaled settings did not restore the shiny outline");
            GuiTestInput.assertContentClipped(context);
            screenshot(context, "progressbar-03-streaming-settings-scaled");
            context.setScreen(() -> null);
            testProductionHud(context);
        } finally {
            context.setScreen(() -> null);
            context.runOnClient(client -> {
                client.options.guiScale().set(originalScale);
                client.options.hideGui = originalHideGui;
                client.resizeDisplay();
            });
            try {
                Files.deleteIfExists(fixture);
            } catch (IOException error) {
                throw new AssertionError("Cannot remove the isolated Progress Bar configuration fixture", error);
            }
        }
    }

    private static void testProductionHud(ClientGameTestContext context) {
        KeyMapping open = context.computeOnClient(client -> Arrays.stream(client.options.keyMappings)
                .filter(key -> key.getName().equals("key.donut.open_gui")).findFirst().orElseThrow());
        context.waitTicks(4); // Let the closed settings screen release its input suppression.
        context.getInput().pressKey(open);
        context.waitForScreen(DonutScreen.class);
        GuiTestInput.settle(context);
        ClientConfig production = context.computeOnClient(client -> config((DonutScreen) client.screen));
        boolean enabled = production.progressBar.enabled, shiny = production.progressBar.shinyOutline;
        boolean showHud = production.showHud;
        int stages = production.progressBar.completedStages, basesFound = production.progressBar.basesFound;
        double x = production.progressBar.x, y = production.progressBar.y, scale = production.progressBar.scale;
        int[] timerDurations = new int[3];
        long[] timerRemaining = new long[3];
        boolean[] timerRunning = new boolean[3];
        context.runOnClient(client -> {
            for (int index = 0; index < 3; index++) {
                timerRunning[index] = ProgressTimers.isRunning(production, index);
                ProgressTimers.pause(production, index);
                timerDurations[index] = production.progressBar.timers[index].durationSeconds;
                timerRemaining[index] = ProgressTimers.remainingMillis(production, index);
            }
        });
        AtomicReference<ProgressBarHud.Bounds> renderedBounds = new AtomicReference<>();
        try {
            context.setScreen(() -> null);
            context.runOnClient(client -> {
                client.options.guiScale().set(1);
                client.options.hideGui = false;
                client.resizeDisplay();
                production.showHud = false;
                production.progressBar.enabled = false;
                production.progressBar.x = .5;
                production.progressBar.y = .15;
                production.progressBar.completedStages = 2;
                production.progressBar.shinyOutline = true;
                production.progressBar.scale = 1;
                production.progressBar.basesFound = 0;
                for (int index = 0; index < 3; index++) ProgressTimers.setDurationSeconds(production, index, 3600);
                renderedBounds.set(ProgressBarHud.layout(client.getWindow().getGuiScaledWidth(),
                        client.getWindow().getGuiScaledHeight(), production));
            });
            context.waitTicks(2);
            Path off = screenshot(context, "progressbar-04-production-disabled");
            context.runOnClient(client -> production.progressBar.enabled = true);
            context.waitTicks(2);
            Path bright = screenshot(context, "progressbar-05-production-shiny");
            require(changedPixels(off, bright, renderedBounds.get()) > 700,
                    "Progress Bar rendered no visible pixels when the ordinary module HUD was disabled");
            assertCrosses(bright, renderedBounds.get(), 2);
            context.runOnClient(client -> production.progressBar.shinyOutline = false);
            context.waitTicks(2);
            Path empty = screenshot(context, "progressbar-06-production-empty");
            require(changedPixels(bright, empty, renderedBounds.get()) > 150,
                    "The shiny and empty rectangle styles rendered the same pixels");
            assertCrosses(empty, renderedBounds.get(), 2);
            context.runOnClient(client -> client.options.hideGui = true);
            context.waitTicks(2);
            Path f1 = screenshot(context, "progressbar-07-production-f1-hidden");
            require(changedPixels(empty, f1, renderedBounds.get()) > 700,
                    "F1 did not hide the streaming overlay");
            var normalBounds = renderedBounds.get();
            context.runOnClient(client -> {
                client.options.hideGui = false;
                production.progressBar.scale = 1.5;
                renderedBounds.set(ProgressBarHud.layout(client.getWindow().getGuiScaledWidth(),
                        client.getWindow().getGuiScaledHeight(), production));
            });
            context.waitTicks(2);
            require(Math.abs(renderedBounds.get().width() - normalBounds.width() * 1.5) < .01
                            && Math.abs(renderedBounds.get().height() - normalBounds.height() * 1.5) < .01,
                    "The 150% production overlay did not grow to its requested size");
            Path large = screenshot(context, "progressbar-08-production-large-empty");
            assertCrosses(large, renderedBounds.get(), 2);
            context.runOnClient(client -> production.progressBar.completedStages = 1);
            context.waitTicks(2);
            assertCrosses(screenshot(context, "progressbar-09-production-large-unmarked"), renderedBounds.get(), 1);
            context.runOnClient(client -> production.progressBar.completedStages = 0);
            context.waitTicks(2);
            Path zero = screenshot(context, "progressbar-10-production-large-reset");
            assertCrosses(zero, renderedBounds.get(), 0);
            context.runOnClient(client -> production.progressBar.basesFound = 27);
            context.waitTicks(2);
            Path count27 = screenshot(context, "progressbar-11-production-bases-27");
            assertCounterPixels(zero, count27, renderedBounds.get());
            context.runOnClient(client -> production.progressBar.basesFound = 999_999);
            context.waitTicks(2);
            Path maximum = screenshot(context, "progressbar-12-production-bases-max");
            assertCounterPixels(count27, maximum, renderedBounds.get());
            context.runOnClient(client -> {
                production.progressBar.basesFound = 27;
                production.progressBar.completedStages = 2;
                production.progressBar.shinyOutline = true;
            });
            context.waitTicks(2);
            Path themed = screenshot(context, "progressbar-13-production-counter-themed");
            context.runOnClient(client -> {
                ProgressTimers.setDurationSeconds(production, 0, 3600);
                ProgressTimers.setDurationSeconds(production, 1, 120);
                ProgressTimers.setDurationSeconds(production, 2, 2);
                require(ProgressTimers.formattedTime(production, 0).equals("01:00:00")
                                && ProgressTimers.formattedTime(production, 1).equals("00:02:00")
                                && ProgressTimers.formattedTime(production, 2).equals("00:00:02"),
                        "The three HUD timers did not retain their independent durations");
            });
            context.waitTicks(2);
            Path distinct = screenshot(context, "progressbar-14-production-distinct-timers");
            assertTimerPixels(themed, distinct, renderedBounds.get(), 0, false);
            assertTimerPixels(themed, distinct, renderedBounds.get(), 1, true);
            assertTimerPixels(themed, distinct, renderedBounds.get(), 2, true);
            context.runOnClient(client -> ProgressTimers.start(production, 0));
            waitRealtime(context, 2100);
            Path running = screenshot(context, "progressbar-15-production-running-overworld-timer");
            assertTimerPixels(distinct, running, renderedBounds.get(), 0, true);
            assertTimerPixels(distinct, running, renderedBounds.get(), 1, false);
            assertTimerPixels(distinct, running, renderedBounds.get(), 2, false);
            context.runOnClient(client -> {
                ProgressTimers.pause(production, 0);
                ProgressTimers.start(production, 2);
            });
            long paused = context.computeOnClient(client -> ProgressTimers.remainingMillis(production, 0));
            waitRealtime(context, 2100);
            context.runOnClient(client -> require(ProgressTimers.isExpired(production, 2)
                            && !ProgressTimers.isRunning(production, 2)
                            && ProgressTimers.formattedTime(production, 2).equals("00:00:00")
                            && ProgressTimers.remainingMillis(production, 0) == paused
                            && ProgressTimers.remainingMillis(production, 1) == 120_000,
                    "An expired End timer affected another timer or failed to stop at zero"));
            Path expired = screenshot(context, "progressbar-16-production-expired-end-timer");
            assertTimerPixels(distinct, expired, renderedBounds.get(), 2, true);
            assertTimerPixels(distinct, expired, renderedBounds.get(), 1, false);
        } finally {
            context.runOnClient(client -> {
                production.progressBar.enabled = enabled;
                production.progressBar.shinyOutline = shiny;
                production.progressBar.completedStages = stages;
                production.progressBar.basesFound = basesFound;
                production.progressBar.x = x;
                production.progressBar.y = y;
                production.progressBar.scale = scale;
                production.showHud = showHud;
                for (int index = 0; index < 3; index++) {
                    ProgressTimers.pause(production, index);
                    production.progressBar.timers[index].durationSeconds = timerDurations[index];
                    production.progressBar.timers[index].remainingMillis = timerRemaining[index];
                    if (timerRunning[index]) ProgressTimers.start(production, index);
                }
                client.options.hideGui = false;
            });
        }
    }

    private static void testTimerInput(ClientGameTestContext context, ClientConfig config, ConfigStore store) {
        for (int index = 0; index < 3; index++) {
            require(config.progressBar.timers[index].durationSeconds == 3600
                            && ProgressTimers.remainingMillis(config, index) == 3_600_000
                            && !ProgressTimers.isRunning(config, index),
                    "A new stage timer did not default to one paused hour");
        }
        setTimerDuration(context, 0, "1:00:00");
        setTimerDuration(context, 1, "00:02:00");
        setTimerDuration(context, 2, "00:00:30");
        var saved = store.load();
        require(saved.progressBar.timers[0].durationSeconds == 3600
                        && saved.progressBar.timers[1].durationSeconds == 120
                        && saved.progressBar.timers[2].durationSeconds == 30,
                "Typing HH:MM:SS durations did not save three independent stage timers");
        GuiTestInput.click(context, "m7.timerDuration0");
        GuiTestInput.type(context, "1:99:99");
        context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
        GuiTestInput.settle(context);
        require(config.progressBar.timers[0].durationSeconds == 3600
                        && ProgressTimers.remainingMillis(config, 0) == 3_600_000,
                "An invalid minutes/seconds duration replaced the existing timer");
        context.getInput().holdKey(GLFW.GLFW_KEY_ESCAPE);
        try {
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(client.screen instanceof DonutScreen, "Canceling an invalid duration closed the GUI");
                client.screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
            });
            context.waitTicks(5);
            require(context.computeOnClient(client -> client.screen instanceof DonutScreen),
                    "A held Escape repeat closed the GUI after canceling a timer duration");
        } finally { context.getInput().releaseKey(GLFW.GLFW_KEY_ESCAPE); }
        GuiTestInput.settle(context);
        GuiTestInput.click(context, "m7.timerStart0");
        long started = context.computeOnClient(client -> ProgressTimers.remainingMillis(config, 0));
        waitRealtime(context, 2100);
        context.runOnClient(client -> require(ProgressTimers.isRunning(config, 0)
                        && started - ProgressTimers.remainingMillis(config, 0) >= 1900
                        && ProgressTimers.remainingMillis(config, 1) == 120_000
                        && ProgressTimers.remainingMillis(config, 2) == 30_000,
                "The Overworld timer did not count real time independently of the paused timers"));
        GuiTestInput.click(context, "m7.timerStart0");
        long paused = context.computeOnClient(client -> ProgressTimers.remainingMillis(config, 0));
        var loaded = store.load();
        require(!ProgressTimers.isRunning(config, 0) && !ProgressTimers.isRunning(loaded, 0)
                        && ProgressTimers.remainingMillis(loaded, 0) == paused,
                "Pausing did not persist its remaining time as a paused timer on reload");
        waitRealtime(context, 1100);
        require(ProgressTimers.remainingMillis(config, 0) == paused && ProgressTimers.remainingMillis(loaded, 0) == paused,
                "A paused or reloaded timer continued to count down");
        GuiTestInput.click(context, "m7.timerStart1");
        waitRealtime(context, 1100);
        context.runOnClient(client -> require(ProgressTimers.isRunning(config, 1)
                        && ProgressTimers.remainingMillis(config, 1) < 119_000
                        && ProgressTimers.remainingMillis(config, 0) == paused
                        && ProgressTimers.remainingMillis(config, 2) == 30_000,
                "Starting the Nether timer affected the other two timers"));
        GuiTestInput.click(context, "m7.timerReset1");
        GuiTestInput.click(context, "m7.timerReset0");
        require(!ProgressTimers.isRunning(config, 1) && ProgressTimers.remainingMillis(config, 1) == 120_000
                        && ProgressTimers.remainingMillis(config, 0) == 3_600_000,
                "Reset did not stop the timer and restore its own configured duration");
        GuiTestInput.click(context, "m7.timerStart2");
        waitRealtime(context, 1100);
        context.runOnClient(client -> require(ProgressTimers.remainingMillis(config, 2) < 29_000
                        && ProgressTimers.remainingMillis(config, 0) == 3_600_000
                        && ProgressTimers.remainingMillis(config, 1) == 120_000,
                "The End countdown was not independent of the Overworld and Nether timers"));
        GuiTestInput.click(context, "m7.timerStart2");
        GuiTestInput.click(context, "m7.timerReset2");
        require(ProgressTimers.remainingMillis(config, 2) == 30_000 && !ProgressTimers.isRunning(config, 2),
                "The End timer did not pause and reset through the scrolled module controls");
        GuiTestInput.assertContentClipped(context);
    }

    private static void setTimerDuration(ClientGameTestContext context, int index, String duration) {
        GuiTestInput.click(context, "m7.timerDuration" + index);
        GuiTestInput.type(context, duration);
        context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
        GuiTestInput.settle(context);
    }

    private static void waitRealtime(ClientGameTestContext context, long milliseconds) {
        long start = System.nanoTime();
        context.waitFor(client -> System.nanoTime() - start >= milliseconds * 1_000_000L);
    }

    private static void assertTimerPixels(Path before, Path after, ProgressBarHud.Bounds bounds, int index, boolean changed) {
        float scale = bounds.scale();
        var timer = new ProgressBarHud.Bounds(bounds.x() + (ProgressBarHud.TIMER_X + index * ProgressBarHud.TIMER_SPACING) * scale,
                bounds.y() + ProgressBarHud.TIMER_Y * scale, ProgressBarHud.TIMER_WIDTH * scale,
                ProgressBarHud.TIMER_HEIGHT * scale, scale);
        int difference = changedPixels(before, after, timer);
        require(changed ? difference > 8 * scale * scale : difference == 0,
                "The rendered stage " + (index + 1) + " timer " + (changed ? "did not update" : "changed with another timer"));
    }

    private static void testBasesFoundInput(ClientGameTestContext context, ClientConfig config,
                                            ConfigStore store, AtomicInteger saves) {
        int before = saves.get();
        setBasesFound(context, "27");
        require(config.progressBar.basesFound == 27 && store.load().progressBar.basesFound == 27 && saves.get() > before,
                "Typing the manual Bases Found count did not update and save it");
        GuiTestInput.click(context, "m7.basesFoundPlus");
        require(config.progressBar.basesFound == 28 && store.load().progressBar.basesFound == 28,
                "The Bases Found plus control did not increment and save the count");
        GuiTestInput.click(context, "m7.basesFoundMinus");
        require(config.progressBar.basesFound == 27 && store.load().progressBar.basesFound == 27,
                "The Bases Found minus control did not decrement and save the count");
        GuiTestInput.click(context, "m7.basesFound");
        GuiTestInput.type(context, "999");
        context.getInput().holdKey(GLFW.GLFW_KEY_ESCAPE);
        try {
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(client.screen instanceof DonutScreen, "Canceling the counter draft closed the module GUI");
                require(client.screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0)),
                        "The GUI did not consume a held Escape repeat after canceling the counter draft");
            });
            context.waitTicks(5);
            require(context.computeOnClient(client -> client.screen instanceof DonutScreen),
                    "A held Escape repeat closed the GUI after canceling the counter draft");
            require(config.progressBar.basesFound == 27 && store.load().progressBar.basesFound == 27,
                    "Canceling the counter draft changed its saved value");
        } finally {
            context.getInput().releaseKey(GLFW.GLFW_KEY_ESCAPE);
        }
        GuiTestInput.settle(context);
        setBasesFound(context, "999999999999");
        require(config.progressBar.basesFound == 999_999 && store.load().progressBar.basesFound == 999_999,
                "An oversized Bases Found entry did not clamp to its maximum");
        GuiTestInput.click(context, "m7.basesFoundPlus");
        require(config.progressBar.basesFound == 999_999, "Incrementing the maximum counter overflowed its limit");
        GuiTestInput.click(context, "m7.basesFound");
        context.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
        context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
        GuiTestInput.settle(context);
        require(config.progressBar.basesFound == 0 && store.load().progressBar.basesFound == 0,
                "An empty Bases Found draft did not commit and save zero");
        GuiTestInput.click(context, "m7.basesFoundMinus");
        require(config.progressBar.basesFound == 0, "Decrementing a zero counter produced a negative count");
        setBasesFound(context, "27");
    }

    private static void setBasesFound(ClientGameTestContext context, String value) {
        GuiTestInput.click(context, "m7.basesFound");
        GuiTestInput.type(context, value);
        context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
        GuiTestInput.settle(context);
    }

    private static void assertCounterPixels(Path before, Path after, ProgressBarHud.Bounds bounds) {
        float scale = bounds.scale();
        var footer = new ProgressBarHud.Bounds(bounds.x(), bounds.y() + ProgressBarHud.COUNTER_Y * scale,
                bounds.width(), bounds.height() - ProgressBarHud.COUNTER_Y * scale, scale);
        require(changedPixels(before, after, footer) > 35 * scale * scale,
                "Changing Bases Found did not change the rendered footer count");
        for (int stage = 0; stage < 3; stage++) {
            var icon = new ProgressBarHud.Bounds(bounds.x() + (26 + 66 * stage) * scale,
                    bounds.y() + 20 * scale, 32 * scale, 36 * scale, scale);
            require(changedPixels(before, after, icon) == 0,
                    "Changing the counter altered a dimension icon or milestone");
        }
    }

    private static void assertSizeRangeAndMovement(ClientConfig config) {
        double scale = config.progressBar.scale, x = config.progressBar.x, y = config.progressBar.y;
        try {
            config.progressBar.scale = .5;
            var small = ProgressBarHud.layout(1280, 800, config);
            config.progressBar.scale = 2;
            var large = ProgressBarHud.layout(1280, 800, config);
            require(Math.abs(large.width() - small.width() * 4) < .01
                            && Math.abs(large.height() - small.height() * 4) < .01,
                    "The minimum and maximum overlay sizes did not shrink and grow its rendered bounds");
            assertLayout(config, 160, 60);
            ProgressBarHud.moveTo(config, 320, 180, 10_000, 10_000);
            assertLayout(config, 320, 180);
            var edge = ProgressBarHud.layout(320, 180, config);
            require(Math.abs(edge.x() + edge.width() - 316) < .01
                            && Math.abs(edge.y() + edge.height() - 176) < .01,
                    "Moving a large overlay did not clamp it to the bottom-right screen margin");
            ProgressBarHud.moveTo(config, 320, 180, -10_000, -10_000);
            var origin = ProgressBarHud.layout(320, 180, config);
            require(Math.abs(origin.x() - 4) < .01 && Math.abs(origin.y() - 4) < .01,
                    "Moving a large overlay did not clamp it to the top-left screen margin");
        } finally {
            config.progressBar.scale = scale;
            config.progressBar.x = x;
            config.progressBar.y = y;
        }
    }

    /** Inspect both red diagonals above each icon, rather than accepting its naturally red texture. */
    private static void assertCrosses(Path screenshot, ProgressBarHud.Bounds bounds, int completed) {
        try {
            BufferedImage image = ImageIO.read(screenshot.toFile());
            require(image != null, "Cannot decode the milestone X screenshot");
            double scale = bounds.scale(), area = scale * scale;
            for (int stage = 0; stage < 3; stage++) {
                double centerX = bounds.x() + (42 + 66 * stage) * scale;
                double centerY = bounds.y() + 38 * scale;
                int red = 0;
                int[] arms = new int[4];
                for (int y = Math.max(0, (int) (centerY - 16 * scale));
                        y < Math.min(image.getHeight(), centerY + 16 * scale); y++) {
                    for (int x = Math.max(0, (int) (centerX - 16 * scale));
                            x < Math.min(image.getWidth(), centerX + 16 * scale); x++) {
                        int pixel = image.getRGB(x, y);
                        int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
                        if (r <= 180 || g >= 120 || b >= 130 || r <= g * 1.5 || r <= b * 1.5) continue;
                        red++;
                        double dx = (x + .5 - centerX) / scale, dy = (y + .5 - centerY) / scale;
                        if (Math.abs(dx) >= 3 && Math.abs(dy) >= 3
                                && Math.abs(Math.abs(dx) - Math.abs(dy)) <= 3.5) {
                            arms[(dy >= 0 ? 2 : 0) + (dx >= 0 ? 1 : 0)]++;
                        }
                    }
                }
                if (stage < completed) {
                    require(red >= 90 * area, "Marked milestone " + (stage + 1) + " has no visible red X above its icon");
                    for (int arm : arms) require(arm >= 10 * area,
                            "Marked milestone " + (stage + 1) + " is missing a red diagonal X arm");
                } else {
                    require(red <= 3 * area, "Pending milestone " + (stage + 1) + " retained a red X");
                }
            }
        } catch (IOException error) {
            throw new AssertionError("Cannot inspect the rendered milestone X marks", error);
        }
    }

    private static void assertLayout(ClientConfig config, int width, int height) {
        var bounds = ProgressBarHud.layout(width, height, config);
        require(bounds.x() >= 0 && bounds.y() >= 0 && bounds.width() > 0 && bounds.height() > 0
                        && bounds.x() + bounds.width() <= width + .01
                        && bounds.y() + bounds.height() <= height + .01,
                "The Progress Bar can leave a small or resized GUI viewport");
    }

    private static ClientConfig config(DonutScreen screen) {
        try {
            Field field = DonutScreen.class.getDeclaredField("config");
            field.setAccessible(true);
            return (ClientConfig) field.get(screen);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot inspect the production Progress Bar configuration", error);
        }
    }

    private static MouseButtonEvent mouse(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
    }

    private static Path screenshot(ClientGameTestContext context, String name) {
        return context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    private static int changedPixels(Path first, Path second, ProgressBarHud.Bounds bounds) {
        try {
            BufferedImage before = ImageIO.read(first.toFile()), after = ImageIO.read(second.toFile());
            require(before != null && after != null, "Cannot decode streaming HUD screenshots");
            int changed = 0;
            for (int y = Math.max(0, (int) bounds.y()); y < Math.min(before.getHeight(), bounds.y() + bounds.height()); y++) {
                for (int x = Math.max(0, (int) bounds.x()); x < Math.min(before.getWidth(), bounds.x() + bounds.width()); x++) {
                    int a = before.getRGB(x, y), b = after.getRGB(x, y);
                    int distance = Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                            + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) + Math.abs((a & 255) - (b & 255));
                    if (distance > 30) changed++;
                }
            }
            return changed;
        } catch (IOException error) {
            throw new AssertionError("Cannot inspect rendered streaming HUD pixels", error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
