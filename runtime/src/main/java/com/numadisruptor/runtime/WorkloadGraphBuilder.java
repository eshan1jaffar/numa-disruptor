package com.numadisruptor.runtime;

import com.numadisruptor.optimizer.CommunicationGraph;
import com.numadisruptor.optimizer.CommunicationVertex;
import com.numadisruptor.optimizer.EdgeKind;
import com.numadisruptor.optimizer.TrafficProfile;
import com.numadisruptor.optimizer.VertexType;

/**
 * Translates a {@link WorkloadSpec} into the communication graph the optimizer works on.
 *
 * <p>Per ring the graph contains the ring itself, its sequencing cursor and its gating sequences (one colocation group,
 * because they are allocated together), the writers and the readers. Edges follow the actual access pattern:
 * <ul>
 *   <li>writer to ring: payload writes, one per event;</li>
 *   <li>writer to cursor: a cursor line transfer per publish (per event for producers, per batch for forwarders);</li>
 *   <li>writer to gating: the writer re-reads consumers' progress only when its cached view says the ring may be full,
 *       about once per half ring when consumers keep up;</li>
 *   <li>reader to ring: payload reads (all events when multicast, 1/N when work-distributed);</li>
 *   <li>reader to cursor and gating: one line transfer per consumer batch each.</li>
 * </ul>
 * All rates are estimates from the spec, not measurements.
 */
public final class WorkloadGraphBuilder {
    private WorkloadGraphBuilder() { }

    public static CommunicationGraph build(final WorkloadSpec spec) {
        final CommunicationGraph.Builder b = CommunicationGraph.builder();
        final double eps = spec.eventsPerSecondPerPartition();
        for (int p = 0; p < spec.partitions(); p++) {
            for (int r = 0; r < spec.rings(); r++) {
                final String group = VertexIds.ringGroup(p, r);
                final long ringBytes = (long) spec.ringBufferSize() * (spec.payloadBytes() + 64L);
                b.vertex(CommunicationVertex.memory(VertexIds.ring(p, r), VertexType.RING_BUFFER, ringBytes, group));
                b.vertex(CommunicationVertex.memory(VertexIds.cursor(p, r), VertexType.SEQUENCER_METADATA, 4096, group));
                b.vertex(CommunicationVertex.memory(VertexIds.gating(p, r), VertexType.GATING_SEQUENCE, 4096, group));
            }
            for (int k = 0; k < spec.producersPerPartition(); k++) {
                b.vertex(CommunicationVertex.worker(VertexIds.producer(p, k), VertexType.PRODUCER));
                writerEdges(b, VertexIds.producer(p, k), p, 0, eps / spec.producersPerPartition(), 1.0, spec);
            }
            for (int f = 0; f < spec.forwardingStages(); f++) {
                b.vertex(CommunicationVertex.worker(VertexIds.forwarder(p, f), VertexType.STAGE));
                readerEdges(b, VertexIds.forwarder(p, f), p, f, eps, spec.consumerBatch(), spec);
                writerEdges(b, VertexIds.forwarder(p, f), p, f + 1, eps, spec.consumerBatch(), spec);
            }
            final int last = spec.forwardingStages();
            for (int c = 0; c < spec.consumersPerPartition(); c++) {
                b.vertex(CommunicationVertex.worker(VertexIds.consumer(p, c), VertexType.CONSUMER));
                final double payloadShare = spec.mode() == ConsumptionMode.MULTICAST ? 1.0 : 1.0 / spec.consumersPerPartition();
                readerEdges(b, VertexIds.consumer(p, c), p, last, eps * payloadShare, spec.consumerBatch(), spec, eps);
            }
        }
        return b.build();
    }

    private static void writerEdges(
        final CommunicationGraph.Builder b, final String writer, final int p, final int ring,
        final double eps, final double eventsPerPublish, final WorkloadSpec spec) {
        b.edge(writer, VertexIds.ring(p, ring), EdgeKind.PAYLOAD_WRITE, TrafficProfile.payload(eps, spec.payloadBytes()));
        b.edge(writer, VertexIds.cursor(p, ring), EdgeKind.OWNERSHIP_TRANSFER, TrafficProfile.sequencing(eps, eventsPerPublish, 1));
        b.edge(writer, VertexIds.gating(p, ring), EdgeKind.OWNERSHIP_TRANSFER,
            TrafficProfile.sequencing(eps, Math.max(1, spec.ringBufferSize() / 2.0), 1));
    }

    private static void readerEdges(
        final CommunicationGraph.Builder b, final String reader, final int p, final int ring,
        final double eps, final int batch, final WorkloadSpec spec) {
        readerEdges(b, reader, p, ring, eps, batch, spec, eps);
    }

    /** @param payloadEps events whose payload the reader touches; @param seqEps events whose sequence the reader must observe */
    private static void readerEdges(
        final CommunicationGraph.Builder b, final String reader, final int p, final int ring,
        final double payloadEps, final int batch, final WorkloadSpec spec, final double seqEps) {
        b.edge(reader, VertexIds.ring(p, ring), EdgeKind.PAYLOAD_READ, TrafficProfile.payload(payloadEps, spec.payloadBytes()));
        b.edge(reader, VertexIds.cursor(p, ring), EdgeKind.OWNERSHIP_TRANSFER, TrafficProfile.sequencing(seqEps, batch, 1));
        b.edge(reader, VertexIds.gating(p, ring), EdgeKind.OWNERSHIP_TRANSFER, TrafficProfile.sequencing(seqEps, batch, 1));
    }
}
