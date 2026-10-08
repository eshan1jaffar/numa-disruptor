package com.numadisruptor.optimizer;

import com.numadisruptor.topology.NumaTopology;

/**
 * Exhaustive reference solver (depth-first with capacity pruning and a cost bound) used to verify the heuristics on
 * tiny graphs. Returns the minimum-cost feasible assignment; among equal-cost optima the one found first in
 * (cluster ascending, node ascending) order. Refuses problems with more than {@value #MAX_STATES} raw states.
 */
public final class BruteForcePlacementOptimizer implements PlacementOptimizer {
    static final long MAX_STATES = 20_000_000L;

    @Override
    public String name() {
        return "brute-force";
    }

    @Override
    public PlacementPlan place(
        final CommunicationGraph graph, final NumaTopology topology,
        final PlacementCostModel model, final PlacementConstraints constraints) {
        final ClusterProblem p = new ClusterProblem(graph, topology, model, constraints);
        double states = 1;
        for (final ClusterProblem.Cluster c : p.clusters) {
            states *= c.candidateNodes().size();
            if (states > MAX_STATES) {
                throw new IllegalArgumentException("brute force refused: more than " + MAX_STATES + " assignments");
            }
        }
        final Search s = new Search(p);
        s.recurse(0, 0.0);
        if (s.best == null) {
            throw new InfeasiblePlacementException("no feasible assignment exists");
        }
        return p.toPlan(s.best, name());
    }

    private static final class Search {
        final ClusterProblem p;
        final int[] current;
        final int[] cpuUsed;
        final long[] memUsed;
        int[] best;
        double bestCost = Double.POSITIVE_INFINITY;

        Search(final ClusterProblem p) {
            this.p = p;
            this.current = p.newAssignment();
            this.cpuUsed = new int[p.nodes.size()];
            this.memUsed = new long[p.nodes.size()];
        }

        void recurse(final int idx, final double costSoFar) {
            if (costSoFar >= bestCost - 1e-12) {
                return; // costs are non-negative, so this branch cannot beat (or strictly tie-break) the incumbent
            }
            if (idx == p.clusters.size()) {
                bestCost = costSoFar;
                best = current.clone();
                return;
            }
            final ClusterProblem.Cluster c = p.clusters.get(idx);
            for (final int node : c.candidateNodes()) {
                if (!p.fits(c, node, cpuUsed, memUsed)) {
                    continue;
                }
                final int ni = p.nodes.indexOf(node);
                final double added = p.incidentCost(idx, node, current); // only edges to already-assigned clusters
                current[idx] = node;
                cpuUsed[ni] += c.cpuDemand();
                memUsed[ni] += c.memoryBytes();
                recurse(idx + 1, costSoFar + added);
                cpuUsed[ni] -= c.cpuDemand();
                memUsed[ni] -= c.memoryBytes();
                current[idx] = -1;
            }
        }
    }
}
