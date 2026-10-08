package com.numadisruptor.optimizer;

import com.numadisruptor.topology.NumaTopology;

/**
 * Refines the plan of a base optimizer by first-improvement hill climbing over two neighbourhoods:
 * <ul>
 *   <li><b>relocate</b>: move one cluster to another node with spare capacity;</li>
 *   <li><b>swap</b>: exchange the nodes of two clusters (needed when both nodes are full).</li>
 * </ul>
 * A move is applied only if it lowers the model cost by more than 1e-9 and keeps every capacity and node constraint, so the
 * result is never worse than the base plan and always valid. Moves are tried in a fixed order (clusters ascending,
 * nodes ascending) so the result is deterministic. It finds a local optimum, not necessarily the global one.
 */
public final class LocalSearchOptimizer implements PlacementOptimizer {
    private static final double EPSILON = 1e-9;
    private static final int MAX_PASSES = 10_000;

    private final PlacementOptimizer base;

    public LocalSearchOptimizer(final PlacementOptimizer base) {
        this.base = base;
    }

    @Override
    public String name() {
        return base.name() + "+local-search";
    }

    @Override
    public PlacementPlan place(
        final CommunicationGraph graph, final NumaTopology topology,
        final PlacementCostModel model, final PlacementConstraints constraints) {
        final PlacementPlan start = base.place(graph, topology, model, constraints);
        final ClusterProblem p = new ClusterProblem(graph, topology, model, constraints);
        final int[] a = p.assignmentOf(start);
        final int[] cpuUsed = new int[p.nodes.size()];
        final long[] memUsed = new long[p.nodes.size()];
        p.usage(a, cpuUsed, memUsed);

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean improved = false;
            for (final ClusterProblem.Cluster c : p.clusters) {
                improved |= tryRelocate(p, c, a, cpuUsed, memUsed);
            }
            for (int i = 0; i < p.clusters.size(); i++) {
                for (int j = i + 1; j < p.clusters.size(); j++) {
                    improved |= trySwap(p, p.clusters.get(i), p.clusters.get(j), a, cpuUsed, memUsed);
                }
            }
            if (!improved) {
                break;
            }
        }
        return p.toPlan(a, name());
    }

    private static boolean tryRelocate(
        final ClusterProblem p, final ClusterProblem.Cluster c, final int[] a, final int[] cpuUsed, final long[] memUsed) {
        final int from = a[c.index()];
        final int fi = p.nodes.indexOf(from);
        final double current = p.incidentCost(c.index(), from, a);
        for (final int to : c.candidateNodes()) {
            if (to == from) {
                continue;
            }
            final int ti = p.nodes.indexOf(to);
            if (cpuUsed[ti] + c.cpuDemand() > p.cpuCapacity.get(to) || memUsed[ti] + c.memoryBytes() > p.memCapacity.get(to)) {
                continue;
            }
            if (p.incidentCost(c.index(), to, a) < current - EPSILON) {
                a[c.index()] = to;
                cpuUsed[fi] -= c.cpuDemand();
                memUsed[fi] -= c.memoryBytes();
                cpuUsed[ti] += c.cpuDemand();
                memUsed[ti] += c.memoryBytes();
                return true;
            }
        }
        return false;
    }

    private static boolean trySwap(
        final ClusterProblem p, final ClusterProblem.Cluster x, final ClusterProblem.Cluster y,
        final int[] a, final int[] cpuUsed, final long[] memUsed) {
        final int nx = a[x.index()];
        final int ny = a[y.index()];
        if (nx == ny || !x.candidateNodes().contains(ny) || !y.candidateNodes().contains(nx)) {
            return false;
        }
        final int xi = p.nodes.indexOf(nx);
        final int yi = p.nodes.indexOf(ny);
        final int cpuX = cpuUsed[xi] - x.cpuDemand() + y.cpuDemand();
        final int cpuY = cpuUsed[yi] - y.cpuDemand() + x.cpuDemand();
        final long memX = memUsed[xi] - x.memoryBytes() + y.memoryBytes();
        final long memY = memUsed[yi] - y.memoryBytes() + x.memoryBytes();
        if (cpuX > p.cpuCapacity.get(nx) || cpuY > p.cpuCapacity.get(ny)
            || memX > p.memCapacity.get(nx) || memY > p.memCapacity.get(ny)) {
            return false;
        }
        final double before = pairCost(p, x, y, a);
        a[x.index()] = ny;
        a[y.index()] = nx;
        final double after = pairCost(p, x, y, a);
        if (after < before - EPSILON) {
            cpuUsed[xi] = cpuX;
            cpuUsed[yi] = cpuY;
            memUsed[xi] = memX;
            memUsed[yi] = memY;
            return true;
        }
        a[x.index()] = nx;
        a[y.index()] = ny;
        return false;
    }

    /** Cost of all edges touching x or y, counting an x-y edge once. */
    private static double pairCost(final ClusterProblem p, final ClusterProblem.Cluster x, final ClusterProblem.Cluster y, final int[] a) {
        double s = p.incidentCost(x.index(), a[x.index()], a) + p.incidentCost(y.index(), a[y.index()], a);
        for (final CommunicationEdge e : p.incident.get(x.index())) {
            final int f = p.clusterOfVertex.get(e.from());
            final int t = p.clusterOfVertex.get(e.to());
            if ((f == x.index() && t == y.index()) || (f == y.index() && t == x.index())) {
                s -= p.model.edgeCost(e, a[f], a[t]); // counted by both incidentCost calls
            }
        }
        return s;
    }
}
