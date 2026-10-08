package com.numadisruptor.optimizer;

import com.numadisruptor.topology.NumaTopology;

/**
 * Decides which NUMA node and CPU each graph vertex uses. Implementations are deterministic: the same graph, topology,
 * cost model and constraints always produce the same plan.
 */
public interface PlacementOptimizer {
    String name();

    /**
     * @throws InfeasiblePlacementException if no assignment satisfies the capacity and node constraints
     *         (or, for heuristics, if the heuristic could not find one)
     */
    PlacementPlan place(
        CommunicationGraph graph, NumaTopology topology, PlacementCostModel model, PlacementConstraints constraints);

    default PlacementPlan place(final CommunicationGraph graph, final NumaTopology topology, final PlacementCostModel model) {
        return place(graph, topology, model, PlacementConstraints.defaults());
    }
}
