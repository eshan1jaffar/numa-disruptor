package com.numadisruptor.optimizer;

import com.numadisruptor.topology.NumaTopology;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The placement problem reduced to its decision variables: colocation groups ("clusters") assigned to usable nodes under
 * per-node CPU and memory capacity. Shared by every optimizer so they agree on feasibility.
 */
final class ClusterProblem {
    record Cluster(int index, List<String> vertexIds, int cpuDemand, long memoryBytes, List<Integer> candidateNodes) { }

    final CommunicationGraph graph;
    final NumaTopology topology;
    final PlacementConstraints constraints;
    final PlacementCostModel model;
    final List<Cluster> clusters;
    final Map<String, Integer> clusterOfVertex = new HashMap<>();
    final List<Integer> nodes;
    final Map<Integer, Integer> cpuCapacity = new HashMap<>();
    final Map<Integer, Long> memCapacity = new HashMap<>();
    /** Edges between two different clusters, indexed by cluster. Intra-cluster edges are always local. */
    final List<List<CommunicationEdge>> incident = new ArrayList<>();

    ClusterProblem(
        final CommunicationGraph graph, final NumaTopology topology,
        final PlacementCostModel model, final PlacementConstraints constraints) {
        this.graph = graph;
        this.topology = topology;
        this.model = model;
        this.constraints = constraints;
        this.nodes = topology.usableNodes();
        if (nodes.isEmpty()) {
            throw new InfeasiblePlacementException("no NUMA node has both an allowed CPU and allowed memory");
        }
        for (final int n : nodes) {
            cpuCapacity.put(n, cpuCapacity(topology, constraints, n));
            memCapacity.put(n, topology.node(n).memTotalBytes() < 0 ? Long.MAX_VALUE : topology.node(n).memTotalBytes());
        }

        // group vertices into clusters
        final Map<String, List<CommunicationVertex>> groups = new TreeMap<>();
        for (final CommunicationVertex v : graph.vertices().values()) {
            final String key = v.colocationGroup() != null ? "g:" + v.colocationGroup() : "v:" + v.id();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
        }
        final List<List<CommunicationVertex>> ordered = new ArrayList<>(groups.values());
        ordered.sort((a, b) -> a.get(0).id().compareTo(b.get(0).id())); // vertices() is sorted, so get(0) is the smallest
        final List<Cluster> built = new ArrayList<>();
        for (final List<CommunicationVertex> members : ordered) {
            final int idx = built.size();
            final List<String> ids = new ArrayList<>();
            int cpu = 0;
            long mem = 0;
            final Set<Integer> candidates = new TreeSet<>(nodes);
            for (final CommunicationVertex m : members) {
                ids.add(m.id());
                cpu += m.cpuDemand();
                mem += m.memoryBytes();
                if (!m.allowedNodes().isEmpty()) {
                    candidates.retainAll(m.allowedNodes());
                }
                clusterOfVertex.put(m.id(), idx);
            }
            if (candidates.isEmpty()) {
                throw new InfeasiblePlacementException(
                    "no usable node satisfies the allowed-node constraints of " + ids + " (usable nodes: " + nodes + ")");
            }
            final int fitCpu = cpu;
            final long fitMem = mem;
            final List<Integer> fitting = candidates.stream()
                .filter(n -> fitCpu <= cpuCapacity.get(n) && fitMem <= memCapacity.get(n)).toList();
            if (fitting.isEmpty()) {
                throw new InfeasiblePlacementException(
                    "cluster " + ids + " (cpus=" + cpu + ", mem=" + mem + "B) does not fit on any single allowed node");
            }
            built.add(new Cluster(idx, List.copyOf(ids), cpu, mem, fitting));
        }
        this.clusters = List.copyOf(built);
        for (int i = 0; i < clusters.size(); i++) {
            incident.add(new ArrayList<>());
        }
        for (final CommunicationEdge e : graph.edges()) {
            final int a = clusterOfVertex.get(e.from());
            final int b = clusterOfVertex.get(e.to());
            if (a != b) {
                incident.get(a).add(e);
                incident.get(b).add(e);
            }
        }
        final int totalCpu = clusters.stream().mapToInt(Cluster::cpuDemand).sum();
        final int totalCap = nodes.stream().mapToInt(cpuCapacity::get).sum();
        if (totalCpu > totalCap) {
            throw new InfeasiblePlacementException("workload needs " + totalCpu + " dedicated cpus but only " + totalCap
                + " are available across usable nodes " + nodes
                + (constraints.allowSmtSharing() ? "" : " (SMT sharing disabled)"));
        }
    }

