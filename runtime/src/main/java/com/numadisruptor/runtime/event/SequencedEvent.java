package com.numadisruptor.runtime.event;

import com.lmax.disruptor.EventFactory;

/**
 * Mutable, preallocated event used by tests and benchmarks. Every field is derived from {@code (producerId, producerSequence)}
 * so a consumer can recompute and verify it: a slot read before it was fully written, a stale slot from a previous lap
 * of the ring, or a misrouted event all fail {@link #isConsistent()} or break the producer-sequence progression.
 *
 * <p>The optional {@code payload} array sizes the event for event-size sweeps; it is written by the producer and read by the
 * consumer so payload bytes really move between caches/nodes.
 */
public final class SequencedEvent {
    public static final long UNSET = -1L;

    public long eventId = UNSET;
    public int partitionId = -1;
    public int producerId = -1;
    public long producerSequence = UNSET;
    public long value = UNSET;
    /** {@link System#nanoTime()} (intended send time) captured by the producer; 0 when latency is not measured. */
    public long timestampNanos;
    public final long[] payload;

    public SequencedEvent(final int payloadWords) {
        this.payload = new long[payloadWords];
    }

    public static EventFactory<SequencedEvent> factory(final int payloadWords) {
        return () -> new SequencedEvent(payloadWords);
    }

    public static long eventIdOf(final int producerId, final long producerSequence) {
        return ((long) producerId << 40) | producerSequence;
    }

    public static long expectedValue(final int producerId, final long producerSequence) {
        long z = eventIdOf(producerId, producerSequence) + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Fill every field (and the whole payload) for the given identity. */
    public void init(final int partition, final int producer, final long sequence, final long timestamp) {
        partitionId = partition;
        producerId = producer;
        producerSequence = sequence;
        eventId = eventIdOf(producer, sequence);
        timestampNanos = timestamp;
        final long v = expectedValue(producer, sequence);
        for (int i = 0; i < payload.length; i++) {
            payload[i] = v + i;
        }
        value = v; // written last: a reader that sees a matching value has (in program order) seen the rest
    }

    /** XOR of all payload words; forces the consumer to read every payload line. */
    public long payloadChecksum() {
        long c = 0;
        for (final long w : payload) {
            c ^= w;
        }
        return c;
    }

    /** True if every derived field matches the event's identity. */
    public boolean isConsistent() {
        if (producerId < 0 || producerSequence < 0 || eventId != eventIdOf(producerId, producerSequence)) {
            return false;
        }
        final long v = expectedValue(producerId, producerSequence);
        if (value != v) {
            return false;
        }
        for (int i = 0; i < payload.length; i++) {
            if (payload[i] != v + i) {
                return false;
            }
        }
        return true;
    }

    /** Copies identity, value and payload (used by identity stages). */
    public void copyFrom(final SequencedEvent o) {
        eventId = o.eventId;
        partitionId = o.partitionId;
        producerId = o.producerId;
        producerSequence = o.producerSequence;
        timestampNanos = o.timestampNanos;
        System.arraycopy(o.payload, 0, payload, 0, Math.min(payload.length, o.payload.length));
        value = o.value;
    }
}
