package com.numadisruptor.runtime;

import com.lmax.disruptor.RingBuffer;
import com.numadisruptor.affinity.CpuAffinity;
import com.numadisruptor.affinity.PinnedThreadFactory;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Owns the producer worker threads. The Disruptor itself never creates producers; this class does: it creates one platform
 * thread per (partition, producer) slot, pins it (when a CPU assignment exists) and runs the application's
 * {@link ProducerTask} on it.
 */
final class ProducerScheduler<E> {
    private final List<ProducerContext<E>> contexts = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final PinnedThreadFactory pinnedFactory; // null when unpinned
    private CountDownLatch done;

    /**
     * @param rings0       ring 0 of each partition
     * @param cpus         per global producer id, the CPU to pin to, or null for no pinning
     */
    ProducerScheduler(
        final WorkloadSpec spec, final List<RingBuffer<E>> rings0, final int[] cpus,
        final CpuAffinity affinity, final BooleanSupplier running) {
        if (cpus != null) {
            final List<BitSet> sets = new ArrayList<>();
            for (final int c : cpus) {
                sets.add(CpuAffinity.cpu(c));
            }
            pinnedFactory = new PinnedThreadFactory("producer", affinity, sets);
        } else {
            pinnedFactory = null;
        }
        int id = 0;
        for (int p = 0; p < spec.partitions(); p++) {
            for (int k = 0; k < spec.producersPerPartition(); k++) {
                contexts.add(new ProducerContext<>(p, id++, k, rings0.get(p), running));
            }
        }
    }

    int producerCount() { return contexts.size(); }

    List<ProducerContext<E>> contexts() { return List.copyOf(contexts); }

    /** Start all producer threads; returns after they have pinned (or failed to). */
    void start(final ProducerTask<E> task) throws InterruptedException {
        done = new CountDownLatch(contexts.size());
        for (final ProducerContext<E> ctx : contexts) {
            final Runnable body = () -> {
                try {
                    task.run(ctx);
                } catch (final Throwable t) {
                    failures.add(t);
                } finally {
                    ctx.flushCounters();
                    done.countDown();
                }
            };
            final Thread t = pinnedFactory != null
                ? pinnedFactory.newThread(body)
                : daemon(new Thread(body, "producer-" + ctx.producerId()));
            threads.add(t);
        }
        for (final Thread t : threads) {
            t.start();
        }
        if (pinnedFactory != null) {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (pinnedFactory.pinnedCount() < contexts.size() && pinnedFactory.failures().isEmpty()) {
                if (System.nanoTime() > deadline) {
                    throw new PlacementException("producer threads did not pin within 10s");
                }
                Thread.sleep(1);
            }
            if (!pinnedFactory.failures().isEmpty()) {
                // pinned threads that failed never run the body, so release their latch slots
                for (int i = 0; i < pinnedFactory.failures().size(); i++) {
                    done.countDown();
                }
                throw new PlacementException("producer pinning failed", pinnedFactory.failures().get(0));
            }
        }
    }

    private static Thread daemon(final Thread t) {
        t.setDaemon(true);
        return t;
    }

    /** Wait for all tasks to return. @return true if they all did within the timeout */
    boolean await(final long timeout, final TimeUnit unit) throws InterruptedException {
        return done == null || done.await(timeout, unit);
    }

    List<Throwable> failures() { return List.copyOf(failures); }

    long totalStalls() {
        long s = 0;
        for (final ProducerContext<E> c : contexts) {
            s += c.stalls();
        }
        return s;
    }

    long totalPublished() {
        long s = 0;
        for (final ProducerContext<E> c : contexts) {
            s += c.publishedExact();
        }
        return s;
    }

    void interruptAll() {
        threads.forEach(Thread::interrupt);
    }
}
