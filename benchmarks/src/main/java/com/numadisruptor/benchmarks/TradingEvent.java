package com.numadisruptor.benchmarks;

import com.lmax.disruptor.EventFactory;

/**
 * Event of the simplified trading pipeline: a market-data tick that the strategy stage turns into an order. The same preallocated
 * object type is used in both rings. All fields are deterministic functions of (producerId, producerSequence) except the
 * stage's output, which is a deterministic function of the per-partition tick order.
 */
final class TradingEvent {
    long producerSequence = -1;
    int producerId = -1;
    int partitionId = -1;
    int instrument;
    long price;
    int quantity;
    /** Written by the strategy stage: +1 buy / -1 sell. */
    int signal;
    /** Written by the strategy stage: signed notional of the generated order. */
    long orderValue;
    /** Intended send time (System.nanoTime), 0 when latency is not measured. */
    long intendedNanos;
    /** Off-heap payload slot index (V2 with off-heap), unused otherwise. */
    long payloadSlot = -1;
    final long[] payload;

    TradingEvent(final int payloadWords) {
        payload = new long[payloadWords];
    }

    static EventFactory<TradingEvent> factory(final int payloadWords) {
        return () -> new TradingEvent(payloadWords);
    }

    void copyTickFrom(final TradingEvent o) {
        producerSequence = o.producerSequence;
        producerId = o.producerId;
        partitionId = o.partitionId;
        instrument = o.instrument;
        price = o.price;
        quantity = o.quantity;
        intendedNanos = o.intendedNanos;
        payloadSlot = o.payloadSlot;
        System.arraycopy(o.payload, 0, payload, 0, Math.min(payload.length, o.payload.length));
    }
}
