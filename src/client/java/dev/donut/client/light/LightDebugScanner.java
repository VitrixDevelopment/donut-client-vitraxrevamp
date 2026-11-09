// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre 26.2 by 4ldenz for Donut / Minecraft 1.21.11.
package dev.donut.client.light;

import dev.donut.client.config.ClientConfig;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/** Main-thread light collection; detached immutable geometry is processed by a cancellable worker. */
public final class LightDebugScanner implements AutoCloseable {
    private final ClientConfig config;
    private final SessionWorker<Map<Integer,List<AABB>>> worker=new SessionWorker<>("Donut-Light-Debug");
    private ClientLevel world;
    private LightSnapshot collecting;
    private long lastScan;
    private int radius=-1,minLight=-1;
    private boolean denoise;
    private Map<Integer,List<AABB>> data=Map.of();
    public LightDebugScanner(ClientConfig config){this.config=config;}
    public Map<Integer,List<AABB>> snapshot(){return config.lightDebug.enabled ? data : Map.of();}
    public void clear(){worker.close();world=null;collecting=null;data=Map.of();lastScan=0;}
    @Override public void close(){clear();}
    public void tick(Minecraft mc){
        var s=config.lightDebug;
        if(!s.enabled||mc.level==null||mc.player==null){if(world!=null||collecting!=null||worker.busy()||!data.isEmpty())clear();return;}
        if(world!=mc.level||radius!=s.radius||minLight!=s.minLight||denoise!=s.denoise){
            clear();world=mc.level;radius=s.radius;minLight=s.minLight;denoise=s.denoise;
        }
        worker.poll(result->data=result);
        if(worker.busy())return;
        long now=System.currentTimeMillis();
        if(collecting==null){if(now-lastScan<1000)return;collecting=new LightSnapshot(mc.player.chunkPosition(),radius,minLight);}
        if(collecting.collect(mc.level)){
            Map<BlockPos,Integer> hits=collecting.hits();boolean filter=denoise;int minimum=minLight;
            collecting=null;lastScan=now;worker.submit(()->buildBoxes(hits,filter,minimum));
        }
    }
    public static int fillColor(int base,int light){
        float t=Math.clamp(light/15f,0,1);int r=lerp(base>>>16&255,255,t),g=lerp(base>>>8&255,255,t),b=lerp(base&255,255,t);
        return Math.round((base>>>24)*(.5f+.5f*t))<<24|r<<16|g<<8|b;
    }
    public static int strokeColor(int fill){return 0xFF000000|Math.round((fill>>>16&255)*.45f)<<16|Math.round((fill>>>8&255)*.45f)<<8|Math.round((fill&255)*.45f);}
    private static int lerp(int a,int b,float t){return Math.round(a+(b-a)*t);}
   static Map<Integer, List<AABB>> buildBoxes(Map<BlockPos, Integer> hits, boolean denoise, int minLight) {
      Set<BlockPos> skip = new HashSet<>();
      if (denoise) {
         for (Entry<BlockPos, Integer> entry : hits.entrySet()) {
            if (Thread.currentThread().isInterrupted()) {
               throw new CancellationException();
            }

            BlockPos source = entry.getKey();
            int light = entry.getValue();
            if (light >= minLight + 1 && light <= minLight + 2 && !skip.contains(source)) {
               boolean stronger = false;

               for (int x = -1; x <= 1 && !stronger; x++) {
                  for (int y = -1; y <= 1 && !stronger; y++) {
                     for (int z = -1; z <= 1; z++) {
                        if (hits.getOrDefault(source.offset(x, y, z), 0) > light) {
                           stronger = true;
                           break;
                        }
                     }
                  }
               }

               if (!stronger) {
                  ArrayDeque<BlockPos> queue = new ArrayDeque<>();
                  queue.add(source);
                  skip.add(source);

                  while (!queue.isEmpty()) {
                     if (Thread.currentThread().isInterrupted()) {
                        throw new CancellationException();
                     }

                     BlockPos pos = queue.removeFirst();

                     for (Direction dir : Direction.values()) {
                        BlockPos next = pos.relative(dir);
                        int value = hits.getOrDefault(next, 0);
                        if (value >= minLight && value <= light && skip.add(next)) {
                           queue.addLast(next);
                        }
                     }
                  }
               }
            }
         }
      }

      Map<Integer, List<AABB>> built = new HashMap<>();
      int count = 0;

      for (Entry<BlockPos, Integer> entry : hits.entrySet()) {
         if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
         }

         BlockPos pos = entry.getKey();
         if (!skip.contains(pos)) {
            boolean exposed = false;

            for (Direction dirx : Direction.values()) {
               BlockPos next = pos.relative(dirx);
               if (!hits.containsKey(next) || skip.contains(next)) {
                  exposed = true;
                  break;
               }
            }

            if (exposed) {
               built.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(new AABB(pos));
               if (++count >= 12000) {
                  break;
               }
            }
         }
      }

      built.replaceAll((light, boxes) -> List.copyOf(boxes));
      return Map.copyOf(built);
   }

}
