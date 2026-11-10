package dev.donut.client.blockesp;

import java.lang.reflect.InvocationHandler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

// Rebuilds a chunk's underground from the seed with the game's own generator, then replays every feature the
// generator runs before (and including) coal and emerald, the way the server did:
//  1. terrain from noise: stone, deepslate, noise caves, underground water and lava (fillFromNoise)
//  2. the surface layer (grass, dirt, sand...) from the surface rules
//  3. carved caves and canyons (same seeds and order as the server)
//  4. features: lakes, geodes, dungeons..., then the ore blobs in order (dirt, gravel, granite..., coal, ...)
// The result is the ore the server generated, independent of what the client can see. Works for the
// Overworld (Ore Predictor) and the Nether (Ancient Debris Finder); shared by both modules.
// Structures (villages, mineshafts...) are left out.
public final class SeedTerrain {
    final int MIN_Y;
    final int HEIGHT;

    public record Ore(BlockPos pos, Block block) {}

    private record Base(LevelChunkSection[] sections, int[] oceanFloor) {}

    private record Feature(PlacedFeature placed, int index) {}

    private final long seed;
    private final MultiNoiseBiomeSource source;
    private final RandomState randomState;
    private final NoiseBasedChunkGenerator generator;
    private final Holder<NoiseGeneratorSettings> settings;
    private final BiomeManager zoom;
    private final RegistryAccess registryAccess;
    private final LevelHeightAccessor heightAccessor;
    private final PalettedContainerFactory containers;
    private final StructureManager structures;
    private final Registry<Biome> biomeRegistry;
    private final List<FeatureSorter.StepFeatureData> steps;
    private final int oreStep;
    private final int lastIndex;
    // Underground structures placed before the ores (mineshafts), from the seed like the server's structure step.
    private final List<net.minecraft.world.level.levelgen.structure.StructureSet> undergroundSets = new ArrayList<>();
    private final Map<Structure, Integer> structureIndex = new java.util.HashMap<>();
    private final Map<Long, List<StructureStart>> starts = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, List<StructureStart>> eldest) {
            return size() > 6000;
        }
    };
    public int structurePieces;
    // Failures per feature (a feature that needs something the stand-in world cannot give is skipped).
    public final Map<String, Integer> skipped = java.util.Collections.synchronizedMap(new TreeMap<>());
    private final Map<Long, Base> bases = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Base> eldest) {
            return size() > 160;
        }
    };

    public SeedTerrain(long seed, int minY, int height, MultiNoiseBiomeSource source, RandomState randomState, NoiseBasedChunkGenerator generator,
            Holder<NoiseGeneratorSettings> settings, net.minecraft.core.HolderLookup.Provider lookup, RegistryAccess registryAccess,
            PlacedFeature... lastFeatures) {
        this.MIN_Y = minY;
        this.HEIGHT = height;
        this.heightAccessor = LevelHeightAccessor.create(minY, height);
        this.seed = seed;
        this.source = source;
        this.randomState = randomState;
        this.generator = generator;
        this.settings = settings;
        this.zoom = new BiomeManager((qx, qy, qz) -> source.getNoiseBiome(qx, qy, qz, randomState.sampler()), BiomeManager.obfuscateSeed(seed));
        IdMapper<Holder<Biome>> biomeIds = new IdMapper<>();
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(biomeIds::add);
        Holder<Biome> plains = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        this.containers = new PalettedContainerFactory(Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
                Blocks.AIR.defaultBlockState(), null, Strategy.createForBiomes(biomeIds), plains, null);
        // No structures: nothing bends the terrain around villages, cities etc.
        this.structures = new StructureManager((net.minecraft.world.level.LevelAccessor) null, (RandomState) null, 0L) {
            @Override
            public List<StructureStart> startsForStructure(ChunkPos pos, java.util.function.Predicate<Structure> filter) {
                return List.of();
            }
        };
        this.biomeRegistry = stub(Registry.class);
        // Surface rules ask for the biome registry while carving but never read it; hand back the stub.
        Registry<Biome> biomes = this.biomeRegistry;
        this.registryAccess = registryAccess != null ? registryAccess : (RegistryAccess) java.lang.reflect.Proxy.newProxyInstance(
                RegistryAccess.class.getClassLoader(), new Class<?>[] {RegistryAccess.class}, (proxy, method, args) -> {
                    if (method.getName().equals("lookupOrThrow")) {
                        return biomes;
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException("RegistryAccess." + method.getName());
                });
        this.steps = FeatureSorter.buildFeaturesPerStep(List.copyOf(source.possibleBiomes()),
                biome -> biome.value().getGenerationSettings().features(), true);
        int step = -1;
        int index = -1;
        // Replay up to the latest of the wanted features (in generation order).
        for (PlacedFeature lastFeature : lastFeatures) {
            for (int s = 0; s < steps.size(); s++) {
                if (steps.get(s).features().contains(lastFeature)) {
                    int i = steps.get(s).indexMapping().applyAsInt(lastFeature);
                    if (s > step || (s == step && i > index)) {
                        step = s;
                        index = i;
                    }
                }
            }
        }
        this.oreStep = step;
        this.lastIndex = index;
        var setLookup = lookup.lookupOrThrow(Registries.STRUCTURE_SET);
        var mineshafts = setLookup.getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructureSets.MINESHAFTS);
        // Only the sets we place; the full "normal" setup would read biome tags this copy of the registries lacks.
        undergroundSets.add(mineshafts.value());
        Map<Integer, Integer> perStep = new java.util.HashMap<>();
        lookup.lookupOrThrow(Registries.STRUCTURE).listElements().forEach(holder -> {
            int structureStep = holder.value().step().ordinal();
            structureIndex.put(holder.value(), perStep.merge(structureStep, 1, Integer::sum) - 1);
        });
    }

    // Structure starts in one chunk, chosen the way ChunkGenerator.createStructures does.
    private synchronized List<StructureStart> startsAt(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        List<StructureStart> found = starts.get(key);
        if (found != null) {
            return found;
        }
        found = new ArrayList<>();
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        for (var set : undergroundSets) {
            // Random-spread placement (mineshafts): the chunk must be its cell's pick and pass the frequency roll.
            if (!(set.placement() instanceof net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement spread)
                    || !spread.getPotentialStructureChunk(seed, chunkX, chunkZ).equals(pos)
                    || !spread.applyAdditionalChunkRestrictions(chunkX, chunkZ, seed)) {
                continue;
            }
            var entries = set.structures();
            if (entries.size() == 1) {
                StructureStart start = tryStart(entries.get(0), pos);
                if (start != null) {
                    found.add(start);
                }
                continue;
            }
            List<net.minecraft.world.level.levelgen.structure.StructureSet.StructureSelectionEntry> left = new ArrayList<>(entries);
            WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
            random.setLargeFeatureSeed(seed, chunkX, chunkZ);
            int total = 0;
            for (var entry : left) {
                total += entry.weight();
            }
            while (!left.isEmpty()) {
                int roll = random.nextInt(total);
                int k = 0;
                for (var entry : left) {
                    roll -= entry.weight();
                    if (roll < 0) {
                        break;
                    }
                    k++;
                }
                var entry = left.get(k);
                StructureStart start = tryStart(entry, pos);
                if (start != null) {
                    found.add(start);
                    break;
                }
                left.remove(k);
                total -= entry.weight();
            }
        }
        starts.put(key, found);
        return found;
    }

    // A vanilla biome tag read from the game's built-in data (e.g. has_structure/mineshaft), nested tags expanded.
    private static final Map<String, Set<String>> BIOME_TAGS = new java.util.concurrent.ConcurrentHashMap<>();

    static Set<String> biomeTag(String name) {
        Set<String> cached = BIOME_TAGS.get(name);
        if (cached != null) {
            return cached;
        }
        Set<String> out = new HashSet<>();
        String path = "data/minecraft/tags/worldgen/biome/" + name + ".json";
        try (var in = Biome.class.getClassLoader().getResourceAsStream(path)) {
            if (in != null) {
                var json = com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject();
                for (var value : json.getAsJsonArray("values")) {
                    String id = value.isJsonObject() ? value.getAsJsonObject().get("id").getAsString() : value.getAsString();
                    if (id.startsWith("#")) {
                        out.addAll(biomeTag(id.substring(1).replace("minecraft:", "")));
                    } else {
                        out.add(id.contains(":") ? id : "minecraft:" + id);
                    }
                }
            }
        } catch (java.io.IOException ignored) {
        }
        BIOME_TAGS.put(name, out);
        return out;
    }

    private StructureStart tryStart(net.minecraft.world.level.levelgen.structure.StructureSet.StructureSelectionEntry entry, ChunkPos pos) {
        Structure structure = entry.structure().value();
        try {
            String name = entry.structure().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
            Set<String> allowed = biomeTag("has_structure/" + name);
            StructureStart start = structure.generate(entry.structure(), net.minecraft.world.level.Level.OVERWORLD, registryAccess, generator,
                    source, randomState, null, seed, pos, 0, heightAccessor,
                    biome -> biome.unwrapKey().map(k -> allowed.contains(k.identifier().toString())).orElse(false));
            return start.isValid() ? start : null;
        } catch (Throwable e) {
            synchronized (skipped) {
                skipped.merge("structure start (" + e + ")", 1, Integer::sum);
            }
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "stub " + type.getSimpleName();
                default:
                    throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName());
            }
        });
    }

    // ---- 1-3: base terrain ----

    private synchronized Base base(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        Base base = bases.get(key);
        if (base != null) {
            return base;
        }
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY, heightAccessor, containers, null);
        chunk.fillBiomesFromNoise(source, randomState.sampler());
        generator.fillFromNoise(Blender.empty(), randomState, structures, chunk).join();
        int[] floor = new int[256];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                floor[x * 16 + z] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) + 1;
            }
        }
        generator.buildSurface(chunk, new WorldGenerationContext(generator, chunk), randomState, structures, zoom, biomeRegistry,
                Blender.empty());
        carve(chunk);
        base = new Base(chunk.getSections(), floor);
        bases.put(key, base);
        return base;
    }

    // Same as NoiseBasedChunkGenerator.applyCarvers: carvers starting in the 17x17 chunks around carve into this one.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void carve(ProtoChunk chunk) {
        ChunkPos pos = chunk.getPos();
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        NoiseChunk noise = chunk.getOrCreateNoiseChunk(c -> {
            throw new IllegalStateException("no noise chunk");
        });
        var aquifer = noise.aquifer();
        CarvingContext context = new CarvingContext(generator, registryAccess, chunk.getHeightAccessorForGeneration(), noise, randomState,
                settings.value().surfaceRule());
        var mask = chunk.getOrCreateCarvingMask();
        java.util.function.Function<BlockPos, Holder<Biome>> biomeAt = zoom::getBiome;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                ChunkPos from = new ChunkPos(pos.x + dx, pos.z + dz);
                BiomeGenerationSettings biome = generator.getBiomeGenerationSettings(source.getNoiseBiome(
                        QuartPos.fromBlock(from.getMinBlockX()), 0, QuartPos.fromBlock(from.getMinBlockZ()), randomState.sampler()));
                int k = 0;
                for (Holder carverHolder : biome.getCarvers()) {
                    ConfiguredWorldCarver carver = (ConfiguredWorldCarver) carverHolder.value();
                    random.setLargeFeatureSeed(seed + k, from.x, from.z);
                    if (carver.isStartChunk(random)) {
                        carver.carve(context, chunk, biomeAt, random, aquifer, from, mask);
                    }
                    k++;
                }
            }
        }
    }

    // ---- 4: features ----

    // Coal and emerald the server placed while decorating this chunk (they can reach into the chunks around it).
    public List<Ore> predict(int chunkX, int chunkZ, Set<Block> wanted) {
        return predict(chunkX, chunkZ, wanted, null);
    }

    // controls (optional): for each predicted block, one nearby block that was buried solid stone in the simulation
    // (not air, not fluid, not the ore, no air next to it): the kind of spot the ore could have been in.
    public List<Ore> predict(int chunkX, int chunkZ, Set<Block> wanted, List<BlockPos> controls) {
        ProtoChunk[] chunks = new ProtoChunk[9];
        int[][] floors = new int[9][];
        Set<Holder<Biome>> biomes = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Base base = base(chunkX + dx, chunkZ + dz);
                ProtoChunk chunk = new ProtoChunk(new ChunkPos(chunkX + dx, chunkZ + dz), UpgradeData.EMPTY, heightAccessor, containers, null);
                LevelChunkSection[] target = chunk.getSections();
                for (int i = 0; i < target.length; i++) {
                    target[i] = base.sections()[i].copy();
                    target[i].getBiomes().getAll(biomes::add);
                }
                chunks[(dx + 1) * 3 + (dz + 1)] = chunk;
                floors[(dx + 1) * 3 + (dz + 1)] = base.oceanFloor();
            }
        }
        WorldGenLevel level = level(chunkX, chunkZ, chunks, floors);
        net.minecraft.world.level.levelgen.structure.BoundingBox box = new net.minecraft.world.level.levelgen.structure.BoundingBox(
                chunkX * 16, MIN_Y, chunkZ * 16, chunkX * 16 + 15, MIN_Y + HEIGHT - 1, chunkZ * 16 + 15);
        List<StructureStart> touching = new ArrayList<>();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                for (StructureStart start : startsAt(chunkX + dx, chunkZ + dz)) {
                    if (start.getBoundingBox().intersects(box)) {
                        touching.add(start);
                    }
                }
            }
        }
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long decoration = random.setDecorationSeed(seed, chunkX * 16, chunkZ * 16);
        BlockPos origin = new BlockPos(chunkX * 16, MIN_Y, chunkZ * 16);
        for (int s = 0; s <= oreStep; s++) {
            for (StructureStart start : touching) {
                Structure structure = start.getStructure();
                if (structure.step().ordinal() != s) {
                    continue;
                }
                random.setFeatureSeed(decoration, structureIndex.getOrDefault(structure, 0), s);
                List<net.minecraft.world.level.levelgen.structure.StructurePiece> pieces = start.getPieces();
                var first = pieces.get(0).getBoundingBox();
                BlockPos center = first.getCenter();
                BlockPos pivot = new BlockPos(center.getX(), first.minY(), center.getZ());
                for (var piece : pieces) {
                    if (!piece.getBoundingBox().intersects(box)) {
                        continue;
                    }
                    try {
                        piece.postProcess(level, structures, generator, random, box, new ChunkPos(chunkX, chunkZ), pivot);
                        structurePieces++;
                    } catch (Throwable e) {
                        skipped.merge("structure piece " + piece.getClass().getSimpleName() + " (" + e.getClass().getSimpleName() + ": "
                                + e.getMessage() + ")", 1, Integer::sum);
                        if (System.getProperty("orepredictor.debug") != null && skipped.size() < 4) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            FeatureSorter.StepFeatureData step = steps.get(s);
            java.util.TreeSet<Integer> indices = new java.util.TreeSet<>();
            for (Holder<Biome> biome : biomes) {
                var lists = biome.value().getGenerationSettings().features();
                if (s < lists.size()) {
                    for (Holder<PlacedFeature> feature : lists.get(s)) {
                        indices.add(step.indexMapping().applyAsInt(feature.value()));
                    }
                }
            }
            for (int index : indices) {
                if (s == oreStep && index > lastIndex) {
                    break;
                }
                PlacedFeature feature = step.features().get(index);
                random.setFeatureSeed(decoration, index, s);
                try {
                    feature.placeWithBiomeCheck(level, generator, random, origin);
                } catch (Throwable e) {
                    String name = feature.feature().unwrapKey().map(k -> k.identifier().getPath()).orElse("?") + " (" + e.getClass().getSimpleName()
                            + ": " + e.getMessage() + ")";
                    skipped.merge(name, 1, Integer::sum);
                }
            }
        }
        List<Ore> out = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 9; i++) {
            ProtoChunk chunk = chunks[i];
            LevelChunkSection[] sections = chunk.getSections();
            for (int si = 0; si < sections.length; si++) {
                LevelChunkSection section = sections[si];
                if (!section.maybeHas(state -> wanted.contains(state.getBlock()))) {
                    continue;
                }
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            Block block = section.getBlockState(x, y, z).getBlock();
                            if (wanted.contains(block)) {
                                out.add(new Ore(new BlockPos(chunk.getPos().getMinBlockX() + x, MIN_Y + si * 16 + y,
                                        chunk.getPos().getMinBlockZ() + z), block));
                            }
                        }
                    }
                }
            }
        }
        if (controls != null) {
            int[][] offsets = {{5, 0, 3}, {-5, 0, -3}, {3, 0, -5}, {-3, 0, 5}, {6, 0, 0}, {0, 0, 6}};
            for (Ore ore : out) {
                for (int[] o : offsets) {
                    BlockPos c = ore.pos().offset(o[0], o[1], o[2]);
                    if (buriedStone(c, chunkX, chunkZ, chunks, wanted)) {
                        controls.add(c);
                        break;
                    }
                }
            }
        }
        return out;
    }

    // Simulated ground height per column (first free Y above solid ground, before trees), index x * 16 + z.
    public int[] floor(int chunkX, int chunkZ) {
        return base(chunkX, chunkZ).oceanFloor();
    }

    // Test helper: the block at pos in the base terrain, and the ocean-floor height there.
    public String debugBlocks(int chunkX, int chunkZ, BlockPos pos) {
        Base base = base(pos.getX() >> 4, pos.getZ() >> 4);
        int si = (pos.getY() - MIN_Y) >> 4;
        return "base block " + base.sections()[si].getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15)
                + ", floor " + base.oceanFloor()[(pos.getX() & 15) * 16 + (pos.getZ() & 15)];
    }

    // A stand-in for the server's world-gen region: the 3x3 chunks being decorated.
    private WorldGenLevel level(int chunkX, int chunkZ, ProtoChunk[] chunks, int[][] floors) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "getMinY":
                    return MIN_Y;
                case "getHeight":
                    if (args == null || args.length == 0) {
                        return HEIGHT;
                    }
                    return height((Heightmap.Types) args[0], (Integer) args[1], (Integer) args[2], chunkX, chunkZ, chunks, floors);
                case "getHeightmapPos": {
                    BlockPos p = (BlockPos) args[1];
                    return new BlockPos(p.getX(), height((Heightmap.Types) args[0], p.getX(), p.getZ(), chunkX, chunkZ, chunks, floors), p.getZ());
                }
                case "getBlockState": {
                    ProtoChunk chunk = chunkAt(((BlockPos) args[0]), chunkX, chunkZ, chunks);
                    return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState((BlockPos) args[0]);
                }
                case "getFluidState": {
                    ProtoChunk chunk = chunkAt(((BlockPos) args[0]), chunkX, chunkZ, chunks);
                    return chunk == null ? Blocks.AIR.defaultBlockState().getFluidState() : chunk.getFluidState((BlockPos) args[0]);
                }
                case "isStateAtPosition": {
                    ProtoChunk chunk = chunkAt(((BlockPos) args[0]), chunkX, chunkZ, chunks);
                    BlockState state = chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState((BlockPos) args[0]);
                    return ((java.util.function.Predicate<BlockState>) args[1]).test(state);
                }
                case "isFluidAtPosition": {
                    ProtoChunk chunk = chunkAt(((BlockPos) args[0]), chunkX, chunkZ, chunks);
                    var fluid = chunk == null ? Blocks.AIR.defaultBlockState().getFluidState() : chunk.getFluidState((BlockPos) args[0]);
                    return ((java.util.function.Predicate<net.minecraft.world.level.material.FluidState>) args[1]).test(fluid);
                }
                case "setBlock": {
                    ProtoChunk chunk = chunkAt(((BlockPos) args[0]), chunkX, chunkZ, chunks);
                    if (chunk == null) {
                        return false;
                    }
                    chunk.setBlockState((BlockPos) args[0], (BlockState) args[1]);
                    return true;
                }
                case "removeBlock": {
                    ProtoChunk chunk = chunkAt(((BlockPos) args[0]), chunkX, chunkZ, chunks);
                    if (chunk != null) {
                        chunk.setBlockState((BlockPos) args[0], Blocks.AIR.defaultBlockState());
                    }
                    return chunk != null;
                }
                case "getChunk": {
                    int x;
                    int z;
                    if (args[0] instanceof BlockPos p) {
                        x = p.getX() >> 4;
                        z = p.getZ() >> 4;
                    } else {
                        x = (Integer) args[0];
                        z = (Integer) args[1];
                    }
                    if (Math.abs(x - chunkX) > 1 || Math.abs(z - chunkZ) > 1) {
                        if (args.length >= 4 && Boolean.FALSE.equals(args[3])) {
                            return null;
                        }
                        throw new IllegalStateException("chunk outside region");
                    }
                    return chunks[(x - chunkX + 1) * 3 + (z - chunkZ + 1)];
                }
                case "hasChunk":
                    return Math.abs((Integer) args[0] - chunkX) <= 1 && Math.abs((Integer) args[1] - chunkZ) <= 1;
                case "ensureCanWrite": {
                    // Like the server's region: only the horizontal position matters (height is not checked).
                    BlockPos p = (BlockPos) args[0];
                    return Math.abs((p.getX() >> 4) - chunkX) <= 1 && Math.abs((p.getZ() >> 4) - chunkZ) <= 1;
                }
                case "getBiome":
                    return zoom.getBiome((BlockPos) args[0]);
                case "getNoiseBiome":
                case "getUncachedNoiseBiome":
                    return source.getNoiseBiome((Integer) args[0], (Integer) args[1], (Integer) args[2], randomState.sampler());
                case "getBiomeManager":
                    return zoom;
                case "getSeed":
                    return seed;
                case "getSeaLevel":
                    return 63;
                case "isClientSide":
                    return false;
                case "getRandom":
                    return RandomSource.create(seed);
                case "registryAccess":
                    return registryAccess;
                case "getBlockEntity":
                    return args.length == 1 ? null : java.util.Optional.empty();
                case "getBlockTicks":
                case "getFluidTicks":
                    return net.minecraft.world.ticks.BlackholeTickAccess.emptyLevelList();
                case "getRawBrightness":
                case "getMaxLocalRawBrightness":
                case "getBrightness":
                    return 0; // world generation happens in the dark (no light computed yet)
                case "scheduleTick":
                case "markPosForPostprocessing":
                case "blockUpdated":
                case "updateNeighborsAt":
                    return null;
                case "nextSubTickCount":
                    return 0L;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "SeedTerrainRegion";
                default:
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    throw new UnsupportedOperationException("WorldGenLevel." + name);
            }
        };
        return (WorldGenLevel) java.lang.reflect.Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[] {WorldGenLevel.class}, handler);
    }

    private boolean buriedStone(BlockPos pos, int chunkX, int chunkZ, ProtoChunk[] chunks, Set<Block> wanted) {
        ProtoChunk chunk = chunkAt(pos, chunkX, chunkZ, chunks);
        if (chunk == null) {
            return false;
        }
        BlockState state = chunk.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty() || wanted.contains(state.getBlock())) {
            return false;
        }
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            BlockPos next = pos.relative(direction);
            ProtoChunk other = chunkAt(next, chunkX, chunkZ, chunks);
            if (other == null || other.getBlockState(next).isAir()) {
                return false;
            }
        }
        return true;
    }

    private ProtoChunk chunkAt(BlockPos pos, int chunkX, int chunkZ, ProtoChunk[] chunks) {
        int x = (pos.getX() >> 4) - chunkX;
        int z = (pos.getZ() >> 4) - chunkZ;
        if (x < -1 || x > 1 || z < -1 || z > 1 || pos.getY() < MIN_Y || pos.getY() >= MIN_Y + HEIGHT) {
            return null;
        }
        return chunks[(x + 1) * 3 + (z + 1)];
    }

    private int height(Heightmap.Types type, int x, int z, int chunkX, int chunkZ, ProtoChunk[] chunks, int[][] floors) {
        int cx = (x >> 4) - chunkX;
        int cz = (z >> 4) - chunkZ;
        if (cx < -1 || cx > 1 || cz < -1 || cz > 1) {
            return MIN_Y;
        }
        int i = (cx + 1) * 3 + (cz + 1);
        if (type == Heightmap.Types.OCEAN_FLOOR_WG || type == Heightmap.Types.OCEAN_FLOOR) {
            return floors[i][(x & 15) * 16 + (z & 15)];
        }
        // Other height maps: scan the current blocks from the top.
        ProtoChunk chunk = chunks[i];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, MIN_Y + HEIGHT - 1, z);
        for (int y = MIN_Y + HEIGHT - 1; y >= MIN_Y; y--) {
            pos.setY(y);
            if (type.isOpaque().test(chunk.getBlockState(pos))) {
                return y + 1;
            }
        }
        return MIN_Y;
    }
}
