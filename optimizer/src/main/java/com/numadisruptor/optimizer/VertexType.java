package com.numadisruptor.optimizer;

/** What a vertex of the communication graph represents. */
public enum VertexType {
    /** Thread that claims ring slots and publishes events. Needs a CPU. */
    PRODUCER(true),
    /** Thread that reads events from a ring (an {@code EventHandler} runner). Needs a CPU. */
    CONSUMER(true),
    /** A consumer that republishes into a downstream ring (pipeline stage / cross-ring bridge). Needs a CPU. */
    STAGE(true),
    /** The preallocated event array of one ring. Consumes memory, no CPU. */
    RING_BUFFER(false),
    /** Producer-side sequencing state: cursor and (multi-producer) availability buffer. */
    SEQUENCER_METADATA(false),
    /** Consumer progress sequences that the producer reads to detect a full ring. */
    GATING_SEQUENCE(false);

    private final boolean worker;

    VertexType(final boolean worker) {
        this.worker = worker;
    }

    /** True for vertices that are executed by a pinned thread and therefore occupy a CPU. */
    public boolean isWorker() {
        return worker;
    }
}
