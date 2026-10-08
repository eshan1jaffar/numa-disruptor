package com.numadisruptor.optimizer;

import com.numadisruptor.topology.NumaTopology;

/**
 * Baseline: first-fit in cluster order. Fills node after node and ignores traffic entirely, which is roughly what happens
 * when workers are started in order and pinned to consecutive CPUs without any knowledge of who talks to whom.
 */
public final class NaivePlacementOptimizer implements PlacementOptimizer {
    @Override
    public String name() {
        return "naive-first-fit";
    }

    @Override
    public PlacementPlan place(
        final CommunicationGraph graph, final NumaTopology topology,
        final PlacementCostModel model, final PlacementConstraints constraints) {
        final ClusterProblem p = new ClusterProblem(graph, topology, model, constraints);
        final int[] assignment = p.newAssignment();
        final int[] cpuUsed = new int[p.nodes.size()];
        final long[] memUsed = new long[p.nodes.size()];
        for (final ClusterProblem.Cluster c : p.clusters) {
            boolean placed = false;
            for (final int node : c.candidateNodes()) {
                if (p.fits(c, node, cpuUsed, memUsed)) {
                    assignment[c.index()] = node;
                    final int ni = p.nodes.indexOf(node);
                    cpuUsed[ni] += c.cpuDemand();
                    memUsed[ni] += c.memoryBytes();
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                throw new InfeasiblePlacementException("first-fit could not place " + c.vertexIds());
            }
        }
        return p.toPlan(assignment, name());
    }
}
