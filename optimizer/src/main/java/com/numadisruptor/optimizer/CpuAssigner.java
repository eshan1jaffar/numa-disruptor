package com.numadisruptor.optimizer;

import com.numadisruptor.topology.NumaTopology;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Chooses concrete logical CPUs for worker vertices once their nodes are decided.
 *
 * <p>Within a node, workers (sorted by id, so results are deterministic) take one hardware thread per physical core
 * first and only then SMT siblings, so busy-spinning workers do not share a core unless the node has more workers than
 * cores (and the constraints allow it).
 */
final class CpuAssigner {
    private CpuAssigner() { }

    record Result(Map<String, Integer> cpuByVertex, List<String> warnings) { }

    /** Allowed, non-reserved CPUs of a node, ordered: first hardware thread of each core, then the siblings. */
    static List<Integer> orderedPool(final NumaTopology topology, final PlacementConstraints constraints, final int node) {
        final BitSet reserved = constraints.reservedCpus();
        final List<Integer> firsts = new ArrayList<>();
        final List<Integer> siblings = new ArrayList<>();
        final Set<Long> seenCores = new HashSet<>();
        for (final int cpu : topology.allowedCpusOnNode(node)) {
            if (reserved.get(cpu)) {
                continue;
            }
            if (seenCores.add(topology.cpu(cpu).physicalCoreKey())) {
                firsts.add(cpu);
            } else {
                siblings.add(cpu);
            }
        }
        final List<Integer> out = new ArrayList<>(firsts);
        if (constraints.allowSmtSharing()) {
            out.addAll(siblings);
        }
        return out;
    }

    static Result assign(
        final CommunicationGraph graph, final Map<String, Integer> nodeByVertex,
        final NumaTopology topology, final PlacementConstraints constraints) {
        final Map<Integer, List<String>> workersByNode = new TreeMap<>();
        for (final CommunicationVertex v : graph.vertices().values()) {
            if (v.cpuDemand() > 0) {
                workersByNode.computeIfAbsent(nodeByVertex.get(v.id()), k -> new ArrayList<>()).add(v.id());
            }
        }
        final Map<String, Integer> out = new LinkedHashMap<>();
        final List<String> warnings = new ArrayList<>();
        for (final var e : workersByNode.entrySet()) {
            final int node = e.getKey();
            final List<Integer> pool = orderedPool(topology, constraints, node);
            // a worker may demand more than one CPU in principle; today every worker takes exactly one
            final List<String> workers = e.getValue();
            if (workers.size() > pool.size()) {
                throw new InfeasiblePlacementException(
                    "node " + node + " has " + pool.size() + " usable cpus but " + workers.size() + " workers were placed there");
            }
            final int cores = (int) pool.stream().map(c -> topology.cpu(c).physicalCoreKey()).distinct().count();
            if (workers.size() > cores) {
                warnings.add("node " + node + ": " + workers.size() + " workers on " + cores
                    + " physical cores; some workers share a core via SMT");
            }
            for (int i = 0; i < workers.size(); i++) {
                out.put(workers.get(i), pool.get(i));
            }
        }
        return new Result(out, warnings);
    }
}
