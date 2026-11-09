// SPDX-License-Identifier: GPL-3.0-only
// Native adaptation of Spectre 26.2 SchematicPreview placement controls by 4ldenz.
package dev.donut.client.spectrebuilder;

import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.autobuild.PlacementTransform;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

/** Aim at terrain, R rotates, Page Up/Down raises/lowers, Enter anchors. No world writes. */
public final class BuilderPlacement {
    private static boolean placing,hasTarget;
    private static int height;
    private static ClientLevel world;
    private static final ArrayDeque<Integer> keys=new ArrayDeque<>();
    private BuilderPlacement() { }
    public static boolean placing(){return placing;}
    public static void begin(Minecraft mc){
        if(mc.level==null||mc.player==null||AutoBuilderController.get().schematic()==null)return;
        AutoBuilderController.get().cancel();world=mc.level;placing=true;hasTarget=false;height=0;keys.clear();
        AutoBuilderController.get().setPreviewVisible(true);mc.setScreen(null);
    }
    public static void stop(){placing=false;hasTarget=false;world=null;keys.clear();}
    /** Queue press events so a complete press/release between ticks is retained. */
    public static boolean key(Minecraft mc,int key,int action){
        if(!placing||mc.screen!=null)return false;
        if(key!=GLFW.GLFW_KEY_R&&key!=GLFW.GLFW_KEY_PAGE_UP&&key!=GLFW.GLFW_KEY_PAGE_DOWN&&key!=GLFW.GLFW_KEY_ENTER&&key!=GLFW.GLFW_KEY_ESCAPE)return false;
        if(action==GLFW.GLFW_RELEASE)return false; // Let vanilla clear a mapping that was held before placement began.
        if(action==GLFW.GLFW_PRESS){
            if(key==GLFW.GLFW_KEY_ESCAPE){stop();AutoBuilderController.get().setPreviewVisible(false);}
            else keys.add(key);
        }
        return true;
    }
    public static void tick(Minecraft mc){
        if(!placing)return;
        var builder=AutoBuilderController.get();
        if(mc.level!=world||mc.player==null||mc.screen!=null||builder.schematic()==null){stop();return;}
        var transform=builder.transform();int turns=transform.quarterTurns();
        boolean confirm=false;
        while(!keys.isEmpty())switch(keys.removeFirst()){
            case GLFW.GLFW_KEY_R -> turns++;
            case GLFW.GLFW_KEY_PAGE_UP -> height++;
            case GLFW.GLFW_KEY_PAGE_DOWN -> height--;
            case GLFW.GLFW_KEY_ENTER -> confirm=true;
            default -> { }
        }
        height=Math.clamp(height,-256,256);
        var eye=mc.player.getEyePosition();
        var hit=mc.level.clip(new ClipContext(eye,eye.add(mc.player.getLookAngle().scale(96)),ClipContext.Block.OUTLINE,ClipContext.Fluid.ANY,mc.player));
        hasTarget=hit.getType()==HitResult.Type.BLOCK;
        if(hasTarget)builder.setTransform(new PlacementTransform(hit.getBlockPos().relative(hit.getDirection()).above(height),turns,transform.mirrorX()));
        else if(turns!=transform.quarterTurns())builder.setTransform(new PlacementTransform(transform.origin(),turns,transform.mirrorX()));
        if(confirm&&hasTarget)stop();
    }
    public static String hint(){return hasTarget?"R rotates / Page Up or Down raises / Enter confirms":"Aim at terrain / Esc cancels placement";}
}
