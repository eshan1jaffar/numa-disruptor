package com.numadisruptor.optimizer;

import com.numadisruptor.topology.NumaTopology;
import java.util.List;
import java.util.Map;

/**
 * Builds a validated {@link PlacementPlan} from a hand-chosen vertex-to-node assignment, letting the same CPU selection
 * (distinct cores before SMT siblings) and validation as the optimizers apply. Used for the controlled locality experiments,
 * where the placement is the independent variable rather than something to optimise.
 */
public final class ManualPlacement {
    private ManualPlacement() { }

    /**
     * @throws InfeasiblePlacementException if a node lacks CPUs/memory for what was assigned to it, or a node is not usable
     * @throws IllegalArgumentException     if a vertex has no node or a colocation group is split
     */
    public static PlacementPlan of(
        final CommunicationGraph graph, final Map<String, Integer> nodeByVertex, final NumaTopology topology,
        final PlacementCostModel model, final PlacementConstraints constraints, final String name) {
        for (final String id : graph.vertices().keySet()) {
            if (!nodeByVertex.containsKey(id)) {
                throw new IllegalArgumentException("no node given for vertex " + id);
            }
        }
        final CpuAssigner.Result cpus = CpuAssigner.assign(graph, nodeByVertex, topology, constraints);
        final PlacementPlan plan = new PlacementPlan(
            nodeByVertex, cpus.cpuByVertex(), model.totalCost(graph, nodeByVertex), name, cpus.warnings());
        final List<String> violations = plan.validate(graph, topology, constraints);
        if (!violations.isEmpty()) {
            throw new InfeasiblePlacementException("manual placement is invalid: " + violations);
        }
        return plan;
    }
}
