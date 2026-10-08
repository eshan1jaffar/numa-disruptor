package com.numadisruptor.benchmarks;

import com.numadisruptor.affinity.CpuAffinity;

/**
 * Software proxy for CPU migrations: a worker samples {@code sched_getcpu} every few thousand events and counts how often the
 * answer changed. It is a lower bound (migrations between samples go unseen), single-writer, and costs nothing on the
 * per-event path. Not a kernel migration counter.
 */
final class MigrationProbe {
    private int last = -2;
    private long changes;
    private long samples;
    private int distinct;
    private long seenMask; // cpus 0..63 only, enough to tell "stayed put" from "wandered"

    void sample(final CpuAffinity affinity) {
        final int cpu = affinity.currentCpu();
        samples++;
        if (cpu != last) {
            if (last != -2) {
                changes++;
            }
            last = cpu;
        }
        if (cpu >= 0 && cpu < 64 && (seenMask & (1L << cpu)) == 0) {
            seenMask |= 1L << cpu;
            distinct++;
        }
    }

    long changes() { return changes; }

    long samples() { return samples; }

    /** Distinct CPUs among ids 0..63 that the worker was observed on. */
    int distinctCpus() { return distinct; }
}
