// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre 26.2 by 4ldenz. See docs/spectre-amethyst-port.md.
package dev.donut.client.amethyst;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.chunkfinder.GeodeGlowScan;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayDeque;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AmethystScanner {
   public static final int COLOR = -3636481;
   private static final float LINE_WIDTH = 1.5F;
   private static final int RESCAN_TICKS = 20;
   private static final long BUDGET_NANOS = 2000000L;
   private static final String ANBS_SCAN = "ANBS+ Scan";
   private static final int MAX_GLOW_MARKS = 200;
   private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
   private final LongOpenHashSet found = new LongOpenHashSet();
   private final LongOpenHashSet bypassScanned = new LongOpenHashSet();
   private final Long2ObjectOpenHashMap<BlockPos> bypassMarkers = new Long2ObjectOpenHashMap();
   private final GeodeGlowScan glow = new GeodeGlowScan();
   private final LongArrayList glowCells = new LongArrayList();
   private long[] hits = new long[0];
   private int tick;

   private final ClientConfig config;
   private ClientLevel lastLevel;
   private String appliedMethod;
   private int appliedMinCells;
   private List<BlockPos> markers = List.of();
   private static final List<WeakReference<AmethystScanner>> INSTANCES = new CopyOnWriteArrayList<>();

   public AmethystScanner(ClientConfig config) { this.config = config; INSTANCES.add(new WeakReference<>(this)); }
   public List<BlockPos> snapshot() { return markers; }
   public int pendingChunks() { return queue.size(); }
   public void clear() { restart(); lastLevel = null; appliedMethod = null; }
   private void setHits(long[] next) { hits = next; markers = Arrays.stream(next).mapToObj(BlockPos::of).toList(); }
   public static void packetApplied(Packet<?> packet) {
      for (var reference : INSTANCES) {
         var scanner = reference.get();
         if (scanner == null) INSTANCES.remove(reference);
         else if (scanner.config.amethyst.enabled && scanner.lastLevel != null) scanner.onPacketApplied(packet);
      }
   }
   private static boolean isLoaded(ClientLevel level, int x, int z) {
      return level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false) != null;
   }

   private void restart() {
      this.queue.clear();
      this.found.clear();
      this.bypassScanned.clear();
      this.bypassMarkers.clear();
      setHits(new long[0]);
      glow.clear();
      this.tick = 0;
   }

   public void tick(Minecraft mc) {
      if (!config.amethyst.enabled || mc.level == null || mc.player == null) {
         if (lastLevel != null) clear();
         return;
      }
      ClientLevel level = mc.level;
      if (level != lastLevel || !config.amethyst.method.equals(appliedMethod) || config.amethyst.minCells != appliedMinCells) {
         restart(); lastLevel = level; appliedMethod = config.amethyst.method; appliedMinCells = config.amethyst.minCells;
         tick = RESCAN_TICKS - 1;
      }
      if (level != null && mc.player != null) {
         boolean bypass = config.amethyst.method.equals("Bypass");
         if (this.queue.isEmpty()) {
            if (++this.tick < 20) {
               return;
            }

            this.tick = 0;
            ChunkPos center = mc.player.chunkPosition();
            int radius = mc.options.renderDistance().get();
            if (bypass) {
               this.bypassScanned.removeIf(keyx -> !inRange(keyx, center, radius) || !isLoaded(level, ChunkPos.getX(keyx), ChunkPos.getZ(keyx)));
               this.bypassMarkers.keySet().removeIf(keyx -> !this.bypassScanned.contains(keyx));

               for (int x = center.x - radius; x <= center.x + radius; x++) {
                  for (int z = center.z - radius; z <= center.z + radius; z++) {
                     long key = ChunkPos.asLong(x, z);
                     if (isLoaded(level, x, z) && !this.bypassScanned.contains(key)) {
                        this.queue.add(new ChunkPos(x, z));
                     }
                  }
               }
            } else {
               this.found.clear();

               for (int x = center.x - radius; x <= center.x + radius; x++) {
                  for (int zx = center.z - radius; zx <= center.z + radius; zx++) {
                     if (isLoaded(level, x, zx)) {
                        this.queue.add(new ChunkPos(x, zx));
                     }
                  }
               }
            }
         }

         long deadline = System.nanoTime() + 2000000L;

         while (!this.queue.isEmpty() && System.nanoTime() < deadline) {
            ChunkPos pos = this.queue.poll();
            if (isLoaded(level, pos.x, pos.z)) {
               LevelChunk chunk = level.getChunk(pos.x, pos.z);
               if (bypass) {
                  collectPaletteLeak(chunk, this.bypassMarkers);
                  this.bypassScanned.add(pos.toLong());
               } else if (config.amethyst.method.equals("ANBS+ Scan")) {
                  this.collectGlow(level, chunk);
               } else {
                  collectHiddenBuds(level, chunk, this.found);
               }
            }
         }

         if (this.queue.isEmpty()) {
            setHits(bypass ? markersToLongArray(this.bypassMarkers) : this.found.toLongArray());
         }
      }
   }

   private void collectGlow(ClientLevel level, LevelChunk chunk) {
      this.glowCells.clear();
      if (this.glow.scan(level, chunk, this.glowCells::add) >= config.amethyst.minCells) {
         int marks = Math.min(this.glowCells.size(), 200);

         for (int i = 0; i < marks; i++) {
            this.found.add(this.glowCells.getLong(i));
         }
      }
   }

   private static boolean inRange(long chunkKey, ChunkPos center, int radius) {
      int x = ChunkPos.getX(chunkKey);
      int z = ChunkPos.getZ(chunkKey);
      return Math.abs(x - center.x) <= radius && Math.abs(z - center.z) <= radius;
   }

   private static long[] markersToLongArray(Long2ObjectOpenHashMap<BlockPos> markers) {
      long[] out = new long[markers.size()];
      int i = 0;
      ObjectIterator var3 = markers.values().iterator();

      while (var3.hasNext()) {
         BlockPos marker = (BlockPos)var3.next();
         out[i++] = marker.asLong();
      }

      return out;
   }

   private void onPacketApplied(Packet<?> packet) {
      if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
         long key = forget.pos().toLong();
         bypassScanned.remove(key); bypassMarkers.remove(key);
         found.removeIf(pos -> ChunkPos.asLong(BlockPos.getX(pos) >> 4,BlockPos.getZ(pos) >> 4) == key);
         setHits(config.amethyst.method.equals("Bypass") ? markersToLongArray(bypassMarkers) : found.toLongArray());
      }
      if (config.amethyst.method.equals("Bypass")) {
         if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            this.dirtyBypassChunk(chunk.getX(), chunk.getZ());
         } else if (packet instanceof ClientboundBlockUpdatePacket update) {
            this.dirtyBypassChunk(update.getPos().getX() >> 4, update.getPos().getZ() >> 4);
         } else if (packet instanceof ClientboundSectionBlocksUpdatePacket sectionUpdate) {
            sectionUpdate.runUpdates((pos, state) -> this.dirtyBypassChunk(pos.getX() >> 4, pos.getZ() >> 4));
         }
      }
   }

   private void dirtyBypassChunk(int chunkX, int chunkZ) {
      long key = ChunkPos.asLong(chunkX, chunkZ);
      if (this.bypassScanned.remove(key)) {
         this.bypassMarkers.remove(key);
      }
   }

   private static void collectPaletteLeak(LevelChunk chunk, Long2ObjectOpenHashMap<BlockPos> out) {
      LevelChunkSection[] sections = chunk.getSections();
      int minSectionY = chunk.getMinSectionY();
      ChunkPos pos = chunk.getPos();

      for (int i = 0; i < sections.length; i++) {
         LevelChunkSection section = sections[i];
         if (section != null) {
            PalettedContainer<BlockState> states = section.getStates();
            if (!states.maybeHas(state -> false) && states.maybeHas(AmethystScanner::isBud)) {
               int baseY = (minSectionY + i) * 16;
               BlockPos marker = pickMarker(chunk, section, baseY, pos);
               out.put(pos.toLong(), marker != null ? marker : new BlockPos(pos.getMinBlockX() + 8, baseY + 8, pos.getMinBlockZ() + 8));
               return;
            }
         }
      }
   }

   private static BlockPos pickMarker(LevelChunk chunk, LevelChunkSection section, int baseY, ChunkPos pos) {
      MutableBlockPos neighbour = new MutableBlockPos();
      BlockPos best = null;
      int bestScore = Integer.MAX_VALUE;

      for (int y = 0; y < 16; y++) {
         int worldY = baseY + y;

         for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
               if (section.getBlockState(x, y, z).isAir()) {
                  int worldX = pos.getMinBlockX() + x;
                  int worldZ = pos.getMinBlockZ() + z;
                  boolean touchesGeode = false;

                  for (Direction direction : Direction.values()) {
                     int nx = worldX + direction.getStepX();
                     int nz = worldZ + direction.getStepZ();
                     if (nx >> 4 == pos.x && nz >> 4 == pos.z) {
                        neighbour.set(nx, worldY + direction.getStepY(), nz);
                        Block block = chunk.getBlockState(neighbour).getBlock();
                        if (block == Blocks.AMETHYST_BLOCK || block == Blocks.BUDDING_AMETHYST) {
                           touchesGeode = true;
                           break;
                        }
                     }
                  }

                  if (touchesGeode) {
                     int score = scatter(worldX, worldY, worldZ);
                     if (score < bestScore) {
                        bestScore = score;
                        best = new BlockPos(worldX, worldY, worldZ);
                     }
                  }
               }
            }
         }
      }

      return best;
   }

   static int scatter(int x, int y, int z) {
      long h = x * -7046029254386353131L ^ y * -4417276706812531889L ^ z * 1609587929392839161L;
      h ^= h >>> 29;
      h *= -4658895280553007687L;
      h ^= h >>> 32;
      return Math.floorMod(h, 100);
   }

   private static void collectHiddenBuds(ClientLevel level, LevelChunk chunk, LongOpenHashSet out) {
      LevelChunkSection[] sections = chunk.getSections();
      int minSectionY = chunk.getMinSectionY();
      ChunkPos pos = chunk.getPos();
      MutableBlockPos cursor = new MutableBlockPos();

      for (int i = 0; i < sections.length; i++) {
         LevelChunkSection section = sections[i];
         if (section != null && !section.hasOnlyAir() && section.getStates().maybeHas(AmethystScanner::isGeode)) {
            int baseY = (minSectionY + i) * 16;

            for (int y = 0; y < 16; y++) {
               for (int z = 0; z < 16; z++) {
                  for (int x = 0; x < 16; x++) {
                     if (section.getBlockState(x, y, z).getLightEmission() <= 0) {
                        cursor.set(pos.getMinBlockX() + x, baseY + y, pos.getMinBlockZ() + z);
                        int light = level.getBrightness(LightLayer.BLOCK, cursor);
                        if (isBudLight(light) && touchesGeode(level, cursor) && isLocalMaximum(level, cursor, light)) {
                           out.add(cursor.asLong());
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static boolean isBudLight(int light) {
      return light == 1 || light == 2 || light == 4 || light == 5;
   }

   private static boolean touchesGeode(ClientLevel level, BlockPos pos) {
      for (Direction direction : Direction.values()) {
         if (isGeode(level.getBlockState(pos.relative(direction)))) {
            return true;
         }
      }

      return false;
   }

   private static boolean isLocalMaximum(ClientLevel level, BlockPos pos, int light) {
      for (Direction direction : Direction.values()) {
         if (level.getBrightness(LightLayer.BLOCK, pos.relative(direction)) >= light) {
            return false;
         }
      }

      return true;
   }

   private static boolean isBud(BlockState state) {
      Block block = state.getBlock();
      return block == Blocks.SMALL_AMETHYST_BUD || block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.LARGE_AMETHYST_BUD;
   }

   private static boolean isGeode(BlockState state) {
      return state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST);
   }

}
