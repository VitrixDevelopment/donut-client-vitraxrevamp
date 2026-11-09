// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre 26.2 by 4ldenz for Donut / Minecraft 1.21.11.
package dev.donut.client.spectrebuilder;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.*;

/** Small, loaded-terrain walking planner. Uses ordinary movement/jump inputs, never flight or teleports. */
final class GroundNavigator {
    private final ArrayDeque<BlockPos> route=new ArrayDeque<>();
    private int arrivalTicks;
    static boolean standable(Minecraft mc,BlockPos feet){
        if(!mc.level.hasChunkAt(feet)||!mc.level.getFluidState(feet).isEmpty())return false;
        return mc.level.getBlockState(feet.below()).isFaceSturdy(mc.level,feet.below(),Direction.UP)
            &&mc.level.noCollision(mc.player,new AABB(feet.getX()+.19,feet.getY()+.01,feet.getZ()+.19,feet.getX()+.81,feet.getY()+1.81,feet.getZ()+.81));
    }
    static List<BlockPos> path(Minecraft mc,BlockPos goal){
        BlockPos start=BlockPos.containing(mc.player.getX(),mc.player.getY()+.01,mc.player.getZ());
        for(int d=0;d<3&&!standable(mc,start);d++)start=start.below();
        if(!standable(mc,start)||!standable(mc,goal))return List.of();
        var queue=new ArrayDeque<BlockPos>();var parent=new HashMap<BlockPos,BlockPos>();queue.add(start);parent.put(start,start);
        while(!queue.isEmpty()&&parent.size()<2400){
            var p=queue.remove();
            if(p.equals(goal)){var result=new ArrayList<BlockPos>();for(var n=p;!n.equals(start);n=parent.get(n))result.add(n);Collections.reverse(result);return result.isEmpty()?List.of(goal):result;}
            for(var direction:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST))for(int dy:new int[]{0,1,-1}){
                var n=p.relative(direction).offset(0,dy,0);
                if(parent.containsKey(n)||Math.abs(n.getX()-start.getX())>24||Math.abs(n.getZ()-start.getZ())>24||!standable(mc,n))continue;
                // Enough headroom above the starting tile for a normal one-block jump.
                if(dy>0&&!mc.level.noCollision(mc.player,new AABB(p.getX()+.19,p.getY()+1,p.getZ()+.19,p.getX()+.81,p.getY()+2.81,p.getZ()+.81)))continue;
                parent.put(n,p);queue.add(n);break;
            }
        }
        return List.of();
    }
    void follow(List<BlockPos> path){route.clear();route.addAll(path);arrivalTicks=0;}
    static boolean stopped(Minecraft mc){return mc.player.onGround()&&mc.player.getDeltaMovement().horizontalDistance()<.004;}
    String diagnostic(){return "next="+route.peek()+" remaining="+route.size();}
    boolean tick(Minecraft mc){
        while(!route.isEmpty()){
            var next=route.peek();var center=Vec3.atBottomCenterOf(next);var delta=center.subtract(mc.player.position());
            boolean last=route.size()==1;
            if(delta.horizontalDistance()<(last?.10:.16)&&Math.abs(delta.y)<.2){
                if(last){
                    release(mc);mc.options.keyShift.setDown(true);
                    if(!stopped(mc))return false;
                }
                route.remove();continue;
            }
            mc.options.keyShift.setDown(last&&delta.horizontalDistance()<.8&&Math.abs(delta.y)<.2);
            // Finish landing without turning back toward a waypoint underneath us.
            if(delta.horizontalDistance()<.10&&!mc.player.onGround()){release(mc);return false;}
            // Face the walking route. Quantized strafing while aiming elsewhere can
            // pin the body against the corner of an otherwise reachable narrow gap.
            float desired=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
            float angle=net.minecraft.util.Mth.wrapDegrees(desired-mc.player.getYRot());
            // Release forward and let ordinary ground friction brake before a sharp
            // turn. Rotating while coasting makes forward inputs look like strafing.
            if(Math.abs(angle)>25&&!stopped(mc)){release(mc);return false;}
            mc.player.setYRot(mc.player.getYRot()+net.minecraft.util.Mth.clamp(angle*.4F,-12,12));
            mc.player.setXRot(mc.player.getXRot()+net.minecraft.util.Mth.clamp((15-mc.player.getXRot())*.25F,-6,6));
            mc.options.keyUp.setDown(Math.abs(angle)<25);mc.options.keyDown.setDown(false);
            mc.options.keyRight.setDown(false);mc.options.keyLeft.setDown(false);
            mc.options.keyJump.setDown(delta.y>.35&&mc.player.onGround()&&Math.abs(angle)<25);
            return false;
        }
        release(mc);
        // Also settle empty routes: a usable placement pose does not mean the
        // player has stopped moving. Keep the walking view until fully grounded.
        if(!stopped(mc)){arrivalTicks=0;return false;}
        if(++arrivalTicks<2)return false;
        mc.options.keyShift.setDown(false);return true;
    }
    static void release(Minecraft mc){mc.options.keyUp.setDown(false);mc.options.keyDown.setDown(false);mc.options.keyLeft.setDown(false);mc.options.keyRight.setDown(false);mc.options.keyJump.setDown(false);}
}
