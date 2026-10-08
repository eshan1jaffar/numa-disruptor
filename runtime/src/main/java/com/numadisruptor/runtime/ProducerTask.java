package com.numadisruptor.runtime;

/**
 * Application code run on a framework-created, pinned producer thread. The framework creates one thread per
 * (partition, producer) slot of the {@link WorkloadSpec} and calls {@link #run} once on it. The task owns its loop and must
 * return when it has produced everything or when {@link ProducerContext#isRunning()} turns false.
 */
@FunctionalInterface
public interface ProducerTask<E> {
    void run(ProducerContext<E> context) throws Exception;
}
