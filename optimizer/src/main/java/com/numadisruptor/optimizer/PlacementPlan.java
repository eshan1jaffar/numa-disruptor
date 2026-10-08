package com.numadisruptor.optimizer;

import com.numadisruptor.topology.NumaTopology;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Result of placement: a NUMA node for every vertex and a dedicated CPU for every worker vertex.
 */
public final class PlacementPlan {
    private final Map<String, Integer> nodeByVertex;
    private final Map<String, Integer> cpuByVertex;
    private final double modelCost;
    private final String optimizerName;
    private final List<String> warnings;

    public PlacementPlan(
        final Map<String, Integer> nodeByVertex, final Map<String, Integer> cpuByVertex,
        final double modelCost, final String optimizerName, final List<String> warnings) {
        this.nodeByVertex = Collections.unmodifiableMap(new TreeMap<>(nodeByVertex));
        this.cpuByVertex = Collections.unmodifiableMap(new TreeMap<>(cpuByVertex));
        this.modelCost = modelCost;
        this.optimizerName = optimizerName;
        this.warnings = List.copyOf(warnings);
    }

    public int nodeOf(final String vertexId) {
        final Integer n = nodeByVertex.get(vertexId);
        if (n == null) {
            throw new IllegalArgumentException("vertex " + vertexId + " is not part of this plan");
        }
        return n;
    }

    /** CPU of a worker vertex. */
    public int cpuOf(final String vertexId) {
        final Integer c = cpuByVertex.get(vertexId);
        if (c == null) {
            throw new IllegalArgumentException("vertex " + vertexId + " has no CPU assignment");
        }
        return c;
    }

    public boolean hasCpu(final String vertexId) { return cpuByVertex.containsKey(vertexId); }

    public Map<String, Integer> nodes() { return nodeByVertex; }

    public Map<String, Integer> cpus() { return cpuByVertex; }

    /** Model-estimated remote communication cost (see {@link PlacementCostModel}); not a hardware measurement. */
    public double modelCost() { return modelCost; }

    public String optimizerName() { return optimizerName; }

    public List<String> warnings() { return warnings; }

    /**
     * Checks every constraint independently of how the plan was produced.
     *
     * @return violations; empty means the plan is valid
     */
    public List<String> validate(final CommunicationGraph graph, final NumaTopology topology, final PlacementConstraints constraints) {
        final List<String> v = new ArrayList<>();
        final Set<Integer> usable = new HashSet<>(topology.usableNodes());
        final Map<String, Integer> groupNode = new HashMap<>();
        final Map<Integer, Integer> cpuUse = new HashMap<>();
        final Map<Integer, Long> memUse = new HashMap<>();
        final Set<Integer> usedCpus = new HashSet<>();

        for (final CommunicationVertex vx : graph.vertices().values()) {
            final Integer node = nodeByVertex.get(vx.id());
            if (node == null) {
                v.add("vertex " + vx.id() + " has no node");
                continue;
            }
            if (!usable.contains(node)) {
                v.add("vertex " + vx.id() + " on node " + node + " which is not usable (no allowed cpu or memory)");
            }
            if (!vx.allowedNodes().isEmpty() && !vx.allowedNodes().contains(node)) {
                v.add("vertex " + vx.id() + " on node " + node + " violates its allowed nodes " + vx.allowedNodes());
            }
            if (vx.colocationGroup() != null) {
                final Integer prev = groupNode.putIfAbsent(vx.colocationGroup(), node);
                if (prev != null && !prev.equals(node)) {
                    v.add("colocation group " + vx.colocationGroup() + " split across nodes " + prev + " and " + node);
                }
            }
            cpuUse.merge(node, vx.cpuDemand(), Integer::sum);
            memUse.merge(node, vx.memoryBytes(), Long::sum);

            if (vx.cpuDemand() > 0) {
                final Integer cpu = cpuByVertex.get(vx.id());
                if (cpu == null) {
                    v.add("worker " + vx.id() + " has no cpu");
                } else {
                    if (!topology.allowedCpus().get(cpu)) {
                        v.add("worker " + vx.id() + " assigned cpu " + cpu + " which is not allowed for this job");
                    } else if (topology.cpu(cpu).nodeId() != node) {
                        v.add("worker " + vx.id() + " cpu " + cpu + " belongs to node " + topology.cpu(cpu).nodeId() + " not " + node);
                    }
                    if (constraints.reservedCpus().get(cpu)) {
                        v.add("worker " + vx.id() + " assigned reserved cpu " + cpu);
                    }
                    if (!usedCpus.add(cpu)) {
                        v.add("cpu " + cpu + " assigned to more than one worker");
                    }
                }
            }
        }
        for (final var e : cpuUse.entrySet()) {
            if (!usable.contains(e.getKey())) {
                continue; // already reported
            }
            final int cap = ClusterProblem.cpuCapacity(topology, constraints, e.getKey());
            if (e.getValue() > cap) {
                v.add("node " + e.getKey() + " needs " + e.getValue() + " cpus but has capacity " + cap);
            }
        }
        for (final var e : memUse.entrySet()) {
            final long total = topology.node(e.getKey()).memTotalBytes();
            if (total >= 0 && e.getValue() > total) {
                v.add("node " + e.getKey() + " needs " + e.getValue() + " bytes but has " + total);
            }
        }
        return v;
    }

    @Override
    public String toString() {
        return "PlacementPlan{" + optimizerName + ", cost=" + modelCost + ", nodes=" + nodeByVertex + ", cpus=" + cpuByVertex + "}";
    }
}
