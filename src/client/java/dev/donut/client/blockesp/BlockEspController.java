package dev.donut.client.blockesp;

import dev.donut.client.config.BlockEspSettings;
import dev.donut.client.config.ClientConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-thread results and settings; a single worker exclusively owns the generation world.
 *
 * <p>Seed prediction is only as good as the server's agreement with vanilla generation. Some servers place ore with
 * the world seed in one region and with separate feature seeds in another, and nothing in the terrain reveals the
 * difference. Real ore that the client receives does: exposed ore is never disguised by anti-xray, so each one is
 * either an ore the prediction also places or an ore it does not. {@link AreaVerdicts} turns that evidence into a
 * per-area verdict, markers in rejected areas are hidden, and the verdicts are saved to disk.
 */
public final class BlockEspController implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/BlockESP");
    private static final int MAX_CACHED_CHUNKS = 512;
    private static final int MAX_VERIFY_CHUNKS_PER_TICK = 2;
    private static final int SEARCH_RETRY_MILLIS = 30_000;
    private static final int MINED_TEST_RADIUS = 96;
    private static final int MAX_SEEN_ORE = 200_000;
    private static BlockEspController instance;
    private final ClientConfig config;
    private final Path scratchParent;
    private final Path areasFile;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("Donut BlockESP generation").daemon(true).factory());
    private final AtomicLong revision = new AtomicLong();
    private final LinkedHashMap<Long, List<OreMarker>> cache = new LinkedHashMap<>();
    private CompletableFuture<Result> pending;
    private long pendingRevision;
    private ClientLevel lastLevel;
    private long seed;
    private boolean active, failed, closed;
    private int centerX, centerZ, completed, total, hiddenMarkers;
    private List<OreMarker> markers = List.of();
    private String status = "Disabled";
    // Accessed exclusively by worker tasks, including cleanup.
    private OrePredictorEngine engine;
    private long engineSeed;
    private String engineName = "";
    // Set on the worker when the seed simulation throws and the vanilla world takes over.
    private volatile boolean fellBack;
    private volatile boolean fallbackNoticePending;
    // Verification state, touched only from the client thread.
    private final AreaVerdicts verdicts = new AreaVerdicts();
    private final MinedEvidence mined = new MinedEvidence();
    private final Set<Long> realSeen = new HashSet<>();
    private final java.util.ArrayDeque<Long> verifyQueue = new java.util.ArrayDeque<>();
    private boolean verdictsLoaded;
    private int ticksSinceSave;
    private int ticksSinceMinedTest;
    // Nearest-emerald search.
    private final Set<Long> emeraldGone = ConcurrentHashMap.newKeySet();
    private final Set<Long> deepslateEmerald = ConcurrentHashMap.newKeySet();
    private volatile List<BlockPos> emeraldList = List.of();
    private volatile BlockPos nearestEmerald;
    private volatile boolean emeraldDeepslate;
    private volatile boolean searching;
    private volatile String searchState = "not searched yet";
    private BlockPos lastSearchFrom;
    private long lastSearchAt;
    private volatile NearestEmeraldSearch.Biomes cachedBiomes;
    private volatile long biomesSeed;

    public BlockEspController(ClientConfig config) {
        this(config, FabricLoader.getInstance().getGameDir().resolve(".cache/donut-blockesp"));
        instance = this;
    }

    public BlockEspController(ClientConfig config, Path scratchParent) {
        this.config = config;
        this.scratchParent = scratchParent;
        this.areasFile = FabricLoader.getInstance().getConfigDir().resolve("donut/blockesp-areas.txt");
    }

    public static BlockEspController get() { return instance; }

    public List<OreMarker> snapshot() { return markers; }

    public String status() { return status; }

    public int completedChunks() { return completed; }

    public int totalChunks() { return total; }

    /** Markers hidden because the area's real ore contradicts the seed. */
    public int hiddenMarkers() { return hiddenMarkers; }

    public String areasSummary() { return verdicts.summary(); }

    public String minedSummary() { return mined.summary(); }

    public AreaVerdicts verdicts() { return verdicts; }

    public MinedEvidence minedEvidence() { return mined; }

    public BlockPos nearestEmerald() { return nearestEmerald; }

    public boolean nearestEmeraldIsDeepslate() { return emeraldDeepslate; }

    public boolean searchingEmerald() { return searching; }

    public String emeraldSearchState() { return searchState; }

    /** Verdict for the area containing a position, used by the HUD and the marker labels. */
    public AreaVerdicts.Status areaStatus(BlockPos pos) {
        return verdicts.status(AreaVerdicts.key(false, pos.getX() >> 4, pos.getZ() >> 4));
    }

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
        long nextSeed;
        try { nextSeed = BlockEspSettings.parseSeed(settings.seed); }
        catch (IllegalArgumentException error) { deactivate(error.getMessage()); return; }
        // Switching engine mid-session rebuilds the prediction from the newly selected one.
        if (!active || client.level != lastLevel || nextSeed != seed
                || (engineName != null && !engineName.isEmpty() && !engineName.equals(engineLabel(settings.engine)))) {
            invalidate();
            lastLevel = client.level;
            seed = nextSeed;
            active = true;
        }
        loadVerdicts();
        var center = client.player.chunkPosition();
        centerX = center.x;
        centerZ = center.z;
        total = (settings.radius * 2 + 1) * (settings.radius * 2 + 1);
        publish(client);
        verify(client);
        updateNearest(client);
        if (fallbackNoticePending) {
            fallbackNoticePending = false;
            notice("Seed simulation failed here - switched to the vanilla world engine (see latest.log)");
        }
        if (++ticksSinceSave >= 200) saveVerdicts();
        if (failed) return;
        if (pending == null) {
            ChunkPos next = nextChunk(centerX, centerZ, settings.radius, cache.keySet());
            if (next != null) {
                long token = revision.get(), requestedSeed = seed;
                pendingRevision = token;
                pending = CompletableFuture.supplyAsync(() -> generate(token, requestedSeed, next), worker);
            }
        }
        status = completed == total ? "Ready - original ore positions" : "Predicting " + completed + " / " + total + " chunks";
    }

    private Result generate(long token, long requestedSeed, ChunkPos chunk) {
        try {
            if (token != revision.get()) return null;
            if (engine != null && engineSeed != requestedSeed) releaseEngine();
            if (engine == null) {
                engine = createEngine(requestedSeed);
                engineSeed = requestedSeed;
            }
            if (token != revision.get()) return null;
            return new Result(token, chunk.toLong(), predictChunk(chunk.x, chunk.z));
        } catch (Exception error) {
            if (token != revision.get()) return null;
            throw new java.util.concurrent.CompletionException(error);
        } finally {
            if (token != revision.get()) releaseEngine();
        }
    }

    /**
     * Prediction with a fallback: if the seed simulation throws (a mapping or API change, an unsupported registry
     * call), the vanilla world engine takes over for the rest of the session instead of leaving the module broken.
     * Both are worker-thread only.
     */
    private List<OreMarker> predictChunk(int chunkX, int chunkZ) {
        try {
            return engine.predict(chunkX, chunkZ);
        } catch (RuntimeException | LinkageError error) {
            if (!(engine instanceof OrePredictorEngine.SeedTerrainAdapter)) throw error;
            LOGGER.error("BlockESP seed simulation failed; switching to the vanilla world engine", error);
            fallbackToVanillaWorld();
            return engine.predict(chunkX, chunkZ);
        }
    }

    /** Predicted deepslate emeralds for the search, with the same fallback as chunk prediction. */
    private List<BlockPos> predictEmerald(int chunkX, int chunkZ) {
        try {
            return engine.deepslateEmerald(chunkX, chunkZ);
        } catch (RuntimeException | LinkageError error) {
            if (!(engine instanceof OrePredictorEngine.SeedTerrainAdapter)) throw error;
            LOGGER.error("BlockESP seed simulation failed during the emerald search; switching to the vanilla world engine", error);
            fallbackToVanillaWorld();
            return engine.deepslateEmerald(chunkX, chunkZ);
        }
    }

    private void fallbackToVanillaWorld() {
        long requestedSeed = engineSeed;
        releaseEngine();
        try {
            engine = OrePredictorEngine.vanillaWorld(requestedSeed, scratchParent);
            engineSeed = requestedSeed;
            engineName = "Vanilla world";
            fellBack = true;
            fallbackNoticePending = true;
        } catch (Exception | Error fatal) {
            throw new IllegalStateException("BlockESP could not start the vanilla world engine", fatal);
        }
    }

    /**
     * Builds the configured engine. The seed simulation runs the same vanilla generator per chunk without a server,
     * while the vanilla world is the game's own generation; both were verified against fresh vanilla chunks.
     */
    private OrePredictorEngine createEngine(long requestedSeed) throws Exception {
        String selected = config.blockEsp.engine;
        // An unexpected value is never written back here: this runs on the worker, while the config belongs to the
        // client thread. Anything that is not the vanilla world is the seed simulation.
        if (BlockEspSettings.ENGINE_WORLD.equals(selected)) {
            engineName = "Vanilla world";
            return OrePredictorEngine.vanillaWorld(requestedSeed, scratchParent);
        }
        engineName = "Seed simulation";
        return OrePredictorEngine.seedTerrain(requestedSeed);
    }

    /** Which engine produced the current markers, plus a note when the simulation fell back. */
    public String engineName() { return fellBack ? engineName + " (fallback)" : engineName; }

    /** Label of the configured engine, without building it. */
    public static String engineLabel(String engine) {
        return BlockEspSettings.ENGINE_SIMULATION.equals(engine) ? "Seed simulation" : "Vanilla world";
    }

    private void collect() {
        if (pending == null || !pending.isDone()) return;
        try {
            Result result = pending.join();
            if (result != null && result.revision == revision.get()) {
                cache.put(result.chunk, result.markers);
                if (!verifyQueue.contains(result.chunk)) verifyQueue.add(result.chunk);
                while (cache.size() > MAX_CACHED_CHUNKS) cache.remove(cache.keySet().iterator().next());
            }
        } catch (RuntimeException error) {
            if (pendingRevision == revision.get()) {
                failed = true;
                status = "Prediction failed - Rescan to retry (see latest.log)";
                LOGGER.error("BlockESP could not generate vanilla ore predictions", error);
                worker.execute(this::releaseEngine);
            }
        } finally { pending = null; }
    }

    private void publish(Minecraft client) {
        var settings = config.blockEsp;
        var found = new ArrayList<OreMarker>();
        completed = 0;
        hiddenMarkers = 0;
        for (var entry : cache.entrySet()) {
            ChunkPos chunk = new ChunkPos(entry.getKey());
            if (Math.abs(chunk.x - centerX) > settings.radius || Math.abs(chunk.z - centerZ) > settings.radius) continue;
            completed++;
            var verdict = verdicts.status(AreaVerdicts.key(false, chunk.x, chunk.z));
            // Rejected areas are hidden: the server placed ore there with different feature seeds, so their markers
            // would be wrong everywhere. Confirmed-only mode also hides areas that no real ore has verified yet.
            if (!settings.showUnmatched && verdict == AreaVerdicts.Status.UNMATCHED) {
                hiddenMarkers += entry.getValue().size();
                continue;
            }
            if (settings.confirmedOnly && verdict != AreaVerdicts.Status.CONFIRMED) {
                hiddenMarkers += entry.getValue().size();
                continue;
            }
            for (var marker : entry.getValue()) {
                if (marker.kind() == OreMarker.Kind.EMERALD ? !settings.emerald : !settings.coal) continue;
                // Air in a received chunk is useful evidence of an already mined block. Solid substitutions
                // are deliberately retained: Anti-Xray may disguise an existing ore as stone/deepslate.
                if (client.level.hasChunkAt(marker.pos()) && client.level.getBlockState(marker.pos()).isAir()) {
                    // Remember removed emeralds so the nearest-emerald card does not keep pointing at them.
                    if (marker.kind() == OreMarker.Kind.EMERALD) emeraldGone.add(marker.pos().asLong());
                    continue;
                }
                found.add(marker);
            }
        }
        found.sort(Comparator.comparingDouble(marker -> marker.pos().distToCenterSqr(client.player.position())));
        markers = List.copyOf(found);
    }

    // ---- Area verification --------------------------------------------------------------------------------------

    /** Real ore the client received decides whether the seed actually describes this area. */
    private void verify(Minecraft client) {
        var settings = config.blockEsp;
        if (!settings.verifyAreas && !settings.minedTest) return;
        ClientLevel level = client.level;
        if (level == null) return;
        if (settings.verifyAreas) {
            // One pass per predicted chunk, oldest first; a chunk the client has not received is retried later.
            int budget = MAX_VERIFY_CHUNKS_PER_TICK;
            int deferred = verifyQueue.size();
            while (budget > 0 && deferred-- > 0 && !verifyQueue.isEmpty()) {
                long key = verifyQueue.poll();
                ChunkPos chunk = new ChunkPos(key);
                if (!cache.containsKey(key)) continue;
                if (!level.hasChunk(chunk.x, chunk.z)) {
                    verifyQueue.add(key);
                    continue;
                }
                budget--;
                verifyArea(level, chunk);
            }
        }
        // Sampling every marker in range is cheap per chunk but not per tick; a second is plenty.
        if (settings.minedTest && ++ticksSinceMinedTest >= 20) {
            ticksSinceMinedTest = 0;
        fellBack = false;
        fallbackNoticePending = false;
            minedTest(level);
        }
    }

    private void verifyArea(ClientLevel level, ChunkPos chunk) {
        // A vein can cross a chunk border, so a miss only counts once the whole 3x3 neighbourhood is predicted.
        Set<Long> predicted = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                var markers = cache.get(ChunkPos.asLong(chunk.x + dx, chunk.z + dz));
                if (markers == null) return;
                for (var marker : markers) predicted.add(marker.pos().asLong());
            }
        }
        String area = AreaVerdicts.key(false, chunk.x, chunk.z);
        AreaVerdicts.Status before = verdicts.status(area);
        LevelChunk levelChunk = level.getChunk(chunk.x, chunk.z);
        LevelChunkSection[] sections = levelChunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(state -> OreMarker.kind(state) != null)) continue;
            int baseY = levelChunk.getSectionYFromSectionIndex(index) * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (OreMarker.kind(section.getBlockState(x, y, z)) == null) continue;
                        var pos = new BlockPos(chunk.getMinBlockX() + x, baseY + y, chunk.getMinBlockZ() + z);
                        if (!touchesAir(level, pos)) continue;
                        // Re-seeing a block must not count twice; the cap keeps a long session bounded and only
                        // costs re-checking when it is reached.
                        if (realSeen.size() > MAX_SEEN_ORE) realSeen.clear();
                        if (!realSeen.add(pos.asLong())) continue;
                        verdicts.record(area, predicted.contains(pos.asLong()), 1);
                    }
                }
            }
        }
        AreaVerdicts.Status after = verdicts.status(area);
        if (after != before) {
            int centerBlockX = AreaVerdicts.center(chunk.x >> 3);
            int centerBlockZ = AreaVerdicts.center(chunk.z >> 3);
            int hits = verdicts.hits(area), misses = verdicts.misses(area);
            if (after == AreaVerdicts.Status.CONFIRMED) {
                notice("Area around " + centerBlockX + ", " + centerBlockZ + " matches the seed (" + hits + " of "
                        + (hits + misses) + " real ores predicted) - markers here are reliable");
            } else if (after == AreaVerdicts.Status.UNMATCHED) {
                notice("Area around " + centerBlockX + ", " + centerBlockZ + " does NOT match the seed (" + hits
                        + " of " + (hits + misses) + " real ores predicted) - the server placed ore differently here,"
                        + " markers hidden");
            }
        }
    }

    private static boolean touchesAir(ClientLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(pos.relative(direction)).isAir()) return true;
        }
        return false;
    }

    /**
     * Mined test: predicted blocks that are already air or liquid against control spots beside them. Air at an ore
     * position cannot come from generation, so those blocks were dug out - the veins the module shows are real.
     */
    private void minedTest(ClientLevel level) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        var center = player.blockPosition();
        int radius = Math.min(config.blockEsp.radius * 16, MINED_TEST_RADIUS);
        for (var entry : cache.entrySet()) {
            ChunkPos chunk = new ChunkPos(entry.getKey());
            if (Math.abs(chunk.x - centerX) > config.blockEsp.radius || Math.abs(chunk.z - centerZ) > config.blockEsp.radius) continue;
            for (var marker : entry.getValue()) {
                long packed = marker.pos().asLong();
                // Only nearby markers are worth checking: the client has real blocks there, and the sample sets keep
                // repeated passes cheap because sampled positions are skipped before any block state is read.
                if (mined.sawPredicted(packed)) continue;
                if (Math.abs(marker.pos().getX() - center.getX()) > radius
                        || Math.abs(marker.pos().getZ() - center.getZ()) > radius) continue;
                if (!level.hasChunkAt(marker.pos())) continue;
                if (!mined.observePredicted(packed, gone(level.getBlockState(marker.pos())))) continue;
                for (long control : MinedEvidence.controls(packed)) {
                    if (mined.sawControl(control)) continue;
                    var pos = new BlockPos(MinedEvidence.x(control), MinedEvidence.y(control), MinedEvidence.z(control));
                    if (!level.hasChunkAt(pos)) continue;
                    mined.observeControl(control, gone(level.getBlockState(pos)));
                }
            }
        }
    }

    /** Air or a liquid: ore never generates in either, so the block was removed after generation. */
    private static boolean gone(BlockState state) {
        return state.isAir() || !state.getFluidState().isEmpty();
    }

    // ---- Nearest emerald card -----------------------------------------------------------------------------------

    /**
     * Picks the closest predicted deepslate emerald and refreshes the background search when needed. The search
     * samples seeded mountain biomes out to the configured range and only generates the chunks around the closest
     * candidates, so it reaches far beyond the loaded area without generating everything in between.
     */
    private void updateNearest(Minecraft client) {
        var settings = config.blockEsp;
        if (!settings.nearestEmerald || !settings.emerald) {
            nearestEmerald = null;
            return;
        }
        BlockPos pick = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : emeraldList) {
            if (client.level.hasChunkAt(pos) && gone(client.level.getBlockState(pos))) {
                emeraldGone.add(pos.asLong());
                continue;
            }
            if (verdicts.status(AreaVerdicts.key(false, pos.getX() >> 4, pos.getZ() >> 4)) == AreaVerdicts.Status.UNMATCHED) continue;
            double distance = pos.distToCenterSqr(client.player.position());
            if (distance < best) { best = distance; pick = pos; }
        }
        nearestEmerald = pick;
        emeraldDeepslate = pick != null && deepslateEmerald.contains(pick.asLong());
        if (searching) return;
        BlockPos from = client.player.blockPosition();
        long now = System.currentTimeMillis();
        int moved = lastSearchFrom == null ? Integer.MAX_VALUE : Math.abs(from.getX() - lastSearchFrom.getX())
                + Math.abs(from.getZ() - lastSearchFrom.getZ());
        // Only search once the near-field prediction finished, so the worker is not taken away from it.
        boolean search = completed >= total && total > 0 && (lastSearchFrom == null || moved > 500
                || (pick == null && now - lastSearchAt > SEARCH_RETRY_MILLIS && (moved > 64 || !emeraldList.isEmpty())));
        if (!search) return;
        lastSearchFrom = from;
        lastSearchAt = now;
        startEmeraldSearch(from, settings.emeraldSearchRange);
    }

    private void startEmeraldSearch(BlockPos from, int range) {
        long token = revision.get();
        long requestedSeed = seed;
        int rangeBlocks = NearestEmeraldSearch.clampRange(range);
        Set<String> unmatched = verdicts.keysWith(AreaVerdicts.Status.UNMATCHED);
        Set<Long> removed = new HashSet<>(emeraldGone);
        searching = true;
        searchState = "searching...";
        worker.submit(() -> {
            try {
                var biomes = cachedBiomes;
                if (biomes == null || biomesSeed != requestedSeed) {
                    biomes = EmeraldBiomes.create(VanillaRegistries.createLookup(), requestedSeed);
                    cachedBiomes = biomes;
                    biomesSeed = requestedSeed;
                }
                if (engine == null || engineSeed != requestedSeed) {
                    if (engine != null) releaseEngine();
                    engine = createEngine(requestedSeed);
                    engineSeed = requestedSeed;
                }
                var found = NearestEmeraldSearch.search(biomes, this::predictEmerald,
                        from.getX(), from.getY(), from.getZ(), rangeBlocks, unmatched, removed, null);
                if (token != revision.get()) return;
                // Both engines only report the deepslate variants, so every result is deepslate emerald.
                for (BlockPos pos : found) deepslateEmerald.add(pos.asLong());
                emeraldList = List.copyOf(found);
                searchState = found.isEmpty() ? "no emerald within " + rangeBlocks + " blocks" : "";
            } catch (Throwable error) {
                searchState = "search failed: " + error.getClass().getSimpleName();
                LOGGER.warn("BlockESP nearest emerald search failed", error);
            } finally {
                searching = false;
            }
        });
    }

    // ---- Area verdict persistence --------------------------------------------------------------------------------

    private void loadVerdicts() {
        if (verdictsLoaded) return;
        verdictsLoaded = true;
        verdicts.load(areasFile);
    }

    private void saveVerdicts() {
        ticksSinceSave = 0;
        if (!verdictsLoaded || !verdicts.isDirty()) return;
        verdicts.save(areasFile);
    }

    private void notice(String message) {
        if (config.blockEsp.chatNotices) {
            var player = Minecraft.getInstance().player;
            if (player != null) player.displayClientMessage(Component.literal("[BlockESP] " + message), false);
        }
        LOGGER.info("BlockESP: {}", message);
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
    public void rescan() {
        if (!closed) {
            invalidate();
            active = false;
            status = "Waiting to predict";
        }
    }

    /** Forgets learned area verdicts after the user confirms; used by the settings screen. */
    public void clearAreas() {
        verdicts.clear();
        // Forget which real ore blocks were already counted, so every area can be checked again.
        realSeen.clear();
        verifyQueue.clear();
        saveVerdicts();
        notice("Area evidence cleared - every area starts unverified again");
    }

    public void disconnect() { if (!closed) deactivate("Join a world to predict ores"); }

    private void deactivate(String message) {
        if (active) invalidate();
        active = false;
        lastLevel = null;
        status = message;
    }

    private void invalidate() {
        revision.incrementAndGet();
        cache.clear();
        markers = List.of();
        completed = total = 0;
        failed = false;
        hiddenMarkers = 0;
        verifyQueue.clear();
        realSeen.clear();
        mined.reset();
        ticksSinceMinedTest = 0;
        fellBack = false;
        fallbackNoticePending = false;
        emeraldList = List.of();
        emeraldGone.clear();
        deepslateEmerald.clear();
        nearestEmerald = null;
        searchState = "not searched yet";
        lastSearchFrom = null;
        saveVerdicts();
        worker.execute(this::releaseEngine);
    }

    private void releaseEngine() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    @Override
    public void close() {
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
