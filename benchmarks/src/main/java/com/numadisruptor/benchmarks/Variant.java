package com.numadisruptor.benchmarks;

/** The four benchmark configurations. All run the identical workload through the identical wiring; only placement differs. */
public enum Variant {
    /** Vanilla: unmodified Disruptor rings, default OS scheduling, no pinning, no placement. */
    V0("vanilla LMAX, OS scheduling"),
    /** Pinned producer/consumer workers; ring memory is first-touched wherever the launching thread runs. */
    V1("affinity-aware LMAX: pinned workers"),
    /** V1 plus explicit memory placement: rings first-touched on their node and (optional) off-heap payload bound to it. */
    V2("NUMA-local LMAX: pinned workers + memory placement"),
    /** The optimizer decides every placement; rings are node-local. Same partitions/ordering as the other variants. */
    V3("NUMA-optimised framework: optimizer-chosen placement");

    private final String description;

    Variant(final String description) {
        this.description = description;
    }

    public String description() { return description; }
}
