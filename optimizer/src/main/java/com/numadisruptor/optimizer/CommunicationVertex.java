package com.numadisruptor.optimizer;

import java.util.Set;

/**
 * A placeable component.
 *
 * @param id               unique id within the graph
 * @param type             what the component is
 * @param cpuDemand        dedicated logical CPUs required (0 for memory-only vertices; busy-spin workers need 1)
 * @param memoryBytes      resident memory the component needs on its node
 * @param allowedNodes     nodes this vertex may be placed on; empty means "any usable node"
 * @param colocationGroup  vertices sharing a non-null group id are forced onto one node (for example a ring, its
 *                         cursor and its gating sequences, which are allocated together)
 */
public record CommunicationVertex(
    String id, VertexType type, int cpuDemand, long memoryBytes, Set<Integer> allowedNodes, String colocationGroup) {

    public CommunicationVertex {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("vertex id must not be blank");
        }
        if (cpuDemand < 0 || memoryBytes < 0) {
            throw new IllegalArgumentException("negative demand for vertex " + id);
        }
        if (cpuDemand > 0 && !type.isWorker()) {
            throw new IllegalArgumentException("vertex " + id + " of type " + type + " cannot demand a CPU");
        }
        allowedNodes = Set.copyOf(allowedNodes);
    }

    /** A pinned worker thread needing one dedicated CPU. */
    public static CommunicationVertex worker(final String id, final VertexType type) {
        return new CommunicationVertex(id, type, 1, 0, Set.of(), null);
    }

    /** A ring buffer (and, via the group, its sequencing metadata) occupying {@code memoryBytes}. */
    public static CommunicationVertex memory(final String id, final VertexType type, final long memoryBytes, final String group) {
        return new CommunicationVertex(id, type, 0, memoryBytes, Set.of(), group);
    }

    public CommunicationVertex withAllowedNodes(final Set<Integer> nodes) {
        return new CommunicationVertex(id, type, cpuDemand, memoryBytes, nodes, colocationGroup);
    }

    public CommunicationVertex withColocationGroup(final String group) {
        return new CommunicationVertex(id, type, cpuDemand, memoryBytes, allowedNodes, group);
    }
}
