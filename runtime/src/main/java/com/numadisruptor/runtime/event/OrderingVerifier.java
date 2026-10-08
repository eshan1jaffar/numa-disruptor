package com.numadisruptor.runtime.event;

import com.lmax.disruptor.EventHandler;

/**
 * Consumer that checks every event it receives and counts violations instead of throwing, so one run reports everything wrong.
 *
 * <ul>
 *   <li>{@link Mode#CONTIGUOUS}: the consumer must see <i>every</i> event of each producer in order (single consumer, or
 *       multicast). A skipped producerSequence is counted as lost, a repeated/older one as duplicate-or-reordered.</li>
 *   <li>{@link Mode#MONOTONIC}: the consumer sees a subset (work distribution) so only strict per-producer increase is checked.
 *       Completeness and exactly-once are then checked with a shared {@link ExactlyOnceTracker}.</li>
 * </ul>
 * Not thread-safe: one instance per consumer thread; read the counters only after the processor has stopped (the stop
 * happens-before via the consumer sequence).
 */
public final class OrderingVerifier implements EventHandler<SequencedEvent> {
    public enum Mode { CONTIGUOUS, MONOTONIC }

    /** Aggregated counters. */
    public record Report(long processed, long lost, long duplicateOrReordered, long inconsistent, long misrouted, long duplicateById) {
        public boolean clean() {
            return lost == 0 && duplicateOrReordered == 0 && inconsistent == 0 && misrouted == 0 && duplicateById == 0;
        }

        public Report plus(final Report o) {
            return new Report(processed + o.processed, lost + o.lost, duplicateOrReordered + o.duplicateOrReordered,
                inconsistent + o.inconsistent, misrouted + o.misrouted, duplicateById + o.duplicateById);
        }

        public static Report empty() {
            return new Report(0, 0, 0, 0, 0, 0);
        }
    }

    private final int partition;
    private final Mode mode;
    private final long[] expectedNext;
    private final ExactlyOnceTracker tracker;
    private final boolean checkPayload;

    private long processed;
    private long lost;
    private long duplicateOrReordered;
    private long inconsistent;
    private long misrouted;
    private long duplicateById;

    /**
     * @param partition      partition this consumer serves (events with another partitionId are counted as misrouted)
     * @param totalProducers number of global producer ids
     * @param tracker        optional shared exactly-once ledger (may be null)
     * @param checkPayload   verify every payload word (costly for large events) rather than only the scalar fields
     */
    public OrderingVerifier(
        final int partition, final int totalProducers, final Mode mode, final ExactlyOnceTracker tracker, final boolean checkPayload) {
        this.partition = partition;
        this.mode = mode;
        this.expectedNext = new long[totalProducers];
        this.tracker = tracker;
        this.checkPayload = checkPayload;
    }

    @Override
    public void onEvent(final SequencedEvent e, final long sequence, final boolean endOfBatch) {
        processed++;
        if (e.partitionId != partition) {
            misrouted++;
        }
        final int pid = e.producerId;
        final long seq = e.producerSequence;
        if (pid < 0 || pid >= expectedNext.length || seq < 0) {
            inconsistent++; // partially initialised or garbage slot
            return;
        }
        if (checkPayload ? !e.isConsistent() : (e.eventId != SequencedEvent.eventIdOf(pid, seq)
            || e.value != SequencedEvent.expectedValue(pid, seq))) {
            inconsistent++;
        }
        final long expected = expectedNext[pid];
        if (mode == Mode.CONTIGUOUS) {
            if (seq == expected) {
                expectedNext[pid] = expected + 1;
            } else if (seq > expected) {
                lost += seq - expected;
                expectedNext[pid] = seq + 1;
            } else {
                duplicateOrReordered++;
            }
        } else if (seq >= expected) {
            expectedNext[pid] = seq + 1;
        } else {
            duplicateOrReordered++;
        }
        if (tracker != null && !tracker.markProcessed(pid, seq)) {
            duplicateById++;
        }
    }

    /** Counters so far. Safe to call from another thread only after the consumer has finished. */
    public Report report() {
        return new Report(processed, lost, duplicateOrReordered, inconsistent, misrouted, duplicateById);
    }

    /** For CONTIGUOUS mode: events of {@code producerId} still missing at the end, given how many were published. */
    public long missingFor(final int producerId, final long published) {
        return Math.max(0, published - expectedNext[producerId]);
    }
}
