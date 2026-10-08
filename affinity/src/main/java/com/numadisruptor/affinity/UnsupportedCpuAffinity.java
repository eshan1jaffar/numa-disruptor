package com.numadisruptor.affinity;

import java.util.BitSet;

/** Used where pinning is impossible (non-Linux, binding failure). Reads report "all CPUs"; writes fail loudly. */
final class UnsupportedCpuAffinity implements CpuAffinity {
    private final String reason;

    UnsupportedCpuAffinity(final String reason) {
        this.reason = reason;
    }

    @Override
    public boolean isSupported() {
        return false;
    }

    @Override
    public BitSet currentThreadAffinity() {
        final BitSet all = new BitSet();
        all.set(0, Runtime.getRuntime().availableProcessors());
        return all;
    }

    @Override
    public void pinCurrentThread(final BitSet cpus) {
        throw new AffinityException("CPU pinning unsupported: " + reason);
    }

    @Override
    public int currentCpu() {
        return -1;
    }
}
