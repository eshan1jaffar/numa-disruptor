package com.numadisruptor.optimizer;

import com.numadisruptor.topology.NumaTopology;

/**
 * Baseline: cluster i goes to the next usable node in rotation (skipping nodes where it does not fit). Spreads load
 * evenly across nodes and, because it ignores traffic, usually maximises cross-node communication for connected graphs.
 */
public final class RoundRobinPlacementOptimizer implements PlacementOptimizer {
    @Override
    public String name() {
        return "round-robin";
    }

    @Override
    public PlacementPlan place(
        final CommunicationGraph graph, final NumaTopology topology,
        final PlacementCostModel model, final PlacementConstraints constraints) {
        final ClusterProblem p = new ClusterProblem(graph, topology, model, constraints);
        final int[] assignment = p.newAssignment();
        final int[] cpuUsed = new int[p.nodes.size()];
        final long[] memUsed = new long[p.nodes.size()];
        int next = 0;
        for (final ClusterProblem.Cluster c : p.clusters) {
            boolean placed = false;
            for (int attempt = 0; attempt < p.nodes.size() && !placed; attempt++) {
                final int node = p.nodes.get((next + attempt) % p.nodes.size());
                if (c.candidateNodes().contains(node) && p.fits(c, node, cpuUsed, memUsed)) {
                    assignment[c.index()] = node;
                    final int ni = p.nodes.indexOf(node);
                    cpuUsed[ni] += c.cpuDemand();
                    memUsed[ni] += c.memoryBytes();
                    next = (next + attempt + 1) % p.nodes.size();
                    placed = true;
                }
            }
            if (!placed) {
                throw new InfeasiblePlacementException("round-robin could not place " + c.vertexIds());
            }
        }
        return p.toPlan(assignment, name());
    }
}
