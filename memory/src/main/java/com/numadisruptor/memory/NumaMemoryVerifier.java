package com.numadisruptor.memory;

import java.util.Map;
import java.util.TreeMap;

/**
 * Checks where the pages of a region physically reside, using {@code numa_move_pages} in query mode (no pages move).
 * This is the only trustworthy evidence of placement: a successful {@code numa_alloc_onnode} does not prove it, because the
 * bind policy can be rejected (for example node not in the cpuset's allowed memory) and pages exist only after first touch.
 */
public final class NumaMemoryVerifier {
    private static final int CHUNK = 1024;

    private final NumaAllocator allocator;

    public NumaMemoryVerifier(final NumaAllocator allocator) {
        this.allocator = allocator;
    }

    /**
     * Residency of a sample of the region's pages.
     *
     * @param expectedNode   node the region was bound to
     * @param pagesByNode    sampled pages found on each node
     * @param unmappedPages  sampled pages not yet allocated (never touched) or otherwise unresolvable
     * @param sampledPages   total pages inspected
     */
    public record Report(int expectedNode, Map<Integer, Long> pagesByNode, long unmappedPages, long sampledPages) {
        /** True if at least one page was inspected, none are unresolved and all are on the expected node. */
        public boolean allOnExpectedNode() {
            return sampledPages > 0 && unmappedPages == 0
                && pagesByNode.getOrDefault(expectedNode, 0L) == sampledPages;
        }

        public long pagesOnExpectedNode() {
            return pagesByNode.getOrDefault(expectedNode, 0L);
        }
    }

    /** Inspect up to {@code maxSamples} evenly spaced pages of the region. */
    public Report verify(final NumaMemoryRegion region, final int maxSamples) {
        if (region.isClosed()) {
            throw new IllegalStateException("region is closed");
        }
        final long pages = (region.size() + NumaAllocator.PAGE_SIZE - 1) / NumaAllocator.PAGE_SIZE;
        final long samples = Math.min(pages, Math.max(1, maxSamples));
        final long stride = Math.max(1, pages / samples);
        final long base = region.segment().address();

        final Map<Integer, Long> byNode = new TreeMap<>();
        long unmapped = 0;
        long inspected = 0;
        final long[] addrs = new long[CHUNK];
        int n = 0;
        for (long page = 0; page < pages && inspected + n < samples; page += stride) {
            addrs[n++] = base + page * NumaAllocator.PAGE_SIZE;
            if (n == CHUNK) {
                unmapped += tally(allocator.queryPageNodes(addrs.clone()), byNode);
                inspected += n;
                n = 0;
            }
        }
        if (n > 0) {
            final long[] tail = new long[n];
            System.arraycopy(addrs, 0, tail, 0, n);
            unmapped += tally(allocator.queryPageNodes(tail), byNode);
            inspected += n;
        }
        return new Report(region.node(), byNode, unmapped, inspected);
    }

    private static long tally(final int[] status, final Map<Integer, Long> byNode) {
        long unmapped = 0;
        for (final int s : status) {
            if (s >= 0) {
                byNode.merge(s, 1L, Long::sum);
            } else {
                unmapped++; // -ENOENT: page not present yet; other negatives: not queryable
            }
        }
        return unmapped;
    }
}
