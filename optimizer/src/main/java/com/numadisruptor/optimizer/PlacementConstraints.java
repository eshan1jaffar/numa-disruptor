package com.numadisruptor.optimizer;

import java.util.BitSet;

/**
 * Knobs that restrict where workers may be put, beyond the topology's allowed masks.
 *
 * @param allowSmtSharing  if false, node CPU capacity counts only one hardware thread per physical core, so no two
 *                         busy workers ever share a core. If true, SMT siblings are used once the cores run out
 *                         (the plan then carries a warning).
 * @param reservedCpus     CPUs that must not receive workers (for example the benchmark driver or JVM service threads)
 */
public record PlacementConstraints(boolean allowSmtSharing, BitSet reservedCpus) {
    public PlacementConstraints {
        reservedCpus = (BitSet) reservedCpus.clone();
    }

    public static PlacementConstraints defaults() {
        return new PlacementConstraints(true, new BitSet());
    }

    public static PlacementConstraints noSmtSharing() {
        return new PlacementConstraints(false, new BitSet());
    }

    @Override
    public BitSet reservedCpus() {
        return (BitSet) reservedCpus.clone();
    }
}
