package com.numadisruptor.topology;

import java.util.List;

/**
 * One logical CPU (hardware thread).
 *
 * @param cpuId          Linux logical CPU id
 * @param nodeId         NUMA node the CPU belongs to
 * @param socketId       physical package id
 * @param coreId         core id within the package (shared by SMT siblings)
 * @param threadSiblings all logical CPUs on the same physical core, including this one, ascending
 */
public record CpuTopology(int cpuId, int nodeId, int socketId, int coreId, List<Integer> threadSiblings) {
    public CpuTopology {
        threadSiblings = List.copyOf(threadSiblings);
    }

    /**
     * Identifier unique across the machine for the physical core this CPU belongs to: socket plus the lowest sibling cpu id.
     * Deliberately not {@code coreId}, which some AMD parts and VMs repeat across dies within a package.
     */
    public long physicalCoreKey() {
        return ((long) socketId << 32) | (java.util.Collections.min(threadSiblings) & 0xFFFFFFFFL);
    }
}
