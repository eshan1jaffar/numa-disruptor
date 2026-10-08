package com.numadisruptor.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
class NumaMemoryTest {
    @TempDir
    Path tmp;

    @Test
    void missingLibraryIsReportedAsUnavailableNotCrash() {
        assertThrows(NumaUnavailableException.class, () -> NumaAllocator.open(List.of("libdefinitely-not-numa.so.9")));
        assertThrows(NumaUnavailableException.class, () -> NumaAllocator.open(List.of()));
    }

    @Test
    void numaMapsParsingSumsPagesPerNodeAndFiltersSmallMappings() throws Exception {
        final Path maps = tmp.resolve("numa_maps");
        Files.writeString(maps, String.join("\n",
            "7f0000000000 default anon=1000 dirty=1000 N0=600 N1=400 kernelpagesize_kB=4",
            "7f0000100000 default anon=2 dirty=2 N0=2 kernelpagesize_kB=4",
            "7f0000200000 default anon=512 dirty=512 N1=512 kernelpagesize_kB=2048",
            ""));
        assertEquals(Map.of(0, 2408L, 1, 1_048_576L + 1600L), NumaMapsReader.residentKiBByNode(maps, 0));
        assertEquals(Map.of(0, 2400L, 1, 1_048_576L + 1600L), NumaMapsReader.residentKiBByNode(maps, 100));
        assertTrue(NumaMapsReader.residentKiBByNode(tmp.resolve("missing"), 0).isEmpty());
    }

    // ---- everything below needs real libnuma on Linux (e.g. WSL, CI, the HPC node) ----

    private static NumaAllocator allocatorOrSkip() {
        try {
            return NumaAllocator.open();
        } catch (final NumaUnavailableException e) {
            assumeTrue(false, "libnuma unavailable: " + e.getMessage());
            return null;
        }
    }

    @Test
    @Tag("hardware")
    void allocatedRegionIsResidentOnRequestedNode() {
        final NumaAllocator numa = allocatorOrSkip();
        try (NumaMemoryRegion r = numa.allocateOnNode(8L << 20, 0)) {
            final NumaMemoryVerifier v = new NumaMemoryVerifier(numa);
            assertTrue(v.verify(r, 4096).unmappedPages() > 0 || v.verify(r, 4096).sampledPages() > 0);
            r.prefault();
            final NumaMemoryVerifier.Report rep = v.verify(r, 4096);
            assertTrue(rep.allOnExpectedNode(), rep.toString());
            assertEquals(rep.sampledPages(), rep.pagesOnExpectedNode());
        }
    }

    @Test
    @Tag("hardware")
    void untouchedPagesAreReportedUnresolvedNotAsPlaced() {
        final NumaAllocator numa = allocatorOrSkip();
        try (NumaMemoryRegion r = numa.allocateOnNode(4L << 20, 0)) {
            final NumaMemoryVerifier.Report before = new NumaMemoryVerifier(numa).verify(r, 1024);
            assertFalse(before.allOnExpectedNode(), "never-touched pages must not be claimed as resident: " + before);
            assertTrue(before.unmappedPages() > 0, before.toString());
        }
    }

    @Test
    @Tag("hardware")
    void useAfterCloseFailsFastAndDoubleCloseIsSafe() {
        final NumaAllocator numa = allocatorOrSkip();
        final NumaMemoryRegion r = numa.allocateOnNode(1 << 20, 0);
        final var seg = r.segment();
        seg.set(ValueLayout.JAVA_LONG, 0, 42L);
        assertEquals(42L, seg.get(ValueLayout.JAVA_LONG, 0));
        r.close();
        assertTrue(r.isClosed());
        assertThrows(IllegalStateException.class, () -> seg.get(ValueLayout.JAVA_LONG, 0), "use after free must throw");
        assertThrows(IllegalStateException.class, () -> seg.set(ValueLayout.JAVA_LONG, 0, 1L));
        r.close(); // idempotent
        assertThrows(IllegalStateException.class, () -> new NumaMemoryVerifier(numa).verify(r, 16));
    }

    @Test
    @Tag("hardware")
    void badArgumentsAndRefusedAllocationsAreRejected() {
        final NumaAllocator numa = allocatorOrSkip();
        assertThrows(IllegalArgumentException.class, () -> numa.allocateOnNode(0, 0));
        assertThrows(IllegalArgumentException.class, () -> numa.allocateOnNode(-5, 0));
        assertThrows(IllegalArgumentException.class, () -> numa.allocateOnNode(4096, -1));
        assertThrows(IllegalArgumentException.class, () -> numa.allocateOnNode(4096, numa.maxNode() + 1));
        assertThrows(NumaAllocationException.class, () -> numa.allocateOnNode(1L << 46, 0), "64 TiB must be refused");
    }

    @Test
    @Tag("hardware")
    void payloadSlabRoundTripsAndWrapsBySequence() {
        final NumaAllocator numa = allocatorOrSkip();
        try (NumaMemoryRegion r = numa.allocateOnNode(64L * 16, 0)) {
            r.prefault();
            final PayloadSlab slab = new PayloadSlab(r, 64, 16);
            slab.write(3, 1000);
            assertTrue(slab.matches(3, 1000));
            assertTrue(slab.matches(19, 1000), "sequence 19 wraps to the same slot as 3");
            assertFalse(slab.matches(3, 1001));
            slab.write(4, 7);
            assertTrue(slab.matches(3, 1000), "neighbouring slots do not overlap");
            assertThrows(IllegalArgumentException.class, () -> new PayloadSlab(r, 64, 17));
            assertThrows(IllegalArgumentException.class, () -> new PayloadSlab(r, 64, 32), "slab larger than region");
            assertThrows(IllegalArgumentException.class, () -> new PayloadSlab(r, 12, 16));
        }
    }

    @Test
    @Tag("hardware")
    void manyAllocateCloseCyclesDoNotLeak() {
        final NumaAllocator numa = allocatorOrSkip();
        final long before = vmSizeKiB();
        for (int i = 0; i < 2000; i++) {
            try (NumaMemoryRegion r = numa.allocateOnNode(16L << 20, 0)) {
                r.segment().set(ValueLayout.JAVA_BYTE, 0, (byte) 1); // touch one page only
            }
        }
        final long grewKiB = vmSizeKiB() - before;
        // 2000 x 16 MiB = 32 GiB of address space if leaked; freed regions must leave the process size nearly unchanged
        assertTrue(grewKiB < (512L << 10), "virtual size grew by " + grewKiB + " KiB, regions leak");
    }

    private static long vmSizeKiB() {
        try {
            for (final String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmSize:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("no VmSize");
    }
}
