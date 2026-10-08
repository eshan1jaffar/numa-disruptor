package com.numadisruptor.runtime;

/**
 * One-to-one transformation applied by a forwarding stage: every input event produces exactly one output event in the next
 * ring (use a field of the output to say "no signal"). One-to-one keeps counts and per-partition order exact.
 *
 * <p>Runs on the stage's pinned worker thread. Must not retain {@code in} or {@code out} after returning, because both
 * slots are reused by the ring.
 */
@FunctionalInterface
public interface StageFunction<E> {
    void apply(int partition, E in, E out);
}
