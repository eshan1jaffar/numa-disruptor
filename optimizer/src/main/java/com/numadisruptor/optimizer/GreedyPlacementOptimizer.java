package com.numadisruptor.optimizer;

import com.numadisruptor.topology.NumaTopology;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Communication-aware greedy placement.
 *
 * <ol>
 *   <li>Connected components of the graph are independent event streams (no traffic between components), so each
 *       component is placed as a unit when possible: heaviest components first, each onto the node where the whole
 *       component fits with the least capacity left over (best-fit), which also packs independent streams tightly.</li>
 *   <li>A component too large for any single node is split cluster by cluster, heaviest cluster first, each cluster
 *       going to the feasible node that adds the least model cost against the clusters already placed (ties: more free CPUs,
 *       then lower node id).</li>
 * </ol>
 * Polynomial and deterministic, but myopic; pair it with {@link LocalSearchOptimizer} for refinement.
 */
public final class GreedyPlacementOptimizer implements PlacementOptimizer {
    @Override
    public String name() {
        return "greedy";
    }

    @Override
    public PlacementPlan place(
        final CommunicationGraph graph, final NumaTopology topology,
        final PlacementCostModel model, final PlacementConstraints constraints) {
        final ClusterProblem p = new ClusterProblem(graph, topology, model, constraints);
        final int[] assignment = p.newAssignment();
        final int[] cpuUsed = new int[p.nodes.size()];
        final long[] memUsed = new long[p.nodes.size()];

        // cluster-level connected components
        final Map<Integer, List<Integer>> components = new TreeMap<>();
        final int[] root = new int[p.clusters.size()];
        for (int i = 0; i < root.length; i++) {
            root[i] = i;
        }
        for (final CommunicationEdge e : graph.edges()) {
            union(root, p.clusterOfVertex.get(e.from()), p.clusterOfVertex.get(e.to()));
        }
        for (int i = 0; i < root.length; i++) {
            components.computeIfAbsent(find(root, i), k -> new ArrayList<>()).add(i);
        }
        final List<List<Integer>> ordered = new ArrayList<>(components.values());
        ordered.sort(Comparator
            .comparingDouble((List<Integer> comp) -> -comp.stream().mapToDouble(p::incidentWeight).sum())
            .thenComparing(comp -> comp.get(0)));

        for (final List<Integer> comp : ordered) {
            if (!tryPlaceWhole(p, comp, assignment, cpuUsed, memUsed)) {
                placeSplit(p, comp, assignment, cpuUsed, memUsed);
            }
        }
        return p.toPlan(assignment, name());
    }

    private static boolean tryPlaceWhole(
        final ClusterProblem p, final List<Integer> comp, final int[] assignment, final int[] cpuUsed, final long[] memUsed) {
        int cpu = 0;
        long mem = 0;
        for (final int ci : comp) {
            cpu += p.clusters.get(ci).cpuDemand();
            mem += p.clusters.get(ci).memoryBytes();
        }
        int bestNode = -1;
        int bestLeft = Integer.MAX_VALUE;
        for (final int node : p.nodes) {
            final int ni = p.nodes.indexOf(node);
            final boolean allowed = comp.stream().allMatch(ci -> p.clusters.get(ci).candidateNodes().contains(node));
            if (!allowed || cpuUsed[ni] + cpu > p.cpuCapacity.get(node) || memUsed[ni] + mem > p.memCapacity.get(node)
                || memUsed[ni] + mem < 0) {
                continue;
            }
            final int left = p.cpuCapacity.get(node) - cpuUsed[ni] - cpu;
            if (left < bestLeft) { // strict: lowest node id wins ties
                bestLeft = left;
                bestNode = node;
            }
        }
        if (bestNode < 0) {
            return false;
        }
        final int ni = p.nodes.indexOf(bestNode);
        for (final int ci : comp) {
            assignment[ci] = bestNode;
        }
        cpuUsed[ni] += cpu;
        memUsed[ni] += mem;
        return true;
    }

    private static void placeSplit(
        final ClusterProblem p, final List<Integer> comp, final int[] assignment, final int[] cpuUsed, final long[] memUsed) {
        final List<Integer> byWeight = new ArrayList<>(comp);
        byWeight.sort(Comparator.comparingDouble((Integer ci) -> -p.incidentWeight(ci)).thenComparing(ci -> ci));
        for (final int ci : byWeight) {
            final ClusterProblem.Cluster c = p.clusters.get(ci);
            int bestNode = -1;
            double bestCost = Double.POSITIVE_INFINITY;
            int bestFree = -1;
            for (final int node : c.candidateNodes()) {
                if (!p.fits(c, node, cpuUsed, memUsed)) {
                    continue;
                }
                final double cost = p.incidentCost(ci, node, assignment);
                final int free = p.cpuCapacity.get(node) - cpuUsed[p.nodes.indexOf(node)];
                if (cost < bestCost - 1e-12 || (Math.abs(cost - bestCost) <= 1e-12 && free > bestFree)) {
                    bestNode = node;
                    bestCost = cost;
                    bestFree = free;
                }
            }
            if (bestNode < 0) {
                throw new InfeasiblePlacementException("greedy could not place " + c.vertexIds() + " (capacity exhausted)");
            }
            assignment[ci] = bestNode;
            final int ni = p.nodes.indexOf(bestNode);
            cpuUsed[ni] += c.cpuDemand();
            memUsed[ni] += c.memoryBytes();
        }
    }

    private static int find(final int[] root, final int x) {
        int r = x;
        while (root[r] != r) {
            r = root[r];
        }
        return r;
    }

    private static void union(final int[] root, final int a, final int b) {
        final int ra = find(root, a);
        final int rb = find(root, b);
        if (ra != rb) {
            root[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }
}
