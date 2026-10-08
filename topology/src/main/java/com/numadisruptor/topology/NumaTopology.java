package com.numadisruptor.topology;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable view of the machine as seen by this process: the hardware layout plus the CPU and memory-node
 * masks the process is actually allowed to use (cgroup cpuset / Slurm binding / taskset).
 *
 * <p>All placement code must go through {@link #allowedCpus()}, {@link #allowedNodes()} and
 * {@link #allowedCpusOnNode(int)} rather than assuming that every CPU in the hardware layout is usable.
 */
public final class NumaTopology {
    /** Where the data came from. */
    public enum Source { LINUX_SYSFS, SYNTHETIC, FALLBACK_SINGLE_NODE }

    private final Source source;
    private final Map<Integer, NumaNode> nodes;
    private final Map<Integer, CpuTopology> cpus;
    private final int[][] distances; // indexed by position in nodeIds
    private final List<Integer> nodeIds;
    private final BitSet allowedCpus;
    private final BitSet allowedNodes;
    private final String cgroupCpuMax;

    public NumaTopology(
        final Source source,
        final List<NumaNode> nodeList,
        final List<CpuTopology> cpuList,
        final int[][] distanceMatrix,
        final BitSet allowedCpus,
        final BitSet allowedNodes,
        final String cgroupCpuMax) {
        this.source = source;
        final Map<Integer, NumaNode> n = new TreeMap<>();
        nodeList.forEach(x -> n.put(x.id(), x));
        final Map<Integer, CpuTopology> c = new TreeMap<>();
        cpuList.forEach(x -> c.put(x.cpuId(), x));
        this.nodes = Collections.unmodifiableMap(n);
        this.cpus = Collections.unmodifiableMap(c);
        this.nodeIds = List.copyOf(n.keySet());
        if (distanceMatrix.length != nodeIds.size()) {
            throw new IllegalArgumentException(
                "distance matrix size " + distanceMatrix.length + " != node count " + nodeIds.size());
        }
        this.distances = new int[distanceMatrix.length][];
        for (int i = 0; i < distanceMatrix.length; i++) {
            if (distanceMatrix[i].length != nodeIds.size()) {
                throw new IllegalArgumentException("distance matrix must be square");
            }
            this.distances[i] = distanceMatrix[i].clone();
        }
        this.allowedCpus = (BitSet) allowedCpus.clone();
        this.allowedCpus.and(toBitSet(cpus.keySet()));
        this.allowedNodes = (BitSet) allowedNodes.clone();
        this.allowedNodes.and(toBitSet(nodes.keySet()));
        this.cgroupCpuMax = cgroupCpuMax;
    }

    private static BitSet toBitSet(final Iterable<Integer> ids) {
        final BitSet b = new BitSet();
        ids.forEach(b::set);
        return b;
    }

    /**
     * Builds a regular synthetic machine for tests and offline planning. Node {@code n} owns CPUs
     * {@code [n*cpusPerNode, (n+1)*cpusPerNode)} with SMT siblings adjacent.
     *
     * @param sockets             number of sockets
     * @param nodesPerSocket      NUMA nodes per socket
     * @param coresPerNode        physical cores per node
     * @param threadsPerCore      SMT threads per core
     * @param intraSocketDistance Linux-style distance between different nodes of one socket
     * @param interSocketDistance Linux-style distance between nodes of different sockets
     */
    public static NumaTopology synthetic(
        final int sockets, final int nodesPerSocket, final int coresPerNode, final int threadsPerCore,
        final int intraSocketDistance, final int interSocketDistance) {
        final int nodeCount = sockets * nodesPerSocket;
        final int cpusPerNode = coresPerNode * threadsPerCore;
        final List<NumaNode> nodeList = new ArrayList<>();
        final List<CpuTopology> cpuList = new ArrayList<>();
        for (int node = 0; node < nodeCount; node++) {
            final int socket = node / nodesPerSocket;
            final List<Integer> nodeCpus = new ArrayList<>();
            for (int core = 0; core < coresPerNode; core++) {
                final int coreInSocket = (node % nodesPerSocket) * coresPerNode + core;
                final List<Integer> siblings = new ArrayList<>();
                for (int t = 0; t < threadsPerCore; t++) {
                    siblings.add(node * cpusPerNode + core * threadsPerCore + t);
                }
                for (final int cpu : siblings) {
                    cpuList.add(new CpuTopology(cpu, node, socket, coreInSocket, siblings));
                    nodeCpus.add(cpu);
                }
            }
            nodeList.add(new NumaNode(node, socket, nodeCpus, 64L << 30));
        }
        final int[][] dist = new int[nodeCount][nodeCount];
        for (int a = 0; a < nodeCount; a++) {
            for (int b = 0; b < nodeCount; b++) {
                dist[a][b] = a == b ? 10
                    : (a / nodesPerSocket == b / nodesPerSocket ? intraSocketDistance : interSocketDistance);
            }
        }
        final BitSet allCpus = new BitSet();
        cpuList.forEach(c -> allCpus.set(c.cpuId()));
        final BitSet allNodes = new BitSet();
        allNodes.set(0, nodeCount);
        return new NumaTopology(Source.SYNTHETIC, nodeList, cpuList, dist, allCpus, allNodes, null);
    }

    /**
     * The 2-socket, 8-node, 64-thread AMD EPYC 7513 machine described in the project brief, using its
     * published distance matrix. The CPU numbering is the brief's (node n = CPUs 8n..8n+7); the SMT sibling
     * pairing is an assumption (adjacent ids) because the brief does not state it, so only use this for
     * planning/tests, never as a substitute for discovery on the real machine.
     */
    public static NumaTopology epyc7513DualSocket() {
        return synthetic(2, 4, 4, 2, 12, 32);
    }

    /** Copy whose allowed masks are narrowed to {@code cpusToAllow} (e.g. a simulated Slurm cpuset). */
    public NumaTopology restrictedTo(final BitSet cpusToAllow) {
        final BitSet cpuMask = (BitSet) cpusToAllow.clone();
        cpuMask.and(allowedCpus);
        final BitSet nodeMask = new BitSet();
        cpuMask.stream().forEach(c -> nodeMask.set(cpus.get(c).nodeId()));
        nodeMask.and(allowedNodes);
        return new NumaTopology(
            source, List.copyOf(nodes.values()), List.copyOf(cpus.values()), distances, cpuMask, nodeMask, cgroupCpuMax);
    }

    public Source source() { return source; }

    public List<NumaNode> nodes() { return List.copyOf(nodes.values()); }

    public NumaNode node(final int id) {
        final NumaNode n = nodes.get(id);
        if (n == null) {
            throw new IllegalArgumentException("Unknown NUMA node " + id);
        }
        return n;
    }

    public CpuTopology cpu(final int cpuId) {
        final CpuTopology c = cpus.get(cpuId);
        if (c == null) {
            throw new IllegalArgumentException("Unknown CPU " + cpuId);
        }
        return c;
    }

    public List<CpuTopology> cpus() { return List.copyOf(cpus.values()); }

    /** Relative Linux distance (10 = local). */
    public int distance(final int nodeA, final int nodeB) {
        return distances[indexOf(nodeA)][indexOf(nodeB)];
    }

    private int indexOf(final int nodeId) {
        final int i = nodeIds.indexOf(nodeId);
        if (i < 0) {
            throw new IllegalArgumentException("Unknown NUMA node " + nodeId);
        }
        return i;
    }

    public BitSet allowedCpus() { return (BitSet) allowedCpus.clone(); }

    /** Memory nodes the process may allocate from (Mems_allowed), restricted to nodes that exist. */
    public BitSet allowedNodes() { return (BitSet) allowedNodes.clone(); }

    /** Allowed CPUs of one node, ascending. */
    public List<Integer> allowedCpusOnNode(final int nodeId) {
        final List<Integer> out = new ArrayList<>();
        for (final int cpu : node(nodeId).cpus()) {
            if (allowedCpus.get(cpu)) {
                out.add(cpu);
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Nodes that are memory-allowed and have at least one allowed CPU, ascending. */
    public List<Integer> usableNodes() {
        final List<Integer> out = new ArrayList<>();
        for (final int id : nodeIds) {
            if (allowedNodes.get(id) && !allowedCpusOnNode(id).isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }

    /** One allowed CPU per distinct allowed physical core of the node (lowest id of each core), ascending. */
    public List<Integer> allowedPhysicalCoreRepresentatives(final int nodeId) {
        final List<Integer> out = new ArrayList<>();
        final Set<Long> seen = new HashSet<>();
        for (final int cpu : allowedCpusOnNode(nodeId)) {
            if (seen.add(cpu(cpu).physicalCoreKey())) {
                out.add(cpu);
            }
        }
        return out;
    }

    /** True when more than one NUMA node is usable by this process. */
    public boolean isNuma() { return usableNodes().size() > 1; }

    /** Raw cgroup v2 {@code cpu.max} string ("max 100000" or "quota period"), or null when unavailable. */
    public String cgroupCpuMax() { return cgroupCpuMax; }

    public String describe() {
        final StringBuilder sb = new StringBuilder();
        sb.append("source=").append(source)
            .append(" nodes=").append(nodes.size())
            .append(" cpus=").append(cpus.size())
            .append(" allowedCpus=[").append(CpuList.format(allowedCpus)).append(']')
            .append(" allowedNodes=[").append(CpuList.format(allowedNodes)).append("]\n");
        for (final NumaNode n : nodes.values()) {
            sb.append("  node ").append(n.id()).append(" socket=").append(n.socketId())
                .append(" mem=").append(n.memTotalBytes() < 0 ? "?" : (n.memTotalBytes() >> 20) + "MiB")
                .append(" allowedCpus=").append(allowedCpusOnNode(n.id()))
                .append(" dist=");
            for (final int other : nodeIds) {
                sb.append(distance(n.id(), other)).append(' ');
            }
            sb.append('\n');
        }
        if (cgroupCpuMax != null) {
            sb.append("  cgroup cpu.max=").append(cgroupCpuMax).append('\n');
        }
        return sb.toString();
    }
}
