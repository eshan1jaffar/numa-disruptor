package com.numadisruptor.affinity;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates platform threads that pin <i>themselves</i> to a pre-assigned CPU set before running their task.
 *
 * <p>Assignments are consumed in order: the n-th created thread gets the n-th mask. The LMAX Disruptor DSL asks a
 * ThreadFactory for one thread per event handler in registration order, so a plan can address handlers by position.
 * Running out of assignments is an error (never silently unpinned or shared).
 *
 * <p>If pinning fails the thread records the failure in {@link #failures()} and exits without running the task
 * (strict, the default). Callers must check {@link #failures()}/{@link #pinnedCount()} after starting workers,
 * otherwise a consumer that never ran would look like a hung pipeline.
 */
public final class PinnedThreadFactory implements ThreadFactory {
    private final String prefix;
    private final CpuAffinity affinity;
    private final List<BitSet> assignments;
    private final AtomicInteger created = new AtomicInteger();
    private final AtomicInteger pinned = new AtomicInteger();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final List<AffinityVerifier.Result> verifications = new CopyOnWriteArrayList<>();
    private final boolean verify;

    public PinnedThreadFactory(final String prefix, final CpuAffinity affinity, final List<BitSet> assignments) {
        this(prefix, affinity, assignments, true);
    }

    public PinnedThreadFactory(
        final String prefix, final CpuAffinity affinity, final List<BitSet> assignments, final boolean verify) {
        this.prefix = prefix;
        this.affinity = affinity;
        final List<BitSet> copy = new ArrayList<>();
        assignments.forEach(a -> copy.add((BitSet) a.clone()));
        this.assignments = List.copyOf(copy);
        this.verify = verify;
    }

    /** One thread per CPU id, in order. */
    public static PinnedThreadFactory forCpus(final String prefix, final CpuAffinity affinity, final int... cpus) {
        final List<BitSet> sets = new ArrayList<>();
        for (final int c : cpus) {
            sets.add(CpuAffinity.cpu(c));
        }
        return new PinnedThreadFactory(prefix, affinity, sets);
    }

    @Override
    public Thread newThread(final Runnable task) {
        final int index = created.getAndIncrement();
        if (index >= assignments.size()) {
            created.decrementAndGet();
            throw new IllegalStateException(
                prefix + ": requested thread #" + index + " but only " + assignments.size() + " CPU assignments were provided");
        }
        final BitSet mask = assignments.get(index);
        final Thread t = new Thread(() -> {
            try {
                affinity.pinCurrentThread(mask);
                if (verify) {
                    final AffinityVerifier.Result r = AffinityVerifier.verifyCurrentThread(affinity, mask);
                    verifications.add(r);
                    if (!r.ok()) {
                        throw new AffinityException(Thread.currentThread().getName() + ": " + r.message());
                    }
                }
            } catch (final Throwable e) {
                failures.add(e);
                return;
            }
            pinned.incrementAndGet();
            task.run();
        }, prefix + "-" + index);
        t.setDaemon(true);
        return t;
    }

    /** Number of threads that pinned (and verified) successfully so far. */
    public int pinnedCount() {
        return pinned.get();
    }

    public int createdCount() {
        return created.get();
    }

    public List<Throwable> failures() {
        return List.copyOf(failures);
    }

    public List<AffinityVerifier.Result> verifications() {
        return List.copyOf(verifications);
    }
}
