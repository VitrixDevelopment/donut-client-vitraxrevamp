// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre 26.2 by 4ldenz for Donut / Minecraft 1.21.11.
package dev.donut.client.spectrebuilder;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.*;

/** Creative-only placement executor. Never writes world blocks or creates inventory items. */
public final class CreativeBuilder {
    public static final CreativeBuilder INSTANCE=new CreativeBuilder();
    private record Step(BlockPos pos,BlockState state,Item item,boolean fluid) { }
    private record Aim(BlockHitResult hit,float yaw,float pitch) { }
    private record Approach(Vec3 position,Aim aim,List<BlockPos> route) { }
    private final GroundNavigator navigator=new GroundNavigator();
    private BlockState observed;
    private int cooldown,poseStable;
    private Approach approach;
    private List<Step> steps=List.of();
    private Map<BlockPos,BlockState> expected=Map.of();
    private ClientLevel world;
    private int index,wait,age,settle,oldSlot,travelStage,attempts;
    private boolean running,paused,oldFlying;
    private CompletableFuture<BlockState> confirmed;
    private String status="Ready. Creative single-player construction.";
    public boolean running(){return running;}
    public boolean paused(){return paused;}
    public String status(){return status;}
    public int completed(){return index;}
    public int total(){return steps.size();}
    public String diagnostic(){var p=Minecraft.getInstance().player;return "target="+(index<steps.size()?steps.get(index).pos:"done")+" stage="+travelStage+" age="+age+" attempts="+attempts+" stable="+poseStable+" yaw="+p.getYRot()+" pitch="+p.getXRot()+" observed="+observed+" approach="+(approach==null?"none":approach.position)+" "+navigator.diagnostic();}
    public void pause(){if(running){paused=!paused;Minecraft.getInstance().player.setDeltaMovement(Vec3.ZERO);}}
    public void stop(){finish("Stopped. Placed blocks remain in the world.");}
    private void finish(String message){
        var mc=Minecraft.getInstance();GroundNavigator.release(mc);running=false;paused=false;status=message;
        if(mc.player!=null){mc.player.setDeltaMovement(Vec3.ZERO);mc.player.getInventory().setSelectedSlot(oldSlot);
            mc.player.getAbilities().flying=oldFlying;mc.player.onUpdateAbilities();mc.options.keyShift.setDown(false);mc.options.keyJump.setDown(false);}
    }
    public void start(List<Schematic.Block> blocks,Schematic.Pos origin,int rotation){
        var mc=Minecraft.getInstance();
        if(running)throw new IllegalStateException("A build is already running");
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||!mc.player.getAbilities().instabuild)
            throw new IllegalStateException("This executor requires creative single-player");
        var planned=new ArrayList<Step>();var targets=new LinkedHashMap<BlockPos,BlockState>();
        Rotation turn=Rotation.values()[Math.floorMod(rotation,4)];
        for(var block:blocks){
            var p=block.pos().rotate(rotation).add(origin);var pos=new BlockPos(p.x(),p.y(),p.z());
            var state=BuilderMaterials.state(block.state()).rotate(turn);
            if(!mc.level.hasChunkAt(pos)||!mc.level.getWorldBorder().isWithinBounds(pos)||mc.level.isOutsideBuildHeight(pos))
                throw new IllegalArgumentException("Build position is outside loaded, buildable terrain: "+pos);
            targets.put(pos,state);
            if(state.is(Blocks.WATER)||state.is(Blocks.LAVA)){
                if(state.getValue(BlockStateProperties.LEVEL)!=0)throw new IllegalArgumentException("Flowing fluid cells must be produced by sources: "+pos);
                planned.add(new Step(pos,state,state.is(Blocks.WATER)?Items.WATER_BUCKET:Items.LAVA_BUCKET,true));continue;
            }
            if(state.getBlock() instanceof DoorBlock||state.getBlock() instanceof BedBlock||state.getBlock() instanceof DoublePlantBlock
                ||state.is(Blocks.PISTON_HEAD)||state.is(Blocks.MOVING_PISTON))throw new IllegalArgumentException("Unsupported multi-block placement: "+state);
            Item item=state.is(Blocks.REDSTONE_WIRE)?Items.REDSTONE:state.getBlock().asItem();
            if(!(item instanceof BlockItem))throw new IllegalArgumentException("No supported placement item for "+state);
            boolean wet=state.hasProperty(BlockStateProperties.WATERLOGGED)&&state.getValue(BlockStateProperties.WATERLOGGED);
            planned.add(new Step(pos,wet?state.setValue(BlockStateProperties.WATERLOGGED,false):state,item,false));
            if(wet)planned.add(new Step(pos,state,Items.WATER_BUCKET,true));
        }
        if(planned.isEmpty())throw new IllegalArgumentException("Schematic has no blocks");
        for(var entry:targets.entrySet()){
            var actual=mc.level.getBlockState(entry.getKey());
            if(!actual.isAir()&&!actual.equals(entry.getValue()))throw new IllegalArgumentException("Occupied build cell at "+entry.getKey()+": "+actual);
        }
        for(var step:planned)if(!hasItem(step.item))throw new IllegalArgumentException("Missing inventory item: "+step.item);
        planned.sort(Comparator.comparingInt((Step s)->s.fluid?3:s.state.getBlock() instanceof RedStoneWireBlock||s.state.getBlock() instanceof DiodeBlock?2:s.state.getBlock() instanceof HopperBlock||s.state.getBlock() instanceof PistonBaseBlock?1:0)
            .thenComparingInt(s->s.pos.getY()).thenComparingInt(s->s.pos.getZ()).thenComparingInt(s->(s.pos.getZ()&1)==0?s.pos.getX():-s.pos.getX()));
        steps=List.copyOf(planned);expected=Map.copyOf(targets);world=mc.level;index=wait=age=settle=travelStage=attempts=0;approach=null;confirmed=null;
        oldSlot=mc.player.getInventory().getSelectedSlot();oldFlying=mc.player.getAbilities().flying;
        mc.player.getAbilities().flying=false;mc.player.onUpdateAbilities();observed=null;cooldown=0;running=true;paused=false;
        status="Building 0 / "+steps.size();mc.setScreen(null);
    }
    private static boolean hasItem(Item item){var inv=Minecraft.getInstance().player.getInventory();for(int i=0;i<36;i++)if(inv.getItem(i).is(item))return true;return false;}
    private boolean select(Item item){
        var mc=Minecraft.getInstance();var inv=mc.player.getInventory();
        if(inv.getSelectedItem().is(item))return true;
        for(int i=0;i<36;i++)if(inv.getItem(i).is(item)){
            if(i<9)inv.setSelectedSlot(i);
            else mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId,i,inv.getSelectedSlot(),ClickType.SWAP,mc.player);
            wait=3;return false;
        }
        throw new IllegalStateException("Inventory no longer contains "+item);
    }
    private static boolean configured(BlockState wanted,BlockState actual){
        if(wanted.getBlock()!=actual.getBlock())return false;
        for(var property:wanted.getProperties()){
            String name=property.getName();
            if(Set.of("powered","power","locked","extended","enabled","delay").contains(name))continue;
            if(wanted.getBlock() instanceof RedStoneWireBlock&&Set.of("north","south","east","west").contains(name))continue;
            if(!wanted.getValue(property).equals(actual.getValue(property)))return false;
        }
        return true;
    }
    public void tick(Minecraft mc){
        if(!running)return;
        if(mc.level!=world||mc.player==null||mc.getSingleplayerServer()==null||!mc.player.getAbilities().instabuild){finish("Stopped: world or game mode changed");return;}
        if(dev.donut.client.freecam.FreecamController.enabled()||!mc.player.isAlive()||mc.player.isPassenger()){finish("Stopped: player unavailable, riding or Freecam active");return;}
        if(paused||mc.screen!=null||mc.isPaused()){GroundNavigator.release(mc);return;}
        try{
            if(cooldown>0)cooldown--;
            if(++age>1500)throw new IllegalStateException("No progress: build needs a clear walking route or scaffolding");
            if(wait-->0)return;
            if(index==steps.size()){
                GroundNavigator.release(mc);
                if(++settle<60)return;
                var errors=new ArrayList<String>();
                for(var e:expected.entrySet())if(!world.getBlockState(e.getKey()).equals(e.getValue()))
                    errors.add(e.getKey()+" expected "+e.getValue()+"; got "+world.getBlockState(e.getKey()));
                finish(errors.isEmpty()?"Complete: verified "+expected.size()+" exact block states.":"Verification failed ("+errors.size()+" cells): "+errors.getFirst());return;
            }
            Step step=steps.get(index);
            if(observed==null){
                GroundNavigator.release(mc);
                if(confirmed==null){var dimension=world.dimension();confirmed=mc.getSingleplayerServer().submit(()->mc.getSingleplayerServer().getLevel(dimension).getBlockState(step.pos));return;}
                if(!confirmed.isDone())return;observed=confirmed.join();confirmed=null;
            }
            var actual=observed;
            if(actual.equals(step.state)){advance();return;}
            // The integrated server's task queue can run before its placement packet arrives.
            // Do not plan around a stale air snapshot while the accepted block is appearing.
            if(actual.isAir()&&!world.getBlockState(step.pos).isAir()&&attempts>0){observed=null;wait=2;return;}
            if(!select(step.item))return;
            if(approach==null){
                if(!mc.player.onGround())return;
                approach=findApproach(mc,step);
                if(approach==null)throw new IllegalStateException("No visible walking approach for "+step.state+" at "+step.pos+". Add access/scaffolding.");
                navigator.follow(approach.route);travelStage=1;poseStable=0;
            }
            if(travelStage==1){if(navigator.tick(mc))travelStage=2;return;}
            GroundNavigator.release(mc);
            // Never lock onto a placement face (or repeater) while still walking,
            // landing, or being pushed. Navigation owns the view until settled.
            if(!GroundNavigator.stopped(mc)){poseStable=0;return;}
            if(configured(step.state,actual)&&!step.fluid){
                if(step.state.getBlock() instanceof RepeaterBlock&&actual.getValue(BlockStateProperties.DELAY)!=step.state.getValue(BlockStateProperties.DELAY)){
                    mc.options.keyShift.setDown(false);
                    if(!lookAt(mc,Vec3.atBottomCenterOf(step.pos).add(0,.1,0))||cooldown>0)return;
                    var hit=trace(mc);if(!hit.getBlockPos().equals(step.pos))throw new IllegalStateException("Repeater is obstructed");
                    mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);observed=null;cooldown=5;wait=3;return;
                }
                advance();return;
            }
            if(!actual.isAir()&&!(step.fluid&&step.state.getBlock()==actual.getBlock()))throw new IllegalStateException("Unexpected block at "+step.pos+": "+actual);
            if(mc.player.getBoundingBox().intersects(new AABB(step.pos).inflate(.08))){approach=null;travelStage=0;return;}
            if(!mc.player.onGround()||cooldown>0||!lookAt(mc,approach.aim.hit.getLocation())){poseStable=0;return;}
            var visible=trace(mc);
            if(visible.getType()!=HitResult.Type.BLOCK||!visible.getBlockPos().equals(approach.aim.hit.getBlockPos())||visible.getDirection()!=approach.aim.hit.getDirection()){
                approach=null;return;
            }
            if(!step.fluid){
                var context=new BlockPlaceContext(mc.player,InteractionHand.MAIN_HAND,mc.player.getMainHandItem(),visible);
                var prediction=((BlockItem)step.item).getBlock().getStateForPlacement(context);
                if(!context.getClickedPos().equals(step.pos)||prediction==null||!configured(step.state,prediction)){approach=null;return;}
            }
            // Crouch only for interactive supports; no simultaneous jump or flight.
            boolean interactive=world.getBlockState(visible.getBlockPos()).hasBlockEntity();
            if(interactive&&!mc.player.isShiftKeyDown()){mc.options.keyShift.setDown(true);poseStable=0;wait=2;return;}
            // END_CLIENT_TICK follows movement packet transmission. Keep the validated
            // pose for another tick so directional placement uses the server's updated yaw.
            boolean directional=step.state.getProperties().stream().anyMatch(p->Set.of("facing","axis","half","face").contains(p.getName()));
            if(directional&&++poseStable<3)return;
            if(++attempts>5)throw new IllegalStateException("Server rejected five placement attempts at "+step.pos);
            if(step.fluid)mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);
            else mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,visible);
            mc.player.swing(InteractionHand.MAIN_HAND);observed=null;cooldown=5;wait=3;
        }catch(Exception ex){finish("Stopped: "+ex.getMessage());}
    }
    private void advance(){
        index++;age=travelStage=attempts=0;approach=null;observed=null;
        Minecraft.getInstance().options.keyShift.setDown(false);
        status="Building "+index+" / "+steps.size();
    }
    private static boolean lookAt(Minecraft mc,Vec3 point){
        Vec3 d=point.subtract(mc.player.getEyePosition());
        return turn(mc,(float)Math.toDegrees(Math.atan2(-d.x,d.z)),(float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z))));
    }
    private static boolean turn(Minecraft mc,float yaw,float pitch){
        float dy=net.minecraft.util.Mth.wrapDegrees(yaw-mc.player.getYRot()),dp=pitch-mc.player.getXRot();
        mc.player.setYRot(mc.player.getYRot()+net.minecraft.util.Mth.clamp(dy*.45F,-12,12));
        mc.player.setXRot(mc.player.getXRot()+net.minecraft.util.Mth.clamp(dp*.45F,-10,10));
        return Math.abs(dy)<.7F&&Math.abs(dp)<.7F;
    }
    private static BlockHitResult trace(Minecraft mc){
        Vec3 eye=mc.player.getEyePosition();
        return mc.level.clip(new ClipContext(eye,eye.add(mc.player.getLookAngle().scale(mc.player.blockInteractionRange())),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
    }
    private Approach findApproach(Minecraft mc,Step step){
        Vec3 original=mc.player.position();float yaw=mc.player.getYRot(),pitch=mc.player.getXRot();
        int clear=0,visibleCount=0,predictedCount=0;
        var candidates=new ArrayList<Vec3>();
        // Reuse the player's real pose when it already offers a valid placement.
        // This also avoids endlessly retrying a block-center prediction after arriving slightly off-center.
        if(mc.player.onGround())candidates.add(original);
        for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)for(int y=-3;y<=4;y++){
            var feet=step.pos.offset(x,y,z);if(GroundNavigator.standable(mc,feet))candidates.add(Vec3.atBottomCenterOf(feet));
        }
        candidates.sort(Comparator.comparingDouble(v->v.distanceToSqr(original)));
        try{
            for(Vec3 position:candidates){
                var body=mc.player.getBoundingBox().move(position.subtract(original));
                if(body.intersects(new AABB(step.pos).inflate(.18))||!world.noCollision(mc.player,body))continue;
                clear++;
                mc.player.setPos(position);
                for(Direction face:Direction.values()){
                    BlockPos support=step.pos.relative(face.getOpposite());
                    if(step.fluid&&step.state.hasProperty(BlockStateProperties.WATERLOGGED))support=step.pos;
                    if(world.getBlockState(support).isAir())continue;
                    for(double fraction:new double[]{.5,.25,.75}){
                        Vec3 hit=Vec3.atCenterOf(support).add(face.getStepX()*.5,face.getStepY()*.5,face.getStepZ()*.5);
                        if(face.getAxis()!=Direction.Axis.Y)hit=new Vec3(hit.x,support.getY()+fraction,hit.z);
                        Vec3 delta=hit.subtract(mc.player.getEyePosition());
                        float y=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
                        float p=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
                        mc.player.setYRot(y);mc.player.setXRot(p);
                        var visible=trace(mc);
                        if(visible.getType()!=HitResult.Type.BLOCK||!visible.getBlockPos().equals(support)||visible.getDirection()!=face)continue;
                        visibleCount++;
                        if(step.fluid){
                            mc.player.setPos(original);var route=GroundNavigator.path(mc,BlockPos.containing(position));mc.player.setPos(position);
                            if(position.equals(original))return new Approach(position,new Aim(visible,y,p),List.of());
                            if(!route.isEmpty())return new Approach(position,new Aim(visible,y,p),route);continue;
                        }
                        var context=new BlockPlaceContext(mc.player,InteractionHand.MAIN_HAND,mc.player.getMainHandItem(),visible);
                        var predicted=((BlockItem)step.item).getBlock().getStateForPlacement(context);
                        if(context.getClickedPos().equals(step.pos)&&predicted!=null&&configured(step.state,predicted)&&predicted.canSurvive(world,step.pos)){
                            // A diagonal exactly between two facings can change direction after
                            // even a tiny walking offset. Reject such fragile candidate poses.
                            boolean robust=true;
                            for(float[] jitter:new float[][]{{-3,0},{3,0},{0,-3},{0,3}}){
                                mc.player.setYRot(y+jitter[0]);mc.player.setXRot(p+jitter[1]);
                                var ray=trace(mc);
                                if(ray.getType()!=HitResult.Type.BLOCK||!ray.getBlockPos().equals(support)||ray.getDirection()!=face){robust=false;break;}
                                var check=((BlockItem)step.item).getBlock().getStateForPlacement(context);
                                if(check==null||!configured(step.state,check)){robust=false;break;}
                            }
                            mc.player.setYRot(y);mc.player.setXRot(p);
                            if(!robust)continue;
                            predictedCount++;
                            mc.player.setPos(original);var route=GroundNavigator.path(mc,BlockPos.containing(position));mc.player.setPos(position);
                            if(position.equals(original))return new Approach(position,new Aim(visible,y,p),List.of());
                            if(!route.isEmpty())return new Approach(position,new Aim(visible,y,p),route);
                        }
                    }
                }
                mc.player.setPos(original);
            }
            if(Boolean.getBoolean("spectre.smoke"))System.out.println("BUILDER_APPROACH: feet="+original+" target="+step.pos+" candidates="+candidates.size()+" clear="+clear+" visible="+visibleCount+" predicted="+predictedCount+" startStandable="+GroundNavigator.standable(mc,BlockPos.containing(original)));
            return null;
        }finally{mc.player.setPos(original);mc.player.setYRot(yaw);mc.player.setXRot(pitch);}
    }
}
