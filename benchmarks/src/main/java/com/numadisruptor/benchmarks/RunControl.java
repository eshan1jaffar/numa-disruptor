package com.numadisruptor.benchmarks;

/** Shared run phase and stop flags, written by the driver thread and read by producers/gateways. */
final class RunControl {
    static final int WARMUP = 0;
    static final int MEASURE = 1;
    static final int DONE = 2;

    volatile int phase = WARMUP;
    volatile boolean stop;
    /** System.nanoTime() origin for paced producers; set before producers start. */
    volatile long originNanos;
    /** Events each producer publishes before stopping on its own (verification runs); unbounded otherwise. */
    final long limitPerProducer;

    RunControl(final long limitPerProducer) {
        this.limitPerProducer = limitPerProducer;
    }
}