    static int cpuCapacity(final NumaTopology topology, final PlacementConstraints constraints, final int node) {
        return CpuAssigner.orderedPool(topology, constraints, node).size();
    }

    /** Sum of weights of edges touching a cluster (ordering key: heavy clusters are placed first). */
    double incidentWeight(final int cluster) {
        double s = 0;
        for (final CommunicationEdge e : incident.get(cluster)) {
            s += model.weight(e);
        }
        return s;
    }

    /** Cost of the cluster's edges to already-assigned neighbours if it were on {@code node}. */
    double incidentCost(final int cluster, final int node, final int[] assignment) {
        double s = 0;
        for (final CommunicationEdge e : incident.get(cluster)) {
            final int from = clusterOfVertex.get(e.from());
            final int to = clusterOfVertex.get(e.to());
            final int fromNode = from == cluster ? node : assignment[from];
            final int toNode = to == cluster ? node : assignment[to];
            if (fromNode < 0 || toNode < 0) {
                continue;
            }
            s += model.edgeCost(e, fromNode, toNode);
        }
        return s;
    }

    double totalCost(final int[] assignment) {
        double s = 0;
        for (final CommunicationEdge e : graph.edges()) {
            s += model.edgeCost(e, assignment[clusterOfVertex.get(e.from())], assignment[clusterOfVertex.get(e.to())]);
        }
        return s;
    }

    boolean fits(final Cluster c, final int node, final int[] cpuUsed, final long[] memUsed) {
        final int ni = nodes.indexOf(node);
        return cpuUsed[ni] + c.cpuDemand() <= cpuCapacity.get(node)
            && memUsed[ni] + c.memoryBytes() <= memCapacity.get(node)
            && memUsed[ni] + c.memoryBytes() >= 0; // overflow guard when capacity is unbounded
    }

    int[] newAssignment() {
        final int[] a = new int[clusters.size()];
        java.util.Arrays.fill(a, -1);
        return a;
    }

    /** Turn a complete cluster-to-node assignment into a validated plan. */
    PlacementPlan toPlan(final int[] assignment, final String optimizerName) {
        final Map<String, Integer> nodeByVertex = new LinkedHashMap<>();
        for (final Cluster c : clusters) {
            for (final String v : c.vertexIds()) {
                nodeByVertex.put(v, assignment[c.index()]);
            }
        }
        final CpuAssigner.Result cpus = CpuAssigner.assign(graph, nodeByVertex, topology, constraints);
        final PlacementPlan plan = new PlacementPlan(
            nodeByVertex, cpus.cpuByVertex(), model.totalCost(graph, nodeByVertex), optimizerName, cpus.warnings());
        final List<String> violations = plan.validate(graph, topology, constraints);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("optimizer " + optimizerName + " produced an invalid plan: " + violations);
        }
        return plan;
    }

    /** Recover the cluster assignment from a plan (used by refinement optimizers). */
    int[] assignmentOf(final PlacementPlan plan) {
        final int[] a = new int[clusters.size()];
        for (final Cluster c : clusters) {
            a[c.index()] = plan.nodeOf(c.vertexIds().get(0));
        }
        return a;
    }

    void usage(final int[] assignment, final int[] cpuUsed, final long[] memUsed) {
        java.util.Arrays.fill(cpuUsed, 0);
        java.util.Arrays.fill(memUsed, 0);
        for (final Cluster c : clusters) {
            if (assignment[c.index()] >= 0) {
                final int ni = nodes.indexOf(assignment[c.index()]);
                cpuUsed[ni] += c.cpuDemand();
                memUsed[ni] += c.memoryBytes();
            }
        }
    }
}
