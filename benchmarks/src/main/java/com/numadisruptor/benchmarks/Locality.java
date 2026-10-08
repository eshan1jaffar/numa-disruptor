package com.numadisruptor.benchmarks;

/**
 * Where workers sit relative to each other. For the controlled scenarios the placement is the independent variable.
 */
public enum Locality {
    /** No pinning: the OS decides (variant V0). */
    OS,
    /** Pinned, first-fit packing of workers onto consecutive nodes (what naive pinning produces). */
    DENSE,
    /** Pinned, partitions' clusters rotated across all usable nodes with no regard to traffic. */
    ROUND_ROBIN,
    /** Each partition: producer, stage, consumers all on one node. */
    SAME_NODE,
    /** Each partition: producer on one node, stage/consumers on a different node of the same socket. */
    SAME_SOCKET,
    /** Each partition: producer on one socket, stage/consumers on a node of the other socket. */
    CROSS_SOCKET,
    /** Partitions spread over all usable nodes; each partition's producer on node n, its consumers on node n+1. */
    MULTI_NODE_SPREAD,
    /** Partitions spread over all usable nodes; each partition entirely local to its node. */
    MULTI_NODE_LOCAL,
    /** The optimizer decides (variant V3). */
    AUTO
}
