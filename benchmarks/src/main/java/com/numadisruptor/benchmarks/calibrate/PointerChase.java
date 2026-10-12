package com.numadisruptor.benchmarks.calibrate;

import java.util.SplittableRandom;

/** Pure pointer-chase permutation builder (no native code), so it can be unit-tested anywhere. */
public final class PointerChase {
    private PointerChase() { }

    /**
     * A random cyclic permutation of {@code 0..lines-1} (Sattolo's algorithm): following {@code next[i]} from any start visits
     * every line exactly once before returning, so the chase cannot fall into a short cache-resident loop. Deterministic per seed.
     */
    public static int[] buildCycle(final int lines, final long seed) {
        if (lines < 2) {
            throw new IllegalArgumentException("need at least 2 lines, was " + lines);
        }
        final int[] next = new int[lines];
        for (int i = 0; i < lines; i++) {
            next[i] = i;
        }
        final SplittableRandom rnd = new SplittableRandom(seed);
        for (int i = lines - 1; i > 0; i--) {
            final int j = rnd.nextInt(i); // j in [0, i): never i itself, which is what makes it a single cycle
            final int t = next[i];
            next[i] = next[j];
            next[j] = t;
        }
        return next;
    }
}
