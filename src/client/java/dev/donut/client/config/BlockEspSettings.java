package dev.donut.client.config;

/** Vanilla 1.21.11 seed prediction preferences. Keep seeds as text to preserve all 64 bits in JSON. */
public final class BlockEspSettings {
    public static final String DEFAULT_SEED = "6608149111735331168";
    /** Full vanilla world generation: the game's own chunk scheduler and world storage. */
    public static final String ENGINE_WORLD = "Vanilla world";
    /** Seed simulation: the same generator replayed per chunk, no server, no storage. */
    public static final String ENGINE_SIMULATION = "Seed simulation";
    public static final java.util.List<String> ENGINES = java.util.List.of(ENGINE_WORLD, ENGINE_SIMULATION);
    public boolean enabled, favorite;
    public String seed = DEFAULT_SEED;
    /** Prediction engine; the seed simulation is lighter and reaches further, the vanilla world is the reference. */
    public String engine = ENGINE_SIMULATION;
    public boolean emerald = true, coal = true, tracers, showHud = true;
    public int radius = 4, emeraldColor = 0x55EE88, coalColor = 0xA7B7CB, opacity = 210;
    // Area verification: real ore the client receives decides which areas follow the seed.
    public boolean verifyAreas = true, showUnmatched, confirmedOnly, chatNotices, minedTest = true;
    // Nearest deepslate emerald card, searched through seeded mountain biomes.
    public boolean nearestEmerald = true;
    public int emeraldSearchRange = 1500;

    /** Hard cap of the emerald search range: beyond this the biome sampling and generation cost is not worth it. */
    public static final int MAX_SEARCH_RANGE = 1500;

    public static long parseSeed(String text) {
        if (text == null || !text.trim().matches("-?[0-9]{1,19}"))
            throw new IllegalArgumentException("Enter a signed 64-bit numeric world seed.");
        try { return Long.parseLong(text.trim()); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Seed is outside the signed 64-bit range."); }
    }

    public void sanitize() {
        // Preserve invalid input so the module reports it instead of silently predicting a different world.
        if (seed == null) seed = "";
        seed = seed.trim();
        if (seed.length() > 64) seed = seed.substring(0, 64);
        if (engine == null || !ENGINES.contains(engine)) engine = ENGINE_SIMULATION;
        radius = Math.clamp(radius, 1, 8);
        emeraldSearchRange = Math.clamp(emeraldSearchRange, 256, 1500);
        opacity = Math.clamp(opacity, 0, 255);
        emeraldColor &= 0xFFFFFF;
        coalColor &= 0xFFFFFF;
    }
}
