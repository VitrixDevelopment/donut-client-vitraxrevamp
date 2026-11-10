package dev.donut.client.blockesp;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Samples the Overworld's seeded biome layout instead of waiting for chunks, so the nearest-emerald search can look
 * hundreds of blocks outside the loaded area.
 *
 * <p>Emerald ore only generates in mountain biomes. Sampling the same multi-noise biome source the generator uses,
 * at three heights above sea level, tells the search which columns are worth generating. Built on the client thread
 * from the world's registry data and then read only by the search worker.
 */
public final class EmeraldBiomes implements NearestEmeraldSearch.Biomes {
    /** Heights sampled per column; peaks, slopes and meadows all appear in this band. */
    private static final int[] SAMPLE_Y = {64, 96, 128};

    private final MultiNoiseBiomeSource source;
    private final Climate.Sampler sampler;

    private EmeraldBiomes(MultiNoiseBiomeSource source, Climate.Sampler sampler) {
        this.source = source;
        this.sampler = sampler;
    }

    /**
     * @throws RuntimeException when the client has no Overworld noise settings yet (before a world is joined)
     */
    public static EmeraldBiomes create(HolderLookup.Provider registries, long seed) {
        var preset = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        var source = MultiNoiseBiomeSource.createFromPreset(preset);
        RandomState randomState = RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, seed);
        return new EmeraldBiomes(source, randomState.sampler());
    }

    @Override
    public boolean emerald(int blockX, int blockZ) {
        for (int y : SAMPLE_Y) {
            var biome = source.getNoiseBiome(blockX >> 2, y >> 2, blockZ >> 2, sampler);
            String path = biome.unwrapKey().map(key -> key.identifier().getPath()).orElse("");
            if (NearestEmeraldSearch.EMERALD_BIOMES.contains(path)) return true;
        }
        return false;
    }
}
