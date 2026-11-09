package dev.donut.client.gametest;

import dev.donut.client.autobuild.*;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.gui.AutoBuilderScreen;
import dev.donut.client.spectrebuilder.BuilderBridge;
import dev.donut.client.spectrebuilder.CreativeBuilder;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Source executor: real walking, rotation, interactions and exact integrated-server confirmation. */
public final class SpectreBuilderGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var builder=AutoBuilderController.get();var creative=CreativeBuilder.INSTANCE;
        int originalScale=context.computeOnClient(mc->mc.options.guiScale().get());
        context.getInput().resizeWindow(1280,800);
        try(var world=context.worldBuilder().create()) {
            var server=world.getServer();
            server.runCommand("gamemode creative @a");server.runCommand("time set noon");
            server.runCommand("fill -16 64 -8 31 64 24 minecraft:smooth_stone");
            server.runCommand("tp @a 0.5 65 5.5 180 15");
            context.waitTicks(10);world.getClientWorld().waitForChunksRender();context.waitFor(mc->mc.player.onGround());
            var states=List.of(block(0,Blocks.STONE.defaultBlockState()),
                    block(3,Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST)),
                    block(6,Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.SOUTH).setValue(BlockStateProperties.DELAY,3)));
            context.runOnClient(mc->{
                builder.setSchematic(new Schematic("Spectre Creative fixture",states,new BlockPos(7,1,1),1,List.of()));
                builder.setTransform(new PlacementTransform(new BlockPos(0,65,0),0,false));
                builder.getMaterials(mc);builder.startSpectre();
                require(builder.isRunning(),"Source executor did not start: "+builder.reason());
                builder.pause();require(builder.status()==AutoBuilderController.Status.PAUSED,"Pause did not reach source executor");
            });
            context.waitTicks(5);
            context.runOnClient(mc->{require(builder.confirmedBlocks()==0,"Paused build progressed");builder.resume();});
            await(context,builder,1500);
            context.runOnClient(mc->require(builder.status()==AutoBuilderController.Status.COMPLETED,"Source build failed: "+builder.reason()+" / "+creative.diagnostic()));
            server.runOnServer(mc->{for(var block:states)require(mc.overworld().getBlockState(new BlockPos(0,65,0).offset(block.pos())).equals(block.state()),"Source executor produced the wrong block state");});
            // The source supplies planner and native materials modal share exact registered item recipes.
            context.runOnClient(mc->{
                var plan=BuilderBridge.materials(builder.schematic());require(plan.unresolved().isEmpty()&&plan.rows().size()==3,"Source material resolver failed");
                require(BuilderBridge.packingSummary(builder.schematic()).contains("SHULKER PACKING"),"Missing packing report");
                mc.options.guiScale().set(1);mc.resizeDisplay();mc.setScreen(new AutoBuilderScreen(null,new ClientConfig(),()->{}));
            });
            context.waitTicks(20);context.takeScreenshot(TestScreenshotOptions.of("spectre-builder-01-library").withSize(1280,800));
            context.setScreen(()->null);
            // A water source uses the vanilla bucket interaction, after ordinary dry construction.
            server.runCommand("fill 9 65 -1 11 65 1 minecraft:stone");server.runCommand("setblock 10 65 0 minecraft:air");
            server.runCommand("give @a minecraft:water_bucket");server.runCommand("tp @a 10.5 65 4.5 180 35");context.waitTicks(5);
            context.runOnClient(mc->{
                builder.setSchematic(new Schematic("water source",List.of(block(0,Blocks.WATER.defaultBlockState())),new BlockPos(1,1,1),1,List.of()));
                builder.setTransform(new PlacementTransform(new BlockPos(10,65,0),0,false));builder.startSpectre();
                require(builder.isRunning(),"Bucket build failed to start: "+builder.reason());
            });
            await(context,builder,700);
            context.runOnClient(mc->require(builder.status()==AutoBuilderController.Status.COMPLETED,"Source bucket build failed: "+builder.reason()));
            server.runOnServer(mc->require(mc.overworld().getBlockState(new BlockPos(10,65,0)).equals(Blocks.WATER.defaultBlockState()),"Bucket source not confirmed on server"));
            server.runCommand("give @a minecraft:oak_slab");server.runCommand("tp @a 14.5 65 4.5 180 35");context.waitTicks(5);
            var wet=Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true);
            context.runOnClient(mc->{
                builder.setSchematic(new Schematic("waterlogged slab",List.of(block(0,wet)),new BlockPos(1,1,1),1,List.of()));
                builder.setTransform(new PlacementTransform(new BlockPos(14,65,0),0,false));builder.startSpectre();
                require(builder.isRunning(),"Waterlogged build failed to start: "+builder.reason());
            });
            await(context,builder,800);
            context.runOnClient(mc->require(builder.status()==AutoBuilderController.Status.COMPLETED,"Waterlogged build failed: "+builder.reason()));
            server.runOnServer(mc->require(mc.overworld().getBlockState(new BlockPos(14,65,0)).equals(wet),"Waterlogged state not confirmed on server"));
            context.runOnClient(mc->{builder.startSpectre();builder.cancel();require(!creative.running(),"Cancel left source executor active");});
            server.runCommand("gamemode survival @a");context.waitFor(mc->!mc.player.getAbilities().instabuild);
            context.runOnClient(mc->{builder.startSpectre();require(!builder.isRunning()&&builder.reason().contains("creative single-player"),"Source restrictions were lost");});
        } finally {
            context.runOnClient(mc->{builder.disconnect();builder.setSchematic(null);mc.options.guiScale().set(originalScale);mc.resizeDisplay();});
        }
    }
    private static Schematic.Block block(int x,net.minecraft.world.level.block.state.BlockState state){return new Schematic.Block(new BlockPos(x,0,0),state,null);}
    private static void await(ClientGameTestContext context,AutoBuilderController builder,int ticks){for(int i=0;i<ticks && context.computeOnClient(mc->builder.isRunning());i++)context.waitTick();}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
