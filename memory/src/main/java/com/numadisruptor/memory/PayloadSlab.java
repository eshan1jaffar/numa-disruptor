package com.numadisruptor.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Fixed-size event payload slots in a {@link NumaMemoryRegion}: slot {@code i} lives at {@code i * slotBytes}. Pair slot
 * index with ring sequence ({@code sequence & (slots - 1)}) so the payload of an event sits in off-heap memory on a chosen
 * node while the small event object stays on the Java heap.
 *
 * <p>Slots are plain memory: the Disruptor's publish/consume ordering is what makes a slot written before publish
 * visible to the consumer, exactly as for the event's heap fields. Not thread-safe for concurrent access to one slot.
 */
public final class PayloadSlab {
    private final NumaMemoryRegion region;
    private final int slotBytes;
    private final long slotCount;
    private final long slotMask;

    /**
     * @param slotBytes multiple of 8
     * @param slotCount power of two, so a sequence maps to a slot with a mask
     */
    public PayloadSlab(final NumaMemoryRegion region, final int slotBytes, final long slotCount) {
        if (slotBytes <= 0 || slotBytes % 8 != 0) {
            throw new IllegalArgumentException("slotBytes must be a positive multiple of 8");
        }
        if (Long.bitCount(slotCount) != 1) {
            throw new IllegalArgumentException("slotCount must be a power of two");
        }
        if ((long) slotBytes * slotCount > region.size()) {
            throw new IllegalArgumentException("region too small: need " + (long) slotBytes * slotCount + " bytes, have " + region.size());
        }
        this.region = region;
        this.slotBytes = slotBytes;
        this.slotCount = slotCount;
        this.slotMask = slotCount - 1;
    }

    public int slotBytes() { return slotBytes; }

    public long slotCount() { return slotCount; }

    public NumaMemoryRegion region() { return region; }

    private long offset(final long sequence) {
        return (sequence & slotMask) * slotBytes;
    }

    /** Fill the slot with {@code seed + wordIndex}. */
    public void write(final long sequence, final long seed) {
        final MemorySegment seg = region.segment();
        final long base = offset(sequence);
        for (int i = 0; i < slotBytes; i += 8) {
            seg.set(ValueLayout.JAVA_LONG_UNALIGNED, base + i, seed + (i >>> 3));
        }
    }

    /** XOR of all words of the slot; reads every cache line of the payload. */
    public long checksum(final long sequence) {
        final MemorySegment seg = region.segment();
        final long base = offset(sequence);
        long c = 0;
        for (int i = 0; i < slotBytes; i += 8) {
            c ^= seg.get(ValueLayout.JAVA_LONG_UNALIGNED, base + i);
        }
        return c;
    }

    /** True if the slot holds exactly what {@link #write} stored for {@code seed}. */
    public boolean matches(final long sequence, final long seed) {
        final MemorySegment seg = region.segment();
        final long base = offset(sequence);
        for (int i = 0; i < slotBytes; i += 8) {
            if (seg.get(ValueLayout.JAVA_LONG_UNALIGNED, base + i) != seed + (i >>> 3)) {
                return false;
            }
        }
        return true;
    }
}
