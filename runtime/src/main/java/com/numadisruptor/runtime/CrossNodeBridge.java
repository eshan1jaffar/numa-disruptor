package com.numadisruptor.runtime;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.InsufficientCapacityException;
import com.lmax.disruptor.RingBuffer;
import java.util.function.BooleanSupplier;

/**
 * Handler that moves events from one ring into the next, applying a {@link StageFunction}. It is the forwarding stage of a
 * pipeline and also the mechanism for crossing NUMA nodes cheaply: because the bridge publishes <b>once per batch</b> (not
 * once per event), the cursor cache line travels between the two nodes once per batch rather than once per event, and the
 * source and destination rings can each live next to their own producer/consumer.
 *
 * <p>Invariants:
 * <ul>
 *   <li>The bridge is the <b>only</b> writer of the target ring, which is therefore a single-producer ring, so claimed
 *       sequences are contiguous and {@code publish(lo, hi)} is valid.</li>
 *   <li>Per-event it claims with {@code tryNext()} and never blocks inside the Disruptor: if the target is full it first
 *       <b>publishes what it already wrote</b> (otherwise the target's consumers could not see those events and could
 *       never free space: deadlock), then spins until space appears or {@code stopping} turns true.</li>
 *   <li>On stop it throws {@link BridgeStoppedException}, which the processor's exception handler treats as a clean exit.</li>
 * </ul>
 */
final class CrossNodeBridge<E> implements EventHandler<E> {
    /** Thrown to leave a blocked claim loop during shutdown or failure. */
    static final class BridgeStoppedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BridgeStoppedException() {
            super("bridge stopped", null, false, false);
        }
    }

    private final RingBuffer<E> target;
    private final StageFunction<E> function;
    private final int partition;
    private final BooleanSupplier stopping;

    private long pendingLow = -1;
    private long pendingHigh = -1;

    CrossNodeBridge(final RingBuffer<E> target, final StageFunction<E> function, final int partition, final BooleanSupplier stopping) {
        this.target = target;
        this.function = function;
        this.partition = partition;
        this.stopping = stopping;
    }

    @Override
    public void onEvent(final E in, final long sequence, final boolean endOfBatch) {
        final long slot = claim();
        // Record the claim before running user code: if apply() throws, the slot must still be published (as a
        // failed event) or the target's consumers would wait forever on a claimed-but-unpublished sequence.
        if (pendingLow < 0) {
            pendingLow = slot;
        }
        pendingHigh = slot;
        try {
            function.apply(partition, in, target.get(slot));
        } finally {
            if (endOfBatch) {
                flush();
            }
        }
    }

    private long claim() {
        while (true) {
            try {
                return target.tryNext();
            } catch (final InsufficientCapacityException full) {
                flush(); // make already-written events visible so the target can drain
                if (stopping.getAsBoolean()) {
                    throw new BridgeStoppedException();
                }
                Thread.onSpinWait();
            }
        }
    }

    private void flush() {
        if (pendingLow >= 0) {
            target.publish(pendingLow, pendingHigh);
            pendingLow = -1;
        }
    }

    @Override
    public void onShutdown() {
        flush();
    }
}
