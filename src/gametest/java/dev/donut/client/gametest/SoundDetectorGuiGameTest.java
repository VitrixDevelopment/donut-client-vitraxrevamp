package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.gui.SoundAlertEditorScreen;
import dev.donut.client.gui.SoundAlertHud;
import dev.donut.client.gui.SoundRuleFormScreen;
import dev.donut.client.gui.SoundRulesScreen;
import dev.donut.client.gui.SoundSettingsJsonScreen;
import dev.donut.client.gui.SoundSettingsScreen;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/** Production native widgets, persisted preferences, advanced import validation, anchors and responsive previews. */
public final class SoundDetectorGuiGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        ClientConfig config=new ClientConfig();
        Path fixture=FabricLoader.getInstance().getConfigDir().resolve("sound-ui-gametest.json");
        ConfigStore store=new ConfigStore(fixture);
        int oldScale=context.computeOnClient(mc->mc.options.guiScale().get());
        context.getInput().resizeWindow(1280,800);
        context.runOnClient(mc->{mc.options.guiScale().set(1);mc.resizeDisplay();});
        try(var world=context.worldBuilder().create()){
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("time set noon");
            context.setScreen(()->new SoundSettingsScreen(null,config,()->store.save(config)));
            context.waitForScreen(SoundSettingsScreen.class);
            edit(context,"Search settings","Width");context.waitTicks(3);
            edit(context,"Width","420");
            require(store.load().soundDetector.width==420,"Native width control did not persist");
            edit(context,"Width","NaN");
            require(store.load().soundDetector.width==420,"Invalid native value damaged saved preferences");
            edit(context,"Width","420");edit(context,"Search settings","");context.waitTicks(3);
            context.takeScreenshot(TestScreenshotOptions.of("sound-ui-01-appearance").withSize(1280,800));
            click(context,"Rules / areas");context.waitForScreen(SoundRulesScreen.class);
            click(context,"Add rule");context.waitForScreen(SoundRuleFormScreen.class);
            edit(context,"Name","Nearby container test");
            // Name is the first guided field. Other fields remain at their bounded defaults.
            click(context,"Done");context.waitForScreen(SoundRulesScreen.class);
            require(store.load().soundDetector.rules.size()==1&&store.load().soundDetector.rules.getFirst().name.equals("Nearby container test"),"Guided rule did not save");
            context.takeScreenshot(TestScreenshotOptions.of("sound-ui-02-rules").withSize(1280,800));
            click(context,"Enabled");require(!store.load().soundDetector.rules.getFirst().enabled,"Rule toggle did not persist");
            click(context,"Show expected areas");click(context,"Add current area");context.waitForScreen(SoundRuleFormScreen.class);
            edit(context,"Name","Quiet farm");click(context,"Done");
            require(store.load().soundDetector.zones.size()==1&&store.load().soundDetector.zones.getFirst().name.equals("Quiet farm"),"Expected area did not persist");
            click(context,"Advanced JSON");context.waitForScreen(SoundSettingsJsonScreen.class);
            context.runOnClient(mc->{var box=mc.screen.children().stream().filter(c->c instanceof MultiLineEditBox).map(c->(MultiLineEditBox)c).findFirst().orElseThrow();box.setValue("{\"width\":500,\"opacity\":0.8,\"titleTemplate\":\"Activity: {event}\"}");});
            click(context,"Apply");require(store.load().soundDetector.width==500&&store.load().soundDetector.titleTemplate.equals("Activity: {event}"),"Advanced settings did not apply");
            context.runOnClient(mc->{var box=mc.screen.children().stream().filter(c->c instanceof MultiLineEditBox).map(c->(MultiLineEditBox)c).findFirst().orElseThrow();box.setValue("{invalid}");});
            click(context,"Apply");require(store.load().soundDetector.width==500,"Invalid advanced data damaged preferences");
            context.runOnClient(mc->mc.setScreen(new SoundAlertEditorScreen(null,config,()->store.save(config),true)));
            context.waitForScreen(SoundAlertEditorScreen.class);
            click(context,"BR");require(config.soundDetector.x==1&&config.soundDetector.y==1,"Bottom-right anchor did not move preview");
            context.runOnClient(mc->{var b=SoundAlertHud.layout(mc.screen.width,mc.screen.height,config);var press=new MouseButtonEvent(b.x()+12,b.y()+12,new MouseButtonInfo(0,0));require(mc.screen.mouseClicked(press,false),"Preview did not begin drag");var drag=new MouseButtonEvent(100,100,new MouseButtonInfo(0,0));require(mc.screen.mouseDragged(drag,-20,-20),"Preview did not drag");mc.screen.mouseReleased(drag);});
            require(config.soundDetector.x<1&&config.soundDetector.y<1,"Drag did not change normalized position");
            context.runOnClient(mc->mc.screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_RIGHT,0,0)));
            context.takeScreenshot(TestScreenshotOptions.of("sound-ui-03-critical").withSize(1280,800));
            context.getInput().resizeWindow(960,640);
            context.runOnClient(mc->{mc.options.guiScale().set(2);mc.resizeDisplay();config.soundDetector.showCoordinates=true;config.soundDetector.showAge=true;config.soundDetector.showReason=true;});
            context.waitTicks(3);
            context.runOnClient(mc->{var b=SoundAlertHud.layout(mc.screen.width,mc.screen.height,config);require(b.x()>=6&&b.y()>=6&&b.x()+b.width()<=mc.screen.width-6&&b.y()+b.height()<=mc.screen.height-6,"Resized preview escaped viewport");});
            context.takeScreenshot(TestScreenshotOptions.of("sound-ui-04-small-scale2").withSize(960,640));
            click(context,"Done");context.waitForScreen(null);
        }finally{
            context.runOnClient(mc->{mc.setScreen(null);mc.options.guiScale().set(oldScale);mc.resizeDisplay();});
            try{Files.deleteIfExists(fixture);}catch(Exception e){throw new AssertionError(e);}
        }
    }
    private static void click(ClientGameTestContext context,String text){
        context.runOnClient(mc->{Screen screen=mc.screen;Button button=screen.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.getMessage().getString().equals(text)).findFirst().orElseThrow(()->new AssertionError("Missing button: "+text));var e=new MouseButtonEvent(button.getX()+button.getWidth()/2.0,button.getY()+button.getHeight()/2.0,new MouseButtonInfo(0,0));require(screen.mouseClicked(e,false),"Button input ignored: "+text);screen.mouseReleased(e);});
        context.waitTicks(2);
    }
    private static void edit(ClientGameTestContext context,String label,String value){
        context.runOnClient(mc->{EditBox box=mc.screen.children().stream().filter(c->c instanceof EditBox).map(c->(EditBox)c).filter(e->e.getMessage().getString().equals(label)).findFirst().orElseThrow(()->new AssertionError("Missing edit: "+label));box.setValue(value);});
        context.waitTick();
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
