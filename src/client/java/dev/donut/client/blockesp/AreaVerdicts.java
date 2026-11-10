package dev.donut.client.blockesp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-area verdicts for seed prediction.
 *
 * <p>A server can generate some regions from the world seed and other regions with separate feature seeds, which
 * places ore differently while leaving the terrain identical. Real ore that the client can see decides which case
 * an area is in. Verification is all-or-nothing per area, an area being {@value #AREA_CHUNKS}x{@value #AREA_CHUNKS}
 * chunks ({@value #AREA_BLOCKS} blocks) square, and the verdicts are saved so travel does not throw them away.
 *
 * <p>Real ore touching air is used as evidence because anti-xray may disguise buried ore as stone, but it never hides
 * ore that is already exposed to a cave. A hit is a real ore block the prediction also places. A miss is a real ore
 * block the prediction does not place anywhere in that chunk or its eight neighbours.
 *
 * <p>The thresholds are deliberately conservative: a handful of misses in a seed-matching area happen when a vein
 * straddles a chunk border into a chunk that is not predicted yet, so an area is only called unmatched after several
 * misses and almost no hits, and it is only trusted after several hits.
 */
public final class AreaVerdicts {
    public enum Status { UNVERIFIED, CONFIRMED, UNMATCHED }

    /** Chunks per area edge. 8 chunks = 128 blocks, the granularity the server's feature seeds appear to use. */
    public static final int AREA_CHUNKS = 8;
    public static final int AREA_BLOCKS = AREA_CHUNKS * 16;

    /** Hits needed before an area is trusted. */
    public static final int CONFIRM_HITS = 3;
    /** Misses needed before an area is rejected. */
    public static final int REJECT_MISSES = 4;

    private final Map<String, int[]> counts = new HashMap<>();
    private boolean dirty;

    /** Area key: dimension letter plus the floored 8-chunk area coordinate. Negatives floor, never truncate. */
    public static String key(boolean nether, int chunkX, int chunkZ) {
        return (nether ? "n" : "o") + (chunkX >> 3) + "," + (chunkZ >> 3);
    }

    public static String key(boolean nether, long chunkKey) {
        return key(nether, net.minecraft.world.level.ChunkPos.getX(chunkKey), net.minecraft.world.level.ChunkPos.getZ(chunkKey));
    }

    /** Block coordinate of an area's centre, used in chat notices and the HUD. */
    public static int center(int areaCoordinate) {
        return areaCoordinate * AREA_BLOCKS + AREA_BLOCKS / 2;
    }

    public static Status statusOf(int hits, int misses) {
        int total = hits + misses;
        if (hits >= CONFIRM_HITS && hits * 2 >= total) return Status.CONFIRMED;
        if (misses >= REJECT_MISSES && hits * 5 < total) return Status.UNMATCHED;
        return Status.UNVERIFIED;
    }

    public Status status(String key) {
        int[] value = counts.get(key);
        return value == null ? Status.UNVERIFIED : statusOf(value[0], value[1]);
    }

    /** Records evidence and returns the area's status after the change. */
    public Status record(String key, boolean hit, int weight) {
        int[] value = counts.computeIfAbsent(key, ignored -> new int[2]);
        value[hit ? 0 : 1] += Math.max(1, weight);
        dirty = true;
        return statusOf(value[0], value[1]);
    }

    public int hits(String key) {
        int[] value = counts.get(key);
        return value == null ? 0 : value[0];
    }

    public int misses(String key) {
        int[] value = counts.get(key);
        return value == null ? 0 : value[1];
    }

    public int count(Status status) {
        int total = 0;
        for (String key : counts.keySet()) if (status(key) == status) total++;
        return total;
    }

    public Set<String> areas() {
        return java.util.Collections.unmodifiableSet(counts.keySet());
    }

    /** Areas whose status may hide markers, for the emerald search to skip. */
    public Set<String> keysWith(Status status) {
        Set<String> result = new java.util.HashSet<>();
        for (String key : counts.keySet()) if (status(key) == status) result.add(key);
        return result;
    }

    public boolean isDirty() { return dirty; }

    public void markSaved() { dirty = false; }

    public void clear() {
        counts.clear();
        dirty = true;
    }

    /** One line per area: {@code key;hits;misses}. Unparsable lines are ignored so a damaged file still loads. */
    public String serialize() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, int[]> entry : new LinkedHashMap<>(counts).entrySet()) {
            text.append(entry.getKey()).append(';').append(entry.getValue()[0]).append(';')
                    .append(entry.getValue()[1]).append('\n');
        }
        return text.toString();
    }

    public int loadLines(Iterable<String> lines) {
        int loaded = 0;
        for (String line : lines) {
            if (line == null) continue;
            String[] parts = line.trim().split(";");
            if (parts.length != 3 || parts[0].isEmpty()) continue;
            int hits;
            int misses;
            try {
                hits = Integer.parseInt(parts[1].trim());
                misses = Integer.parseInt(parts[2].trim());
            } catch (NumberFormatException error) {
                continue;
            }
            if (hits < 0 || misses < 0) continue;
            counts.put(parts[0], new int[] {hits, misses});
            loaded++;
        }
        dirty = false;
        return loaded;
    }

    public void load(Path file) {
        try {
            if (Files.isRegularFile(file)) loadLines(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // An unreadable evidence file only costs verification time, never a wrong verdict.
        }
    }

    public void save(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, serialize(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            markSaved();
        } catch (IOException ignored) {
            // Keep the in-memory verdicts; the next save attempt writes them again.
        }
    }

    /** Compact human-readable counts for the HUD and the module's help line. */
    public String summary() {
        List<String> parts = new ArrayList<>();
        int confirmed = count(Status.CONFIRMED);
        int unmatched = count(Status.UNMATCHED);
        int unverified = count(Status.UNVERIFIED);
        if (confirmed > 0) parts.add(confirmed + " confirmed");
        if (unmatched > 0) parts.add(unmatched + " unmatched");
        if (unverified > 0) parts.add(unverified + " unknown");
        return parts.isEmpty() ? "no areas checked yet" : String.join(", ", parts);
    }
}
