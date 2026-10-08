package com.numadisruptor.affinity;

import java.util.BitSet;
import java.util.Locale;

/**
 * Per-thread CPU affinity.
 *
 * <p>All operations apply to <b>the calling thread only</b> (Linux {@code sched_setaffinity(0, ...)} semantics:
 * pid 0 means the calling kernel thread, not the whole process). A pinned worker must therefore call
 * {@link #pinCurrentThread(BitSet)} from its own run loop; pinning from a parent thread has no effect on
 * the child. Virtual threads are rejected because they migrate between carrier threads.
 */
public interface CpuAffinity {
    /** True if this implementation can really change the OS scheduling mask. */
    boolean isSupported();

    /** Affinity mask of the calling thread. */
    BitSet currentThreadAffinity();

    /**
     * Restrict the calling thread to {@code cpus}.
     *
     * @throws AffinityException if the OS rejects the mask (for example it contains CPUs outside the job's cpuset)
     * @throws IllegalArgumentException if {@code cpus} is empty or the caller is a virtual thread
     */
    void pinCurrentThread(BitSet cpus);

    /** CPU the calling thread is executing on right now ({@code sched_getcpu}), or -1 if unknown. */
    int currentCpu();

    /** Pin the calling thread; closing the returned handle restores the previous mask. */
    default Restore pinScoped(final BitSet cpus) {
        final BitSet previous = currentThreadAffinity();
        pinCurrentThread(cpus);
        return new Restore(this, previous);
    }

    /** Restores a previously captured affinity mask on {@link #close()}. Must be closed on the pinning thread. */
    final class Restore implements AutoCloseable {
        private final CpuAffinity owner;
        private final BitSet previous;

        Restore(final CpuAffinity owner, final BitSet previous) {
            this.owner = owner;
            this.previous = previous;
        }

        @Override
        public void close() {
            owner.pinCurrentThread(previous);
        }
    }

    /** The real Linux implementation where available, otherwise an implementation that reports unsupported. */
    static CpuAffinity system() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            try {
                return new LinuxCpuAffinity();
            } catch (RuntimeException | LinkageError e) {
                return new UnsupportedCpuAffinity("native binding failed: " + e);
            }
        }
        return new UnsupportedCpuAffinity("os " + os + " has no sched_setaffinity");
    }

    /** Convenience: a one-bit mask. */
    static BitSet cpu(final int cpu) {
        final BitSet b = new BitSet();
        b.set(cpu);
        return b;
    }
}
