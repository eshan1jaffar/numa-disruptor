package com.numadisruptor.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Locale;

/**
 * Allocates off-heap memory on a chosen NUMA node through libnuma, bound with FFM downcalls (no JNI).
 *
 * <p>{@code Arena.allocate()} alone gives ordinary malloc-style memory with <b>no</b> node policy; only
 * {@code numa_alloc_onnode} (which mmaps and applies {@code mbind(MPOL_BIND)}) provides it, which is why this class exists.
 * Obtain one with {@link #open()}; if libnuma is missing it throws {@link NumaUnavailableException} and callers fall back
 * to heap-only operation. The allocator itself is stateless and thread-safe; it only holds method handles.
 */
public final class NumaAllocator {
    /** Conventional small-page size; used only as a stride for prefaulting and residency sampling. */
    static final int PAGE_SIZE = 4096;

    private static final List<String> LIBRARY_NAMES = List.of("libnuma.so.1", "libnuma.so");

    private final MethodHandle allocOnNode;
    private final MethodHandle free;
    private final MethodHandle maxNode;
    private final MethodHandle movePages;

    private NumaAllocator(final SymbolLookup lib) {
        final Linker linker = Linker.nativeLinker();
        allocOnNode = linker.downcallHandle(find(lib, "numa_alloc_onnode"),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        free = linker.downcallHandle(find(lib, "numa_free"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        maxNode = linker.downcallHandle(find(lib, "numa_max_node"), FunctionDescriptor.of(ValueLayout.JAVA_INT));
        movePages = linker.downcallHandle(find(lib, "numa_move_pages"),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        final MethodHandle available = linker.downcallHandle(find(lib, "numa_available"), FunctionDescriptor.of(ValueLayout.JAVA_INT));
        try {
            if ((int) available.invokeExact() < 0) {
                throw new NumaUnavailableException("numa_available() < 0: the kernel has no NUMA support");
            }
        } catch (final NumaUnavailableException e) {
            throw e;
        } catch (final Throwable t) {
            throw new NumaUnavailableException("numa_available failed", t);
        }
    }

    private static MemorySegment find(final SymbolLookup lib, final String name) {
        return lib.find(name).orElseThrow(() -> new NumaUnavailableException("libnuma has no symbol " + name));
    }

    /** True if {@link #open()} would succeed on this host. */
    public static boolean isAvailable() {
        try {
            open();
            return true;
        } catch (final NumaUnavailableException e) {
            return false;
        }
    }

    public static NumaAllocator open() {
        return open(LIBRARY_NAMES);
    }

    /** @param libraryNames shared-library names/paths to try in order (overridable for tests) */
    public static NumaAllocator open(final List<String> libraryNames) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            throw new NumaUnavailableException("libnuma requires Linux");
        }
        RuntimeException last = null;
        for (final String name : libraryNames) {
            try {
                return new NumaAllocator(SymbolLookup.libraryLookup(name, Arena.global()));
            } catch (final IllegalArgumentException e) {
                last = new NumaUnavailableException("cannot load " + name, e);
            } catch (final NumaUnavailableException e) {
                last = e;
            }
        }
        throw last != null ? last : new NumaUnavailableException("no library names given");
    }

    /** Highest node number known to libnuma. */
    public int maxNode() {
        try {
            return (int) maxNode.invokeExact();
        } catch (final Throwable t) {
            throw new NumaAllocationException("numa_max_node failed: " + t);
        }
    }

    /**
     * Allocate {@code bytes} bound to {@code node}. Memory is zero-filled lazily by the kernel and pages appear on first
     * touch; call {@link NumaMemoryRegion#prefault()} to materialise them, then verify with {@link NumaMemoryVerifier}.
     *
     * @throws IllegalArgumentException if {@code bytes <= 0} or {@code node} is outside {@code [0, maxNode]}
     * @throws NumaAllocationException  if the OS refuses the allocation
     */
    public NumaMemoryRegion allocateOnNode(final long bytes, final int node) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("bytes must be positive, was " + bytes);
        }
        if (node < 0 || node > maxNode()) {
            throw new IllegalArgumentException("node " + node + " outside [0, " + maxNode() + "]");
        }
        final MemorySegment ptr;
        try {
            ptr = (MemorySegment) allocOnNode.invokeExact(bytes, node);
        } catch (final Throwable t) {
            throw new NumaAllocationException("numa_alloc_onnode failed: " + t);
        }
        if (ptr.equals(MemorySegment.NULL)) {
            throw new NumaAllocationException("numa_alloc_onnode(" + bytes + " bytes, node " + node + ") returned NULL");
        }
        final Arena arena = Arena.ofShared();
        final MemorySegment segment = ptr.reinterpret(bytes, arena, s -> {
            try {
                free.invokeExact(s, bytes);
            } catch (final Throwable t) {
                throw new AssertionError("numa_free failed", t);
            }
        });
        return new NumaMemoryRegion(node, bytes, segment, arena);
    }

    /** Query the node of each page via {@code numa_move_pages}; status entries are node ids or negative errno values. */
    int[] queryPageNodes(final long[] pageAddresses) {
        final int count = pageAddresses.length;
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment pages = arena.allocate(8L * count, 8);
            final MemorySegment status = arena.allocate(4L * count, 4);
            for (int i = 0; i < count; i++) {
                pages.setAtIndex(ValueLayout.JAVA_LONG, i, pageAddresses[i]);
            }
            final long rc = (long) movePages.invokeExact(0, (long) count, pages, MemorySegment.NULL, status, 0);
            if (rc < 0) {
                throw new NumaAllocationException("numa_move_pages query failed, rc=" + rc);
            }
            final int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                out[i] = status.getAtIndex(ValueLayout.JAVA_INT, i);
            }
            return out;
        } catch (final NumaAllocationException e) {
            throw e;
        } catch (final Throwable t) {
            throw new NumaAllocationException("numa_move_pages failed: " + t);
        }
    }
}
