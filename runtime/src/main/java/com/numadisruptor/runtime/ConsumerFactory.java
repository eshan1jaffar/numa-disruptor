package com.numadisruptor.runtime;

import com.lmax.disruptor.EventHandler;

/** Creates the final-stage handler for one consumer slot. Each slot gets its own handler instance and thread. */
@FunctionalInterface
public interface ConsumerFactory<E> {
    EventHandler<E> create(int partition, int consumerIndex);
}
