package dev.donut.client.blockesp;

import dev.donut.client.config.BlockEspSettings;
import dev.donut.client.config.ClientConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-thread results and settings; a single worker exclusively owns the generation world.
 *
 * <p>Two independent sources feed the markers. Seed simulation predicts original generation, but a
 * server can sever the seed-to-ore relationship at any time (per-feature seeds, seed rotation, a
 * new world seed): predictions for such chunks are indistinguishable from random positions. Ores in
 * chunks the server has actually sent cannot be severed that way, so every tick also scans the
 * received chunks and cross-checks the two sources with {@link SeedTrust}. While the seed verifies,
 * both sources are shown (they agree, apart from mined blocks). Once enough received ores prove the
 * seed dead, predictions are suppressed and generation stops; the received-chunk ESP keeps working
 * on any server, subject only to whatever the server chooses to send (e.g. anti-xray hiding
 * unexposed ores).
 */
public final class BlockEspController implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/BlockESP");
    private static final int MAX_CACHED_CHUNKS = 512;
    private static BlockEspController instance;
    private final ClientConfig config;
    private final Path scratchParent;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("Donut BlockESP generation").daemon(true).factory());
    private final AtomicLong revision = new AtomicLong();
    private final LinkedHashMap<Long, List<OreMarker>> cache = new LinkedHashMap<>();
    private CompletableFuture<Result> pending;
    private long pendingRevision;
    private ClientLevel lastLevel;
    private long seed;
    private boolean seedValid = true;
    private boolean active, failed, closed;
    private int centerX, centerZ, completed, total;
    private List<OreMarker> markers = List.of();
    /** Exact target ores present in chunks the server has sent, refreshed every tick. */
    private List<OreMarker> liveMarkers = List.of();
    private SeedTrust.State trust = SeedTrust.State.UNKNOWN;
    private int matchedOres, observedOres;
    private String status = "Disabled";
    // Accessed exclusively by worker tasks, including cleanup.
    private VanillaOreGenerator generator;
    private long generatorSeed;

    public BlockEspController(ClientConfig config) {
        this(config, FabricLoader.getInstance().getGameDir().resolve(".cache/donut-blockesp"));
        instance = this;
    }

    public BlockEspController(ClientConfig config, Path scratchParent) {
        this.config = config;
        this.scratchParent = scratchParent;
    }

    public static BlockEspController get() { return instance; }
    public List<OreMarker> snapshot() { return markers; }
    public String status() { return status; }
    public int completedChunks() { return completed; }
    public int totalChunks() { return total; }
    public SeedTrust.State seedTrust() { return trust; }
    public int matchedOres() { return matchedOres; }
    public int observedOres() { return observedOres; }

    public void tick(Minecraft client) {
        if (closed) return;
        collect();
        var settings = config.blockEsp;
        if (!settings.enabled || client.level == null || client.player == null) {
            deactivate(settings.enabled ? "Join a world to predict ores" : "Disabled");
            return;
        }
        if (!client.level.dimension().equals(Level.OVERWORLD)) {
            deactivate("Overworld only");
            return;
        }
        if (!settings.emerald && !settings.coal) {
            deactivate("Select an ore in BlockESP settings");
            return;
        }
        long nextSeed = seed;
        String seedError = null;
        try { nextSeed = BlockEspSettings.parseSeed(settings.seed); }
        catch (IllegalArgumentException error) { seedError = error.getMessage(); }
        if (!active || client.level != lastLevel || (seedError == null && nextSeed != seed)) {
            invalidate();
            lastLevel = client.level;
            seed = nextSeed;
            active = true;
        }
        // An unreadable seed disables prediction only; ores already sent by the server remain true.
        seedValid = seedError == null;
        var center = client.player.chunkPosition();
        centerX = center.x;
        centerZ = center.z;
        total = (settings.radius * 2 + 1) * (settings.radius * 2 + 1);
        publish(client);
        if (!seedValid) {
            status = seedError + " Showing ores present in received chunks only.";
            return;
        }
        if (trust == SeedTrust.State.REFUTED) {
            // Generating further chunks would only produce random positions at full CPU cost.
            status = "Seed no longer matches this world (" + (observedOres - matchedOres)
                    + " received ores unpredicted) - showing ores from received chunks";
            return;
        }
        if (failed) return;
        if (pending == null) {
            ChunkPos next = nextChunk(centerX, centerZ, settings.radius, cache.keySet());
            if (next != null) {
                long token = revision.get(), requestedSeed = seed;
                pendingRevision = token;
                pending = CompletableFuture.supplyAsync(() -> generate(token, requestedSeed, next), worker);
            }
        }
        status = completed == total
                ? trust == SeedTrust.State.VERIFIED
                        ? "Ready - seed verified against " + matchedOres + " received ores"
                        : "Ready - original ore positions"
                : "Predicting " + completed + " / " + total + " chunks";
    }

    private Result generate(long token, long requestedSeed, ChunkPos chunk) {
        try {
            if (token != revision.get()) return null;
            if (generator != null && (generatorSeed != requestedSeed || generator.shouldRecycle())) releaseGenerator();
            if (generator == null) {
                generator = new VanillaOreGenerator(requestedSeed, scratchParent);
                generatorSeed = requestedSeed;
            }
            if (token != revision.get()) return null;
            return new Result(token, chunk.toLong(), generator.predict(chunk.x, chunk.z));
        } catch (Exception error) {
            if (token != revision.get()) return null;
            throw new java.util.concurrent.CompletionException(error);
        } finally {
            if (token != revision.get()) releaseGenerator();
        }
    }

    private void collect() {
        if (pending == null || !pending.isDone()) return;
        try {
            Result result = pending.join();
            if (result != null && result.revision == revision.get()) {
                cache.put(result.chunk, result.markers);
                while (cache.size() > MAX_CACHED_CHUNKS) cache.remove(cache.keySet().iterator().next());
            }
        } catch (RuntimeException error) {
            if (pendingRevision == revision.get()) {
                failed = true;
                status = "Prediction failed - Rescan to retry (see latest.log)";
                LOGGER.error("BlockESP could not generate vanilla ore predictions", error);
                worker.execute(this::releaseGenerator);
            }
        } finally { pending = null; }
    }

    private void publish(Minecraft client) {
        var settings = config.blockEsp;
        scanReceivedChunks(client.level, settings.radius);
        var found = new LinkedHashSet<OreMarker>();
        completed = 0;
        for (var entry : cache.entrySet()) {
            ChunkPos chunk = new ChunkPos(entry.getKey());
            if (Math.abs(chunk.x - centerX) > settings.radius || Math.abs(chunk.z - centerZ) > settings.radius) continue;
            completed++;
            // A refuted (or unreadable) seed predicts nothing; its positions are random noise.
            if (!seedValid || trust == SeedTrust.State.REFUTED) continue;
            for (var marker : entry.getValue()) {
                if (marker.kind() == OreMarker.Kind.EMERALD ? !settings.emerald : !settings.coal) continue;
                // Air in a received chunk is useful evidence of an already mined block. Solid substitutions
                // are deliberately retained: Anti-Xray may disguise an existing ore as stone/deepslate.
                if (client.level.hasChunkAt(marker.pos()) && client.level.getBlockState(marker.pos()).isAir()) continue;
                found.add(marker);
            }
        }
        // Ground truth: whatever the server sent is there regardless of seeds or simulation failures.
        for (var marker : liveMarkers) {
            if (marker.kind() == OreMarker.Kind.EMERALD ? settings.emerald : settings.coal) found.add(marker);
        }
        var sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparingDouble(marker -> marker.pos().distToCenterSqr(client.player.position())));
        markers = List.copyOf(sorted);
    }

    /**
     * Scans chunks already received from the server and re-scores seed trust against them. Only
     * observed ores inside chunks whose prediction has completed count as evidence: elsewhere a
     * "miss" would merely mean the simulation has not reached that chunk yet.
     */
    private void scanReceivedChunks(ClientLevel level, int radius) {
        var live = new ArrayList<OreMarker>();
        for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
            var chunk = level.getChunkSource().getChunk(centerX + dx, centerZ + dz, ChunkStatus.FULL, false);
            if (chunk != null) live.addAll(VanillaOreGenerator.findOres(chunk));
        }
        liveMarkers = List.copyOf(live);
        var predicted = new HashSet<OreMarker>();
        int observed = 0, matched = 0;
        for (var entry : cache.entrySet()) {
            ChunkPos chunk = new ChunkPos(entry.getKey());
            if (Math.abs(chunk.x - centerX) <= radius && Math.abs(chunk.z - centerZ) <= radius)
                predicted.addAll(entry.getValue());
        }
        for (var ore : liveMarkers) {
            long chunkKey = ChunkPos.asLong(ore.pos().getX() >> 4, ore.pos().getZ() >> 4);
            ChunkPos chunk = new ChunkPos(chunkKey);
            if (!cache.containsKey(chunkKey)
                    || Math.abs(chunk.x - centerX) > radius || Math.abs(chunk.z - centerZ) > radius) continue;
            observed++;
            if (predicted.contains(ore)) matched++;
        }
        observedOres = observed;
        matchedOres = matched;
        trust = SeedTrust.assess(matched, observed - matched);
    }

    static ChunkPos nextChunk(int centerX, int centerZ, int radius, java.util.Set<Long> cached) {
        ChunkPos nearest = null;
        int distance = Integer.MAX_VALUE;
        for (int z = -radius; z <= radius; z++) for (int x = -radius; x <= radius; x++) {
            int cx = centerX + x, cz = centerZ + z;
            if (Math.abs((long) cx) > 1_874_999 || Math.abs((long) cz) > 1_874_999 || cached.contains(ChunkPos.asLong(cx, cz))) continue;
            int squared = x * x + z * z;
            if (squared < distance) { nearest = new ChunkPos(cx, cz); distance = squared; }
        }
        return nearest;
    }

    /** User-requested refresh; unchanged generation inputs produce the same original predictions. */
    public void rescan() { if (!closed) { invalidate(); active = false; status = "Waiting to predict"; } }

    public void disconnect() { if (!closed) deactivate("Join a world to predict ores"); }

    private void deactivate(String message) {
        if (active) invalidate();
        active = false;
        lastLevel = null;
        liveMarkers = List.of();
        trust = SeedTrust.State.UNKNOWN;
        matchedOres = observedOres = 0;
        status = message;
    }

    private void invalidate() {
        revision.incrementAndGet();
        cache.clear();
        markers = List.of();
        liveMarkers = List.of();
        trust = SeedTrust.State.UNKNOWN;
        matchedOres = observedOres = 0;
        completed = total = 0;
        failed = false;
        worker.execute(this::releaseGenerator);
    }

    private void releaseGenerator() {
        if (generator != null) {
            generator.close();
            generator = null;
        }
    }

    @Override public void close() {
        if (closed) return;
        invalidate();
        closed = true;
        active = false;
        lastLevel = null;
        worker.shutdown();
        if (instance == this) instance = null;
    }

    private record Result(long revision, long chunk, List<OreMarker> markers) { }
}
