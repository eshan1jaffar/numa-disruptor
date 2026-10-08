package com.numadisruptor.benchmarks;

import com.numadisruptor.optimizer.CommunicationGraph;
import com.numadisruptor.optimizer.InfeasiblePlacementException;
import com.numadisruptor.optimizer.ManualPlacement;
import com.numadisruptor.optimizer.PlacementConstraints;
import com.numadisruptor.optimizer.PlacementCostModel;
import com.numadisruptor.optimizer.PlacementPlan;
import com.numadisruptor.runtime.PlacementManager;
import com.numadisruptor.runtime.PlacementPolicy;
import com.numadisruptor.runtime.RingInit;
import com.numadisruptor.runtime.VertexIds;
import com.numadisruptor.runtime.WorkloadGraphBuilder;
import com.numadisruptor.runtime.WorkloadSpec;
import com.numadisruptor.topology.NumaTopology;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves a (variant, locality) pair to a concrete placement on the topology this job is allowed to use. */
final class PlacementFactory {
    /**
     * @param plan            null for unpinned (V0)
     * @param skipReason      non-null when the topology cannot host the requested scenario; the experiment is skipped, not faked
     * @param optimizerNanos  time spent computing the plan
     */
    record Resolved(PlacementPlan plan, CommunicationGraph graph, RingInit ringInit, long optimizerNanos, String skipReason) {
        static Resolved skip(final String reason) {
            return new Resolved(null, null, RingInit.CALLER_THREAD, 0, reason);
        }
    }

    private PlacementFactory() { }

    static Resolved resolve(
        final ExperimentConfig c, final WorkloadSpec spec, final NumaTopology topology, final PlacementConstraints constraints) {
        final PlacementCostModel model = PlacementCostModel.fromTopology(topology);
        final RingInit ringInit = c.variant == Variant.V1 ? RingInit.CALLER_THREAD : RingInit.ON_PLANNED_NODE;
        final CommunicationGraph graph = WorkloadGraphBuilder.build(spec);
        final long t0 = System.nanoTime();
        try {
            final PlacementPlan plan = switch (c.locality) {
                case OS -> null;
                case DENSE -> new PlacementManager(topology, model, constraints).plan(spec, PlacementPolicy.NAIVE).plan();
                case ROUND_ROBIN -> new PlacementManager(topology, model, constraints).plan(spec, PlacementPolicy.ROUND_ROBIN).plan();
                case AUTO -> new PlacementManager(topology, model, constraints).plan(spec, PlacementPolicy.MIN_REMOTE_TRAFFIC).plan();
                default -> scenario(c, spec, graph, topology, model, constraints);
            };
            return new Resolved(plan, graph, ringInit, System.nanoTime() - t0, null);
        } catch (final UnsupportedScenarioException e) {
            return Resolved.skip(e.getMessage());
        } catch (final InfeasiblePlacementException e) {
            return Resolved.skip("workload does not fit the allowed CPUs/nodes: " + e.getMessage());
        }
    }

    private static final class UnsupportedScenarioException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnsupportedScenarioException(final String m) {
            super(m, null, false, false);
        }
    }

    private static PlacementPlan scenario(
        final ExperimentConfig c, final WorkloadSpec spec, final CommunicationGraph graph,
        final NumaTopology topology, final PlacementCostModel model, final PlacementConstraints constraints) {
        final List<Integer> usable = topology.usableNodes();
        final int workersPerPartition = spec.producersPerPartition() + spec.forwardingStages() + spec.consumersPerPartition();
        final int[] producerNode = new int[spec.partitions()];
        final int[] consumerNode = new int[spec.partitions()];

        switch (c.locality) {
            case SAME_NODE -> {
                final int need = workersPerPartition * spec.partitions();
                final int node = usable.stream().filter(n -> topology.allowedCpusOnNode(n).size() >= need).findFirst()
                    .orElseThrow(() -> new UnsupportedScenarioException(
                        "SAME_NODE needs " + need + " cpus on one node; the largest allowed node has "
                            + usable.stream().mapToInt(n -> topology.allowedCpusOnNode(n).size()).max().orElse(0)));
                java.util.Arrays.fill(producerNode, node);
                java.util.Arrays.fill(consumerNode, node);
            }
            case SAME_SOCKET -> {
                final int[] pair = pairSameSocket(topology, usable);
                java.util.Arrays.fill(producerNode, pair[0]);
                java.util.Arrays.fill(consumerNode, pair[1]);
            }
            case CROSS_SOCKET -> {
                final int[] pair = pairCrossSocket(topology, usable);
                java.util.Arrays.fill(producerNode, pair[0]);
                java.util.Arrays.fill(consumerNode, pair[1]);
            }
            case MULTI_NODE_LOCAL, MULTI_NODE_SPREAD -> {
                if (usable.size() < 2 || usable.stream().map(n -> topology.node(n).socketId()).distinct().count() < 2) {
                    throw new UnsupportedScenarioException(c.locality + " needs usable nodes on both sockets; allowed: " + usable);
                }
                for (int p = 0; p < spec.partitions(); p++) {
                    producerNode[p] = usable.get(p % usable.size());
                    consumerNode[p] = c.locality == Locality.MULTI_NODE_LOCAL ? producerNode[p] : usable.get((p + 1) % usable.size());
                }
            }
            default -> throw new IllegalStateException("not a scenario locality: " + c.locality);
        }

        final Map<String, Integer> nodes = new LinkedHashMap<>();
        for (int p = 0; p < spec.partitions(); p++) {
            for (int k = 0; k < spec.producersPerPartition(); k++) {
                nodes.put(VertexIds.producer(p, k), producerNode[p]);
            }
            for (int f = 0; f < spec.forwardingStages(); f++) {
                nodes.put(VertexIds.forwarder(p, f), consumerNode[p]);
            }
            for (int k = 0; k < spec.consumersPerPartition(); k++) {
                nodes.put(VertexIds.consumer(p, k), consumerNode[p]);
            }
            for (int r = 0; r < spec.rings(); r++) {
                final int home = r == 0 && c.ringHome == ExperimentConfig.RingHome.PRODUCER ? producerNode[p] : consumerNode[p];
                nodes.put(VertexIds.ring(p, r), home);
                nodes.put(VertexIds.cursor(p, r), home);
                nodes.put(VertexIds.gating(p, r), home);
            }
        }
        return ManualPlacement.of(graph, nodes, topology, model, constraints, "scenario-" + c.locality + "-ring@" + c.ringHome);
    }

    private static int[] pairSameSocket(final NumaTopology t, final List<Integer> usable) {
        for (final int a : usable) {
            for (final int b : usable) {
                if (a < b && t.node(a).socketId() == t.node(b).socketId()) {
                    return new int[] {a, b};
                }
            }
        }
        throw new UnsupportedScenarioException("SAME_SOCKET needs two usable nodes on one socket; allowed: " + usable);
    }

    private static int[] pairCrossSocket(final NumaTopology t, final List<Integer> usable) {
        for (final int a : usable) {
            for (final int b : usable) {
                if (a < b && t.node(a).socketId() != t.node(b).socketId()) {
                    return new int[] {a, b};
                }
            }
        }
        throw new UnsupportedScenarioException("CROSS_SOCKET needs usable nodes on two sockets; allowed: " + usable);
    }
}
