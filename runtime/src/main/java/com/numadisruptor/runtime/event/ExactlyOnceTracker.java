package com.numadisruptor.runtime.event;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Test-scale ledger recording which {@code (producerId, producerSequence)} pairs were processed, shared by all consumers of a
 * stream. Detects duplicate processing (needed to verify work distribution, where no single consumer sees all events) and,
 * at the end, lost events. Uses a CAS bitmap, so it is meant for correctness tests, not for benchmark hot paths.
 */
public final class ExactlyOnceTracker {
    private final int producers;
    private final long eventsPerProducer;
    private final int wordsPerProducer;
    private final AtomicLongArray bits;
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong outOfRange = new AtomicLong();

    public ExactlyOnceTracker(final int producers, final long eventsPerProducer) {
        this.producers = producers;
        this.eventsPerProducer = eventsPerProducer;
        this.wordsPerProducer = (int) ((eventsPerProducer + 63) >>> 6);
        this.bits = new AtomicLongArray(Math.multiplyExact(producers, wordsPerProducer));
    }

    /** Records an event; returns false if it was already recorded (a duplicate) or is outside the expected range. */
    public boolean markProcessed(final int producerId, final long producerSequence) {
        if (producerId < 0 || producerId >= producers || producerSequence < 0 || producerSequence >= eventsPerProducer) {
            outOfRange.incrementAndGet();
            return false;
        }
        final int idx = producerId * wordsPerProducer + (int) (producerSequence >>> 6);
        final long mask = 1L << (producerSequence & 63);
        while (true) {
            final long cur = bits.get(idx);
            if ((cur & mask) != 0) {
                duplicates.incrementAndGet();
                return false;
            }
            if (bits.compareAndSet(idx, cur, cur | mask)) {
                return true;
            }
        }
    }

    public long duplicates() { return duplicates.get(); }

    public long outOfRange() { return outOfRange.get(); }

    /** Number of expected events never recorded. */
    public long missing() {
        long seen = 0;
        for (int i = 0; i < bits.length(); i++) {
            seen += Long.bitCount(bits.get(i));
        }
        return producers * eventsPerProducer - seen;
    }
}
