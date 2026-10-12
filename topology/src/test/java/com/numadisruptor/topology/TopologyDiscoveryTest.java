package com.numadisruptor.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TopologyDiscoveryTest {
    @TempDir
    Path tmp;

    /** Fabricates /sys/devices/system for a 2-socket, 4-node machine: 2 cores x 2 threads per node. */
    private Path fabricateSys() throws IOException {
        final Path sys = tmp.resolve("sys");
        final int nodes = 4;
        final int cpusPerNode = 4;
        write(sys.resolve("cpu/online"), "0-15\n");
        for (int n = 0; n < nodes; n++) {
            final Path nd = sys.resolve("node/node" + n);
            write(nd.resolve("cpulist"), (n * cpusPerNode) + "-" + (n * cpusPerNode + cpusPerNode - 1) + "\n");
            write(nd.resolve("meminfo"), "Node " + n + " MemTotal:       1048576 kB\nNode " + n + " MemFree: 1 kB\n");
            final StringBuilder d = new StringBuilder();
            for (int m = 0; m < nodes; m++) {
                d.append(n == m ? 10 : (n / 2 == m / 2 ? 12 : 32)).append(m + 1 < nodes ? " " : "\n");
            }
            write(nd.resolve("distance"), d.toString());
        }
        for (int cpu = 0; cpu < 16; cpu++) {
            final int node = cpu / cpusPerNode;
            final int core = (cpu % cpusPerNode) / 2;
            final Path t = sys.resolve("cpu/cpu" + cpu + "/topology");
            write(t.resolve("physical_package_id"), (node / 2) + "\n");
            write(t.resolve("core_id"), ((node % 2) * 2 + core) + "\n");
            final int first = node * cpusPerNode + core * 2;
            write(t.resolve("thread_siblings_list"), first + "-" + (first + 1) + "\n");
        }
        return sys;
    }

    private static void write(final Path p, final String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void parsesNodesCpusSocketsAndDistances() throws IOException {
        final NumaTopology t = TopologyDiscovery.discover(fabricateSys(), null, null);

        assertEquals(4, t.nodes().size());
        assertEquals(16, t.cpus().size());
        assertEquals(List.of(0, 1, 2, 3), t.node(0).cpus());
        assertEquals(1L << 30, t.node(0).memTotalBytes());
        assertEquals(0, t.node(1).socketId());
        assertEquals(1, t.node(2).socketId());
        assertEquals(10, t.distance(2, 2));
        assertEquals(12, t.distance(0, 1));
        assertEquals(32, t.distance(1, 2));
        assertEquals(List.of(4, 5), t.cpu(4).threadSiblings());
        assertEquals(t.cpu(4).physicalCoreKey(), t.cpu(5).physicalCoreKey());
        assertFalse(t.cpu(4).physicalCoreKey() == t.cpu(6).physicalCoreKey());
        assertTrue(t.isNuma());
    }

    @Test
    void respectsAllowedMasksFromProcStatus() throws IOException {
        final Path status = tmp.resolve("status");
        Files.writeString(status, "Name:\tjava\nCpus_allowed:\tff\nCpus_allowed_list:\t2-5,8\nMems_allowed_list:\t0-1\n");
        final NumaTopology t = TopologyDiscovery.discover(fabricateSys(), status, null);

        assertEquals("2-5,8", CpuList.format(t.allowedCpus()));
        assertEquals(List.of(2, 3), t.allowedCpusOnNode(0));
        assertEquals(List.of(4, 5), t.allowedCpusOnNode(1));
        assertEquals(List.of(), t.allowedCpusOnNode(3));
        // node 2 has an allowed cpu (8) but memory node 2 is not allowed, so it is unusable
        assertEquals(List.of(0, 1), t.usableNodes());
        assertEquals(List.of(2), t.allowedPhysicalCoreRepresentatives(0)); // cpus 2,3 are one core
    }

    @Test
    void singleNodeWhenNoNodeDirectory() throws IOException {
        final Path sys = tmp.resolve("sys1");
        write(sys.resolve("cpu/online"), "0-1\n");
        write(sys.resolve("cpu/cpu0/topology/core_id"), "0\n");
        write(sys.resolve("cpu/cpu1/topology/core_id"), "1\n");
        final NumaTopology t = TopologyDiscovery.discover(sys, null, null);

        assertEquals(1, t.nodes().size());
        assertFalse(t.isNuma());
        assertEquals(List.of(0, 1), t.allowedCpusOnNode(0));
        assertEquals(List.of(0), t.cpu(0).threadSiblings());
    }

    @Test
    void wrongDistanceColumnCountIsFlaggedAsGuessed() throws IOException {
        final Path sys = fabricateSys();
        assertFalse(TopologyDiscovery.discover(sys, null, null).distancesGuessed());
        write(sys.resolve("node/node1/distance"), "12 10\n");
        final NumaTopology t = TopologyDiscovery.discover(sys, null, null);
        assertTrue(t.distancesGuessed());
        assertEquals(20, t.distance(1, 0));
        assertTrue(t.describe().contains("distances: guessed"));
        assertTrue(t.restrictedTo(CpuList.parse("0-7")).distancesGuessed());
    }

    @Test
    void coreKeyIgnoresRepeatedCoreIdsAcrossDiesOfOnePackage() {
        // same socket and same core_id 0, but different sibling pairs: two distinct physical cores
        final CpuTopology a = new CpuTopology(0, 0, 0, 0, List.of(0, 1));
        final CpuTopology a2 = new CpuTopology(1, 0, 0, 0, List.of(0, 1));
        final CpuTopology b = new CpuTopology(2, 0, 0, 0, List.of(2, 3));
        assertEquals(a.physicalCoreKey(), a2.physicalCoreKey());
        assertFalse(a.physicalCoreKey() == b.physicalCoreKey());
        assertFalse(a.physicalCoreKey() == new CpuTopology(0, 1, 1, 0, List.of(0, 1)).physicalCoreKey());
    }

    @Test
    void readsCgroupQuota() throws IOException {
        final Path max = tmp.resolve("cpu.max");
        Files.writeString(max, "200000 100000\n");
        assertEquals("200000 100000", TopologyDiscovery.discover(fabricateSys(), null, max).cgroupCpuMax());
    }

    @Test
    void cpuListRoundTrip() {
        final BitSet b = CpuList.parse("0-3, 8,10-11");
        assertEquals("0-3,8,10-11", CpuList.format(b));
        assertEquals(0, CpuList.parse("").cardinality());
        assertThrows(IllegalArgumentException.class, () -> CpuList.parse("5-2"));
    }

    @Test
    void syntheticEpycMatchesBrief() {
        final NumaTopology t = NumaTopology.epyc7513DualSocket();
        assertEquals(8, t.nodes().size());
        assertEquals(64, t.cpus().size());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), t.node(0).cpus());
        assertEquals(12, t.distance(0, 3));
        assertEquals(32, t.distance(3, 4));
        assertEquals(0, t.node(3).socketId());
        assertEquals(1, t.node(4).socketId());
        assertEquals(4, t.allowedPhysicalCoreRepresentatives(0).size());
    }

    @Test
    void restrictedToNarrowsMasks() {
        final NumaTopology t = NumaTopology.epyc7513DualSocket().restrictedTo(CpuList.parse("0-3,32-35"));
        assertEquals(List.of(0, 4), t.usableNodes());
        assertTrue(t.isNuma());
    }

    @Test
    void fallbackIsSingleNode() {
        final NumaTopology t = TopologyDiscovery.fallback();
        assertEquals(1, t.nodes().size());
        assertFalse(t.isNuma());
    }

    @Test
    void discoverOnThisMachineIsConsistent() {
        final NumaTopology t = TopologyDiscovery.discover();
        assertFalse(t.usableNodes().isEmpty());
        assertTrue(t.allowedCpus().cardinality() > 0);
    }
}
