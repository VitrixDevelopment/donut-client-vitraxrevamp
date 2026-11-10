package dev.donut.client.blockesp;

import java.util.HashSet;
import java.util.Set;

/**
 * Mined test: tracks whether predicted ore positions already hold air or a liquid, which ore generation can never
 * produce there, so the block was removed after generation. Control spots a couple of blocks away at the same height
 * are sampled alongside, because caves and tunnels also contain air. Predicted spots that are air far more often than
 * control spots mean other players have already dug the ore that the module predicts - useful evidence that the
 * area and the seed are right, and a hint that a vein is spent.
 *
 * <p>Pure bookkeeping: callers pass packed {@code BlockPos.asLong()} values and whether the received block is gone.
 */
public final class MinedEvidence {
    /** Control spots per predicted block: left, right and forward at the same height. */
    public static final int CONTROL_SPOTS = 3;
    /** Minimum gap over controls before the sample is treated as evidence of mining. */
    public static final int MIN_SIGNIFICANT_GAP = 5;

    private final Set<Long> predictedSeen = new HashSet<>();
    private final Set<Long> controlSeen = new HashSet<>();
    private int predicted;
    private int predictedGone;
    private int control;
    private int controlGone;

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int x(long packed) { return (int) (packed >> 38); }

    public static int y(long packed) { return (int) (packed << 52 >> 52); }

    public static int z(long packed) { return (int) (packed << 26 >> 38); }

    /** Control spots for a predicted block: two blocks east, two blocks west and two blocks south, same height. */
    public static long[] controls(long packed) {
        int x = x(packed);
        int y = y(packed);
        int z = z(packed);
        return new long[] {pack(x + 2, y, z), pack(x - 2, y, z), pack(x, y, z + 2)};
    }

    /** Returns false when this position was already sampled, so one block is never counted twice. */
    public boolean observePredicted(long packed, boolean gone) {
        if (!predictedSeen.add(packed)) return false;
        predicted++;
        if (gone) predictedGone++;
        return true;
    }

    /** Returns false when this control position was already sampled. */
    public boolean observeControl(long packed, boolean gone) {
        if (!controlSeen.add(packed)) return false;
        control++;
        if (gone) controlGone++;
        return true;
    }

    /** Already sampled; callers check this before reading block states. */
    public boolean sawPredicted(long packed) { return predictedSeen.contains(packed); }

    public boolean sawControl(long packed) { return controlSeen.contains(packed); }

    public int predicted() { return predicted; }

    public int predictedGone() { return predictedGone; }

    public int control() { return control; }

    public int controlGone() { return controlGone; }

    public double predictedRate() { return predicted == 0 ? 0 : predictedGone / (double) predicted; }

    public double controlRate() { return control == 0 ? 0 : controlGone / (double) control; }

    /** Enough predicted blocks are air, well above the control rate, for mining to be the plausible explanation. */
    public boolean suggestsMined() {
        return predicted >= MIN_SIGNIFICANT_GAP && predictedGone - controlGone >= MIN_SIGNIFICANT_GAP
                && predictedRate() >= 0.5 && predictedRate() > controlRate() + 0.15;
    }

    public void reset() {
        predictedSeen.clear();
        controlSeen.clear();
        predicted = predictedGone = control = controlGone = 0;
    }

    /** One-line summary for chat and the analyzer log. */
    public String summary() {
        return predictedGone + " of " + predicted + " predicted blocks are air/liquid vs " + controlGone + " of "
                + control + " control spots";
    }
}
