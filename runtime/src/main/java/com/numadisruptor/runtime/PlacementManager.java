package com.numadisruptor.runtime;

import com.numadisruptor.optimizer.CommunicationGraph;
import com.numadisruptor.optimizer.GreedyPlacementOptimizer;
import com.numadisruptor.optimizer.LocalSearchOptimizer;
import com.numadisruptor.optimizer.NaivePlacementOptimizer;
import com.numadisruptor.optimizer.PlacementConstraints;
import com.numadisruptor.optimizer.PlacementCostModel;
import com.numadisruptor.optimizer.PlacementOptimizer;
import com.numadisruptor.optimizer.PlacementPlan;
import com.numadisruptor.optimizer.RoundRobinPlacementOptimizer;
import com.numadisruptor.topology.NumaTopology;

/** Turns a {@link PlacementPolicy} into a {@link PlacementPlan} for a workload on a topology, and times the computation. */
public final class PlacementManager {
    /** A plan together with how long the optimizer took (the "placement calculation overhead"). */
    public record Planned(PlacementPlan plan, CommunicationGraph graph, long optimizerNanos) { }

    private final NumaTopology topology;
    private final PlacementCostModel costModel;
    private final PlacementConstraints constraints;

    public PlacementManager(final NumaTopology topology, final PlacementCostModel costModel, final PlacementConstraints constraints) {
        this.topology = topology;
        this.costModel = costModel;
        this.constraints = constraints;
    }

    public static PlacementOptimizer optimizerFor(final PlacementPolicy policy) {
        return switch (policy) {
            case NAIVE -> new NaivePlacementOptimizer();
            case ROUND_ROBIN -> new RoundRobinPlacementOptimizer();
            case GREEDY -> new GreedyPlacementOptimizer();
            case MIN_REMOTE_TRAFFIC -> new LocalSearchOptimizer(new GreedyPlacementOptimizer());
            case NONE, EXPLICIT -> throw new IllegalArgumentException(policy + " has no optimizer");
        };
    }

    /**
     * Builds the graph for {@code spec} and places it with the policy's optimizer.
     *
     * @throws com.numadisruptor.optimizer.InfeasiblePlacementException if the workload does not fit the allowed CPUs/nodes
     */
    public Planned plan(final WorkloadSpec spec, final PlacementPolicy policy) {
        final CommunicationGraph graph = WorkloadGraphBuilder.build(spec);
        final long t0 = System.nanoTime();
        final PlacementPlan plan = optimizerFor(policy).place(graph, topology, costModel, constraints);
        return new Planned(plan, graph, System.nanoTime() - t0);
    }
}
