package com.numadisruptor.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A block of off-heap memory allocated with {@code numa_alloc_onnode}, bound (MPOL_BIND) to one node.
 *
 * <p><b>Lifetime.</b> The region owns its memory. {@link #close()} frees it exactly once; the {@link MemorySegment} handed
 * out by {@link #segment()} is tied to a shared {@link Arena}, so any access after close throws
 * {@link IllegalStateException} instead of touching freed memory (no use-after-free). A {@link Cleaner} frees regions that
 * are dropped without being closed, but callers should not rely on it.
 *
 * <p><b>Placement.</b> Pages are bound to the node by policy but only materialise when first written, so call
 * {@link #prefault()} (or write the memory) and then check with {@link NumaMemoryVerifier} that the pages really are there.
 */
public final class NumaMemoryRegion implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();

    /** Frees the native block and closes the arena; idempotent and shared between close() and the Cleaner. */
    private static final class Releaser implements Runnable {
        private final AtomicBoolean released = new AtomicBoolean();
        private final Arena arena;

        Releaser(final Arena arena) {
            this.arena = arena;
        }

        @Override
        public void run() {
            if (released.compareAndSet(false, true)) {
                arena.close(); // runs the numa_free cleanup registered with the segment
            }
        }
    }

    private final int node;
    private final long size;
    private final MemorySegment segment;
    private final Releaser releaser;
    private final Cleaner.Cleanable cleanable;

    NumaMemoryRegion(final int node, final long size, final MemorySegment segment, final Arena arena) {
        this.node = node;
        this.size = size;
        this.segment = segment;
        this.releaser = new Releaser(arena);
        this.cleanable = CLEANER.register(this, releaser);
    }

    /** Node the memory is bound to. */
    public int node() { return node; }

    public long size() { return size; }

    /** Accessing the returned segment after {@link #close()} throws {@link IllegalStateException}. */
    public MemorySegment segment() { return segment; }

    public boolean isClosed() { return releaser.released.get(); }

    /** Write one byte per page so every page is allocated now, under the bind policy. */
    public void prefault() {
        final int page = NumaAllocator.PAGE_SIZE;
        for (long off = 0; off < size; off += page) {
            segment.set(ValueLayout.JAVA_BYTE, off, (byte) 0);
        }
        segment.set(ValueLayout.JAVA_BYTE, size - 1, (byte) 0);
    }

    /** Frees the native memory. Safe to call repeatedly; later accesses to {@link #segment()} fail fast. */
    @Override
    public void close() {
        cleanable.clean();
    }
}
