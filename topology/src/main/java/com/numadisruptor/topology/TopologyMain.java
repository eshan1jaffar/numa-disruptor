package com.numadisruptor.topology;

import java.util.Map;

/** Diagnostic entry point: prints the discovered topology and the Slurm environment the job sees. */
public final class TopologyMain {
    private TopologyMain() { }

    public static void main(final String[] args) {
        final NumaTopology topology = TopologyDiscovery.discover();
        System.out.print(topology.describe());
        System.out.println("usableNodes=" + topology.usableNodes() + " isNuma=" + topology.isNuma());
        for (final Map.Entry<String, String> e : new java.util.TreeMap<>(System.getenv()).entrySet()) {
            if (e.getKey().startsWith("SLURM_CPU") || e.getKey().startsWith("SLURM_MEM")
                || e.getKey().equals("SLURM_JOB_ID") || e.getKey().equals("SLURM_CPUS_ON_NODE")
                || e.getKey().equals("SLURM_JOB_CPUS_PER_NODE")) {
                System.out.println(e.getKey() + "=" + e.getValue());
            }
        }
    }
}
