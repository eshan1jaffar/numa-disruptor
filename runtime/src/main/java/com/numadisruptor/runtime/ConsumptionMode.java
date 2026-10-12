package com.numadisruptor.runtime;

/**
 * How the final-stage consumers of a partition share its event stream. These are different semantics and are never mixed.
 */
public enum ConsumptionMode {
    /**
     * Every consumer receives <b>every</b> event, in ring order (publish/subscribe). N consumers means N times the
     * processing work per event.
     */
    MULTICAST,
    /**
     * Each event is processed by <b>exactly one</b> consumer: with N consumers, consumer {@code i} handles the events whose
     * ring sequence satisfies {@code sequence % N == i}. Throughput scales with N, but ordering holds only <i>within one
     * worker</i> (each worker sees its events in ring order); there is no ordering between workers. Batches are per worker:
     * {@code onBatchStart} is delivered only for ring batches in which the worker owns an event (with its own event count)
     * and {@code endOfBatch} is true on the worker's last own event of the batch.
     */
    WORK_DISTRIBUTION
}
