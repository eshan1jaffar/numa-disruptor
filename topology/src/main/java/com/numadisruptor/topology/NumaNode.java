package com.numadisruptor.topology;

import java.util.List;

/**
 * One NUMA node.
 *
 * @param id            node id
 * @param socketId      socket hosting the node's CPUs (-1 for memory-only nodes)
 * @param cpus          all CPUs of the node as exposed by the machine (not filtered by the job's mask)
 * @param memTotalBytes total memory reported by the kernel, or -1 if unknown
 */
public record NumaNode(int id, int socketId, List<Integer> cpus, long memTotalBytes) {
    public NumaNode {
        cpus = List.copyOf(cpus);
    }
}
