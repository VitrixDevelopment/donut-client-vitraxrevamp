package dev.donut.client.blockesp;

import java.util.Collection;
import java.util.Set;

/**
 * Decides, from ores the server has actually sent, whether seed predictions can still be trusted.
 *
 * <p>The mathematics is deliberately asymmetric. Ore generation is deterministic: for the seed and
 * generation version that produced a chunk, a FULL-status simulation reproduces every natural ore,
 * including feature writes crossing in from neighbouring chunks. A correct seed therefore has
 * probability <b>zero</b> of missing a natural deepslate emerald/coal ore that is visible in a
 * received chunk (the only exceptions are ores a player placed, e.g. silk-touched). A wrong seed is
 * the opposite: its predicted positions are effectively random draws. An overworld chunk spans
 * 16&times;16 columns over 384 heights = 98,304 block positions, and a chunk's predicted target set
 * is tiny (emerald is scattered singly; even a generous coal estimate stays in the low tens), so the
 * chance that one specific observed ore coincides with a wrong seed's prediction is at most roughly
 * 20 / 98,304 &asymp; 2&times;10<sup>-4</sup>. Servers that enable per-feature seeds
 * (Paper's {@code feature-seeds.generate-random-seeds-for-all}, seed rotation, or a changed world
 * seed) move ore placement to a secret the client cannot simulate at all &mdash; for those worlds
 * every observed natural ore is an unpredicted one.
 *
 * <p>Hence: one exact prediction/observation match is already strong evidence the seed still
 * governs generation, while {@value #REFUTATION_SAMPLE} observed ores with zero matches refute it
 * beyond reasonable doubt (it would require three independent player-placed ores in predicted
 * chunks). Servers may also mix both: chunks generated before a feature-seed change still follow
 * the world seed, newer chunks do not. That yields matches <i>and</i> misses, reported as
 * {@link State#MIXED} so predictions are kept rather than discarded wholesale.
 */
public final class SeedTrust {
    /** Observed-but-unpredicted natural ores (with zero matches) needed to declare the seed dead. */
    public static final int REFUTATION_SAMPLE = 3;

    public enum State {
        /** No ore has been observed in a predicted chunk yet; nothing can be concluded. */
        UNKNOWN,
        /** Every observed ore was predicted exactly: the seed still governs these chunks. */
        VERIFIED,
        /** Ores were missed but fewer than the refutation sample; possibly player-placed blocks. */
        SUSPECT,
        /** Some ores match and some do not: old (seed-generated) and new (re-seeded) chunks coexist. */
        MIXED,
        /** {@value SeedTrust#REFUTATION_SAMPLE}+ ores observed, none predicted: predictions are random noise. */
        REFUTED
    }

    private SeedTrust() { }

    public static State assess(int matched, int unmatched) {
        if (matched < 0 || unmatched < 0) throw new IllegalArgumentException("Counts cannot be negative");
        if (matched + unmatched == 0) return State.UNKNOWN;
        if (unmatched == 0) return State.VERIFIED;
        if (matched == 0) return unmatched >= REFUTATION_SAMPLE ? State.REFUTED : State.SUSPECT;
        return State.MIXED;
    }

    /** Counts observed ores whose exact position and variant appear in {@code predicted}. */
    public static int countMatches(Set<OreMarker> predicted, Collection<OreMarker> observed) {
        int matched = 0;
        for (OreMarker ore : observed) if (predicted.contains(ore)) matched++;
        return matched;
    }
}
