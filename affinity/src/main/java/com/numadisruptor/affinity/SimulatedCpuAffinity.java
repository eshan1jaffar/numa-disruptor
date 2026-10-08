package com.numadisruptor.affinity;

import java.util.BitSet;

/**
 * In-process stand-in that records masks per thread without touching the OS. It enforces an "allowed" set
 * the same way the kernel does (a mask with no permitted CPU is rejected), so runtime and placement code can be
 * unit-tested on machines without Linux affinity support. It does <b>not</b> constrain real scheduling.
 */
public final class SimulatedCpuAffinity implements CpuAffinity {
    private final BitSet permitted;
    private final ThreadLocal<BitSet> mask;

    public SimulatedCpuAffinity(final BitSet permitted) {
        this.permitted = (BitSet) permitted.clone();
        this.mask = ThreadLocal.withInitial(() -> (BitSet) this.permitted.clone());
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    @Override
    public BitSet currentThreadAffinity() {
        return (BitSet) mask.get().clone();
    }

    @Override
    public void pinCurrentThread(final BitSet cpus) {
        if (cpus.isEmpty()) {
            throw new IllegalArgumentException("Cannot pin to an empty CPU set");
        }
        final BitSet effective = (BitSet) cpus.clone();
        effective.and(permitted);
        if (effective.isEmpty()) {
            throw new AffinityException("sched_setaffinity(" + cpus + ") failed, errno=22 (EINVAL): none permitted");
        }
        mask.set(effective);
    }

    @Override
    public int currentCpu() {
        return mask.get().nextSetBit(0);
    }
}
