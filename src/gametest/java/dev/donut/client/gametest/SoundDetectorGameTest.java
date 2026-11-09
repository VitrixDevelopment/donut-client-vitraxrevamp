package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.SoundDetectorSettings;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.gui.SoundAlertEditorScreen;
import dev.donut.client.sound.*;
import java.lang.reflect.Modifier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Real server block/world events cross the production connection and production mixins. */
public final class SoundDetectorGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var controller = SoundDetectorController.get();
        var settings = controller.settings();
        var original = settings.copyWithoutProfiles();
        var config = new ClientConfig(); config.soundDetector = settings;
        int oldScale = context.computeOnClient(mc -> mc.options.guiScale().get());
        context.runOnClient(mc -> {
            settings.applyPreset("Base activity"); settings.enabled = true;
            settings.warmupSeconds = 0; settings.suppressSelf = false; settings.suppressProbableSelf = false;
            settings.showInMenus = true; settings.chime = false; settings.worldMarkers = true;
            mc.options.guiScale().set(2); mc.resizeDisplay(); controller.disconnect();
        });
        context.getInput().resizeWindow(1280,800);
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("fill -5 64 -5 55 64 5 minecraft:stone");
            server.runCommand("setblock 40 65 0 minecraft:chest");
            server.runCommand("setblock 44 65 0 minecraft:piston[facing=east]");
            server.runCommand("setblock 48 65 0 minecraft:note_block");
            server.runCommand("tp @a 0.5 65 0.5 0 0");
            context.waitTicks(15); world.getClientWorld().waitForChunksRender();
            require(SoundCatalog.coverage().size() == BuiltInRegistries.BLOCK.size(), "Block catalog is incomplete");
            require(SoundCatalog.candidates("minecraft:block.stone.break").size() > 1, "Shared material sound guessed one block");
            server.runOnServer(mc -> ((ChestBlockEntity)mc.overworld().getBlockEntity(new BlockPos(40,65,0)))
                    .startOpen(mc.getPlayerList().getPlayers().getFirst()));
            context.waitFor(mc -> controller.history().stream().anyMatch(i -> i.actionKey().equals("container.open")));
            context.runOnClient(mc -> {
                var container = controller.history().stream().filter(i -> i.actionKey().equals("container.open")).findFirst().orElseThrow();
                require(container.actor() == SoundObservation.Actor.UNKNOWN, "Unbound chest event invented a player");
                require(container.evidence().stream().anyMatch(e -> e.route() == SoundObservation.Route.BLOCK_EVENT), "Chest at 40 blocks missed event route");
            });
            server.runCommand("setblock 44 65 -1 minecraft:redstone_block");
            server.runCommand("setblock 48 65 -1 minecraft:redstone_block");
            context.waitFor(mc -> controller.history().stream().anyMatch(i -> i.actionKey().equals("piston.extend")));
            context.waitFor(mc -> controller.history().stream().anyMatch(i -> i.actionKey().equals("note.play")));
            context.waitTicks(3);
            context.takeScreenshot(TestScreenshotOptions.of("sound-detector-01-alerts").withSize(1280,800));
            // Explicitly mute game audio while a normal S2C sound is still observed.
            float master = context.computeOnClient(mc -> mc.options.getSoundSourceVolume(SoundSource.MASTER));
            context.runOnClient(mc -> mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0));
            server.runOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().connection.send(new ClientboundSoundPacket(
                    net.minecraft.core.Holder.direct(SoundEvents.BARREL_OPEN), SoundSource.BLOCKS, 4,65,0, .5f,1,123)));
            context.waitFor(mc -> controller.history().stream().anyMatch(i -> i.soundId().equals("minecraft:block.barrel.open")));
            context.runOnClient(mc -> mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set((double)master));
            int beforePreview = context.computeOnClient(mc -> controller.history().size());
            context.runOnClient(mc -> mc.setScreen(new SoundAlertEditorScreen(null, config, () -> {}, false)));
            context.waitTicks(3);
            context.takeScreenshot(TestScreenshotOptions.of("sound-detector-02-editor").withSize(1280,800));
            context.runOnClient(mc -> mc.setScreen(null));
            require(context.computeOnClient(mc -> controller.history().size()) == beforePreview, "Preview generated activity");
            GuiTestInput.openAnimated(context, () -> new DonutScreen(config, () -> {}));
            GuiTestInput.click(context,"search"); GuiTestInput.type(context,"sound detector");
            require(GuiTestInput.visible(context,"module12"), "Sound Detector missing from module search");
            GuiTestInput.click(context,"module12");
            context.takeScreenshot(TestScreenshotOptions.of("sound-detector-03-module").withSize(1280,800));
            GuiTestInput.closeAnimated(context);
            context.runOnClient(mc -> { controller.mute(60); require(controller.alerts().isEmpty(), "Mute left a visible alert"); settings.enabled = false; controller.tick(mc); require(controller.history().isEmpty(), "Disable retained old incidents"); });
        } finally {
            context.runOnClient(mc -> {
                controller.disconnect();
                try { for (var field : SoundDetectorSettings.class.getFields()) if (!Modifier.isStatic(field.getModifiers()) && !field.getName().equals("profiles")) field.set(settings,field.get(original)); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                mc.options.guiScale().set(oldScale); mc.resizeDisplay();
            });
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
