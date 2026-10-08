package com.numadisruptor.runtime;

import com.lmax.disruptor.EventHandler;

/**
 * Work distribution on a Disruptor ring: worker {@code ordinal} of {@code count} processes only events whose ring sequence
 * satisfies {@code sequence % count == ordinal}, so each event is handled by exactly one worker. All workers still advance
 * their sequence over every slot, so the ring can wrap normally. (Disruptor 4.0 has no WorkerPool; this is the documented
 * replacement.)
 */
final class WorkDistributionHandler<E> implements EventHandler<E> {
    private final EventHandler<E> delegate;
    private final int ordinal;
    private final int count;

    WorkDistributionHandler(final EventHandler<E> delegate, final int ordinal, final int count) {
        this.delegate = delegate;
        this.ordinal = ordinal;
        this.count = count;
    }

    @Override
    public void onEvent(final E event, final long sequence, final boolean endOfBatch) throws Exception {
        if (sequence % count == ordinal) {
            delegate.onEvent(event, sequence, endOfBatch);
        }
    }

    @Override
    public void onBatchStart(final long batchSize, final long queueDepth) {
        delegate.onBatchStart(batchSize, queueDepth);
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
