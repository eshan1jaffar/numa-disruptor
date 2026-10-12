package com.numadisruptor.benchmarks;

/** Shared run phase and stop flags, written by the driver thread and read by producers/gateways. */
final class RunControl {
    static final int WARMUP = 0;
    static final int MEASURE = 1;
    static final int DONE = 2;

    volatile int phase = WARMUP;
    volatile boolean stop;
    /** System.nanoTime() origin for paced producers; 0 until the first producer claims it (see {@link #origin()}). */
    private final java.util.concurrent.atomic.AtomicLong originNanos = new java.util.concurrent.atomic.AtomicLong();
    /** Latency window [start, end) applied to the intended send time, in System.nanoTime() terms; set by the driver. */
    volatile long windowStartNanos = Long.MAX_VALUE;
    volatile long windowEndNanos = Long.MAX_VALUE;

    /**
     * The shared schedule origin: fixed by the first producer to ask (a short lead after that moment), so ring construction and
     * pinning inside {@code start()} cannot leave producers behind schedule.
     */
    long origin() {
        if (originNanos.get() == 0) {
            originNanos.compareAndSet(0, System.nanoTime() + 20_000_000L);
        }
        return originNanos.get();
    }
    /** Events each producer publishes before stopping on its own (verification runs); unbounded otherwise. */
    final long limitPerProducer;

    RunControl(final long limitPerProducer) {
        this.limitPerProducer = limitPerProducer;
    }
}
