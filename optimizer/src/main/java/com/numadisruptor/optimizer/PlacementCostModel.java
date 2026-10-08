package com.numadisruptor.optimizer;

import com.numadisruptor.topology.NumaTopology;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Converts estimated traffic plus node-to-node distance into a scalar remote-communication cost.
 *
 * <p>The objective minimised by the optimizers is
 * <pre>  sum over edges (i,j) of  w_ij * (D(x_i, x_j) - 1)</pre>
 * where {@code w_ij} is the edge's weighted line rate (payload reads, payload writes and ownership transfers carry
 * different coefficients) and {@code D} is the node-pair distance normalised so that a local access is 1. The constant
 * "-1" removes the unavoidable local cost, so a fully local placement scores exactly 0; it does not change which placement
 * is optimal. Capacity constraints are enforced by the optimizers, not folded into the cost.
 *
 * <p>This class is kept separate from the optimizers so that coefficients and the distance matrix can be replaced by
 * values calibrated from benchmark measurements ({@link #fromLatencyMatrix}). The defaults are uncalibrated priors and
 * the resulting numbers are <b>model estimates, not hardware-measured remote traffic</b>.
 */
public final class PlacementCostModel {
    /**
     * Relative per-line weights.
     *
     * @param remoteReadLine         cost of one payload line read from remote memory
     * @param remoteWriteLine        cost of one payload line written to remote memory
     * @param ownershipTransferLine  cost of one cross-node coherence transfer of a sequencing line
     */
    public record Coefficients(double remoteReadLine, double remoteWriteLine, double ownershipTransferLine) {
        public static Coefficients defaults() {
            // Priors: a coherence ownership transfer needs a request/response round trip and stalls the consumer's
            // dependent load, so it is weighted above a streaming payload line. Calibrate before trusting ratios.
            return new Coefficients(1.0, 0.8, 2.5);
        }
    }

    private final Coefficients coefficients;
    private final int[] nodeIds;
    private final double[][] excess; // D(a,b) - 1, indexed by position in nodeIds

    private PlacementCostModel(final Coefficients coefficients, final int[] nodeIds, final double[][] excess) {
        this.coefficients = coefficients;
        this.nodeIds = nodeIds.clone();
        this.excess = new double[excess.length][];
        for (int i = 0; i < excess.length; i++) {
            this.excess[i] = excess[i].clone();
        }
    }

    /** Uses the kernel's relative distances (10 = local) normalised by the local distance. */
    public static PlacementCostModel fromTopology(final NumaTopology topology) {
        return fromTopology(topology, Coefficients.defaults());
    }

    public static PlacementCostModel fromTopology(final NumaTopology topology, final Coefficients coefficients) {
        final List<com.numadisruptor.topology.NumaNode> nodes = topology.nodes();
        final int[] ids = new int[nodes.size()];
        final double[][] ex = new double[ids.length][ids.length];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = nodes.get(i).id();
        }
        for (int i = 0; i < ids.length; i++) {
            for (int j = 0; j < ids.length; j++) {
                ex[i][j] = i == j ? 0 : topology.distance(ids[i], ids[j]) / (double) topology.distance(ids[i], ids[i]) - 1.0;
            }
        }
        return new PlacementCostModel(coefficients, ids, ex);
    }

    /**
     * Calibrated model from a measured node-to-node access latency matrix (any consistent unit, e.g. nanoseconds):
     * {@code excess[a][b] = latency[a][b] / latency[a][a] - 1}.
     */
    public static PlacementCostModel fromLatencyMatrix(final int[] nodeIds, final double[][] latency, final Coefficients coefficients) {
        final double[][] ex = new double[nodeIds.length][nodeIds.length];
        for (int i = 0; i < nodeIds.length; i++) {
            for (int j = 0; j < nodeIds.length; j++) {
                ex[i][j] = i == j ? 0 : Math.max(0, latency[i][j] / latency[i][i] - 1.0);
            }
        }
        return new PlacementCostModel(coefficients, nodeIds, ex);
    }

    public Coefficients coefficients() { return coefficients; }

    /** Relative extra cost of {@code fromNode} touching memory homed at {@code toNode}; 0 when local. */
    public double excessDistance(final int fromNode, final int toNode) {
        if (fromNode == toNode) {
            return 0;
        }
        return excess[indexOf(fromNode)][indexOf(toNode)];
    }

    private int indexOf(final int node) {
        for (int i = 0; i < nodeIds.length; i++) {
            if (nodeIds[i] == node) {
                return i;
            }
        }
        throw new IllegalArgumentException("node " + node + " unknown to cost model");
    }

    /** Edge weight w_ij: weighted cache lines per second moved if the endpoints were one distance unit apart. */
    public double weight(final CommunicationEdge e) {
        final TrafficProfile t = e.traffic();
        return switch (e.kind()) {
            case PAYLOAD_READ -> t.payloadLinesPerSecond() * coefficients.remoteReadLine();
            case PAYLOAD_WRITE -> t.payloadLinesPerSecond() * coefficients.remoteWriteLine();
            case OWNERSHIP_TRANSFER -> t.coherenceLinesPerSecond() * coefficients.ownershipTransferLine();
        };
    }

    /** Cost of one edge for the given endpoint nodes. */
    public double edgeCost(final CommunicationEdge e, final int fromNode, final int toNode) {
        return weight(e) * excessDistance(fromNode, toNode);
    }

    /** Total model cost of a complete vertex-to-node assignment. */
    public double totalCost(final CommunicationGraph graph, final Map<String, Integer> nodeByVertex) {
        double sum = 0;
        for (final CommunicationEdge e : graph.edges()) {
            sum += edgeCost(e, nodeByVertex.get(e.from()), nodeByVertex.get(e.to()));
        }
        return sum;
    }

    /** Cost split by traffic class (remote reads / remote writes / ownership transfers). */
    public Map<EdgeKind, Double> breakdown(final CommunicationGraph graph, final Map<String, Integer> nodeByVertex) {
        final Map<EdgeKind, Double> out = new EnumMap<>(EdgeKind.class);
        for (final EdgeKind k : EdgeKind.values()) {
            out.put(k, 0.0);
        }
        for (final CommunicationEdge e : graph.edges()) {
            out.merge(e.kind(), edgeCost(e, nodeByVertex.get(e.from()), nodeByVertex.get(e.to())), Double::sum);
        }
        return out;
    }
}
