package com.numadisruptor.runtime;

import com.lmax.disruptor.EventHandler;

/**
 * Work distribution on a Disruptor ring: worker {@code ordinal} of {@code count} processes only events whose ring sequence
 * satisfies {@code sequence % count == ordinal}, so each event is handled by exactly one worker. All workers still advance
 * their sequence over every slot, so the ring can wrap normally. (Disruptor 4.0 has no WorkerPool; this is the documented
 * replacement.)
 *
 * <p>Batch semantics seen by the delegate are exact, not inherited from the ring batch: the wrapper sees every event of a
 * batch, so it knows how many events remain. {@code endOfBatch} is true for the worker's last own event in the batch (no
 * later event of the batch has this ordinal), and {@code onBatchStart} is forwarded lazily, at the batch's first event, only
 * if the worker owns at least one event in the batch, with {@code batchSize} equal to the number of events the worker owns.
 */
final class WorkDistributionHandler<E> implements EventHandler<E> {
    private final EventHandler<E> delegate;
    private final int ordinal;
    private final int count;

    // batch state, touched only by the consumer thread
    private long pendingBatchSize; // events of the current batch not yet seen; 0 between batches
    private long queueDepth;
    private boolean batchOpen;
    private boolean sized; // false if the ring gave no onBatchStart: fall back to the ring's endOfBatch

    WorkDistributionHandler(final EventHandler<E> delegate, final int ordinal, final int count) {
        this.delegate = delegate;
        this.ordinal = ordinal;
        this.count = count;
    }

    @Override
    public void onEvent(final E event, final long sequence, final boolean endOfBatch) throws Exception {
        if (!batchOpen) {
            batchOpen = true;
            sized = pendingBatchSize > 0;
            // first event of the batch: [sequence, sequence + pendingBatchSize - 1]
            final long last = sequence + pendingBatchSize - 1;
            final long firstOwn = sequence + Math.floorMod(ordinal - sequence, (long) count);
            final long owned = firstOwn > last ? 0 : (last - firstOwn) / count + 1;
            if (sized && owned > 0) {
                delegate.onBatchStart(owned, queueDepth);
            }
        }
        pendingBatchSize--;
        if (sequence % count == ordinal) {
            // a later own event exists iff at least `count` events (this one's successors) remain in the batch
            delegate.onEvent(event, sequence, endOfBatch || (sized && pendingBatchSize < count));
        }
        if (endOfBatch) {
            batchOpen = false;
            pendingBatchSize = 0;
        }
    }

    @Override
    public void onBatchStart(final long batchSize, final long queueDepth) {
        this.pendingBatchSize = batchSize;
        this.queueDepth = queueDepth;
        this.batchOpen = false;
    }

    @Override
    public void onStart() {
        delegate.onStart();
    }

    @Override
    public void onShutdown() {
        delegate.onShutdown();
    }
}
