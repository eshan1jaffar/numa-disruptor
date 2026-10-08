package com.numadisruptor.runtime;

/** How workers and rings are mapped to NUMA nodes and CPUs. */
public enum PlacementPolicy {
    /** No pinning and no placement: the OS scheduler decides (the "vanilla" baseline). */
    NONE,
    /** Pin workers using first-fit node filling, ignoring traffic. */
    NAIVE,
    /** Pin workers using round-robin across nodes, ignoring traffic. */
    ROUND_ROBIN,
    /** Communication-aware greedy placement. */
    GREEDY,
    /** Greedy placement refined by local search (the full optimizer). */
    MIN_REMOTE_TRAFFIC,
    /** Use the plan supplied with {@code placementPlan(...)}. */
    EXPLICIT
}
