package com.numadisruptor.runtime;

/** Maps an application routing key (for example an instrument id) to a partition. Must be pure and thread-safe. */
@FunctionalInterface
public interface EventRouter {
    /** @return partition in {@code [0, partitions)}; the same key always yields the same partition */
    int route(long key, int partitions);

    /** Uniform hash router (SplitMix64 finalizer), so consecutive keys spread evenly. */
    static EventRouter hashing() {
        return (key, partitions) -> {
            long z = key + 0x9E3779B97F4A7C15L;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z = z ^ (z >>> 31);
            return (int) ((z >>> 1) % partitions);
        };
    }

    /** Key modulo partitions; useful when keys are already dense partition ids. */
    static EventRouter modulo() {
        return (key, partitions) -> (int) Math.floorMod(key, (long) partitions);
    }
}
