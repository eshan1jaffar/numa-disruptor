package com.numadisruptor.runtime;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.numadisruptor.affinity.CpuAffinity;
import com.numadisruptor.affinity.PinnedThreadFactory;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds one Disruptor ring where the plan says it should live.
 *
 * <p>Java gives no API to place a heap object on a NUMA node. What this factory can do, and does, is <b>construct the ring on a
 * thread pinned to the target node</b>, so the event array and the events it preallocates are first allocated and written
 * by a thread running there (first-touch). Whether the pages actually stay on that node depends on the JVM (for example
 * {@code -XX:+UseNUMA}, {@code -XX:+AlwaysPreTouch}) and on later GC copying; measure placement with
 * {@code NumaMapsReader} instead of assuming it.
 */
final class NumaDisruptorFactory {
    private NumaDisruptorFactory() { }

    /** A built ring plus the thread factory its handlers will be started with. */
    record Ring<E>(Disruptor<E> disruptor, PinnedThreadFactory pinned, int handlerCount) { }

    private static final AtomicInteger UNPINNED_THREAD_IDS = new AtomicInteger();

    /**
     * @param name         used for thread names
     * @param constructOn  CPUs of the ring's node to build on (first-touch), or null to build on the calling thread
     * @param handlerCpus  CPU per handler in registration order, or null for unpinned handler threads
     */
    static <E> Ring<E> create(
        final String name, final EventFactory<E> events, final int size, final ProducerType type, final WaitStrategy wait,
        final CpuAffinity affinity, final BitSet constructOn, final List<Integer> handlerCpus, final int handlerCount) {
        final ThreadFactory threads;
        final PinnedThreadFactory pinned;
        if (handlerCpus != null) {
            final List<BitSet> sets = handlerCpus.stream().map(CpuAffinity::cpu).toList();
            pinned = new PinnedThreadFactory(name, affinity, sets);
            threads = pinned;
        } else {
            pinned = null;
            threads = r -> {
                final Thread t = new Thread(r, name + "-" + UNPINNED_THREAD_IDS.getAndIncrement());
                t.setDaemon(true);
                return t;
            };
        }

        final Disruptor<E> disruptor;
        if (constructOn == null) {
            disruptor = new Disruptor<>(events, size, threads, type, wait);
        } else {
            final AtomicReference<Disruptor<E>> built = new AtomicReference<>();
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final Thread init = new Thread(() -> {
                try {
                    affinity.pinCurrentThread(constructOn);
                    built.set(new Disruptor<>(events, size, threads, type, wait));
                } catch (final Throwable t) {
                    failure.set(t);
                }
            }, name + "-init");
            init.start();
            try {
                init.join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PlacementException("interrupted while constructing ring " + name, e);
            }
            if (failure.get() != null) {
                throw new PlacementException("could not construct ring " + name + " on its node", failure.get());
            }
            disruptor = built.get();
        }
        return new Ring<>(disruptor, pinned, handlerCount);
    }
}
