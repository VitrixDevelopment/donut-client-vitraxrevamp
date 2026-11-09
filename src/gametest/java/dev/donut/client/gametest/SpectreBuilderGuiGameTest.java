package dev.donut.client.gametest;

import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.autobuild.PlacementTransform;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.gui.AutoBuilderScreen;
import dev.donut.client.spectrebuilder.BuilderPlacement;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import org.lwjgl.glfw.GLFW;

/** Real native controls for Sponge imports, engine choice, packing, plan save/restore and placement mode. */
public final class SpectreBuilderGuiGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var builder=AutoBuilderController.get();var config=new ClientConfig();
        int scale=context.computeOnClient(mc->mc.options.guiScale().get());
        Path folder=context.computeOnClient(mc->mc.gameDirectory.toPath().resolve("schematics/donut"));
        Path file=folder.resolve("Spectre Sponge UI.schem");
        context.getInput().resizeWindow(1280,800);
        try {
            Files.createDirectories(folder);NbtIo.writeCompressed(fixture(),file);
            context.runOnClient(mc->{builder.setSchematic(null);mc.options.guiScale().set(1);mc.resizeDisplay();});
            try(var world=context.worldBuilder().create()) {
                var server=world.getServer();server.runCommand("gamemode creative @a");server.runCommand("fill -8 64 -8 24 64 24 minecraft:smooth_stone");server.runCommand("tp @a 1.5 65 7.5 180 30");
                context.waitTicks(8);world.getClientWorld().waitForChunksRender();
                context.setScreen(()->new AutoBuilderScreen(null,config,()->{}));settle(context);
                int index=context.computeOnClient(mc->((List<?>)field(mc.screen,"files")).indexOf(file));require(index>=0,"Sponge file missing from native library");
                click(context,"file"+index);context.waitFor(mc->!(Boolean)field(mc.screen,"loading"));settle(context);
                context.runOnClient(mc->{require(builder.schematic()!=null&&builder.schematic().blocks().size()==2,"Sponge import failed through native UI");require(builder.schematic().blocks().get(1).state().toString().contains("facing=east"),"Sponge block properties lost");});
                click(context,"executor");require(!config.autoBuilder.spectreCreative,"Existing executor cannot be selected");click(context,"executor");require(config.autoBuilder.spectreCreative,"Spectre executor cannot be selected");
                var transform=new PlacementTransform(new BlockPos(9,65,9),3,true);
                context.runOnClient(mc->{builder.setTransform(transform);invoke(mc.screen,"syncCoordinates");});context.waitTick();
                click(context,"savePlan");context.waitFor(mc->!(Boolean)field(mc.screen,"planBusy"));
                context.runOnClient(mc->require(((String)field(mc.screen,"message")).startsWith("Plan saved"),"Plan not saved: "+field(mc.screen,"message")));
                context.runOnClient(mc->{builder.setTransform(new PlacementTransform(BlockPos.ZERO,0,false));invoke(mc.screen,"syncCoordinates");});context.waitTick();
                click(context,"restorePlan");context.waitFor(mc->!(Boolean)field(mc.screen,"planBusy"));
                context.runOnClient(mc->{require(builder.transform().equals(transform),"Saved position, rotation or mirror not restored");require(!builder.isRunning(),"Restoring a plan started construction");});
                click(context,"libraryMode");context.waitFor(mc->!((List<?>)field(mc.screen,"plans")).isEmpty());context.waitTick();
                click(context,"plan0");context.waitFor(mc->!(Boolean)field(mc.screen,"planBusy"));
                context.runOnClient(mc->require(builder.transform().equals(transform)&&!builder.isRunning(),"Selecting a saved plan failed or started building"));
                click(context,"libraryMode");
                click(context,"materials");settle(context);click(context,"packing");context.waitTicks(15);
                context.runOnClient(mc->require(((String)field(mc.screen,"details")).contains("SHULKER PACKING"),"Native packing report is empty"));
                context.takeScreenshot(TestScreenshotOptions.of("spectre-builder-02-packing").withSize(1280,800));
                click(context,"doneDetails");context.waitTicks(15);settle(context);
                click(context,"place");context.waitForScreen(null);context.waitTicks(3);require(BuilderPlacement.placing(),"Place in world did not start placement controls");
                var before=context.computeOnClient(mc->builder.transform());
                context.getInput().pressKey(GLFW.GLFW_KEY_R);context.waitTicks(3);
                require(context.computeOnClient(mc->Math.floorMod(builder.transform().quarterTurns()-before.quarterTurns(),4))==1,"R did not rotate the positioned hologram");
                context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);context.waitTicks(3);require(!BuilderPlacement.placing(),"Enter did not anchor the hologram");
                require(!builder.isRunning(),"Positioning the hologram started building");
                context.setScreen(()->new AutoBuilderScreen(null,config,()->{}));settle(context);
                Files.writeString(file,"changed source");click(context,"restorePlan");context.waitFor(mc->!(Boolean)field(mc.screen,"planBusy"));
                context.runOnClient(mc->require(((String)field(mc.screen,"message")).contains("changed"),"Changed schematic was accepted when restoring a plan"));
            }
        } catch(Exception e){throw new AssertionError(e);}
        finally {
            context.setScreen(()->null);context.runOnClient(mc->{BuilderPlacement.stop();builder.disconnect();builder.setSchematic(null);mc.options.guiScale().set(scale);mc.resizeDisplay();});
            try{Files.deleteIfExists(file);}catch(Exception e){throw new AssertionError(e);}
        }
    }
    private static CompoundTag fixture(){
        var root=new CompoundTag();root.putInt("Version",3);root.putInt("DataVersion",SharedConstants.getCurrentVersion().dataVersion().version());
        root.putShort("Width",(short)2);root.putShort("Height",(short)1);root.putShort("Length",(short)1);
        var blocks=new CompoundTag();var palette=new CompoundTag();palette.putInt("minecraft:stone",0);palette.putInt("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",1);
        blocks.put("Palette",palette);blocks.putByteArray("Data",new byte[]{0,1});root.put("Blocks",blocks);return root;
    }
    private static void settle(ClientGameTestContext context){context.waitTick();context.waitFor(mc->mc.screen instanceof AutoBuilderScreen && number(mc.screen,"progress")==1 && !(Boolean)field(mc.screen,"refreshing") && !(Boolean)field(mc.screen,"modalClosing") && (!(Boolean)field(mc.screen,"materialsOpen")||number(mc.screen,"modalProgress")==1));}
    private static void click(ClientGameTestContext context,String id){
        context.runOnClient(mc->{
            Screen screen=mc.screen;Object target=((List<?>)field(screen,"hits")).stream().filter(hit->id.equals(field(hit,"id"))).findFirst().orElseThrow(()->new AssertionError("Missing control "+id));
            double x=number(target,"x")+number(target,"w")/2,y=number(target,"y")+number(target,"h")/2;
            var event=new MouseButtonEvent(number(screen,"originX")+x*number(screen,"scale"),number(screen,"originY")+y*number(screen,"scale"),new MouseButtonInfo(0,0));
            require(screen.mouseClicked(event,false),"Click not consumed: "+id);screen.mouseReleased(event);
        });context.waitTick();
    }
    private static void invoke(Object object,String name){try{var m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(object);}catch(Exception e){throw new AssertionError(e);}}
    private static Object field(Object object,String name){try{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(Exception e){throw new AssertionError(e);}}
    private static double number(Object object,String name){return ((Number)field(object,name)).doubleValue();}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
