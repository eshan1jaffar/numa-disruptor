package com.numadisruptor.affinity;

import com.numadisruptor.topology.CpuList;
import com.numadisruptor.topology.NumaTopology;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Checks that placements took effect and do not violate the job's allowed CPU set. */
public final class AffinityVerifier {
    private AffinityVerifier() { }

    /**
     * Outcome of verifying the calling thread.
     *
     * @param ok         true when the thread's mask equals {@code expected} and it runs on one of those CPUs
     * @param expected   requested mask
     * @param actual     mask read back from the OS
     * @param currentCpu CPU the thread was running on at check time (-1 if unknown)
     * @param message    human-readable explanation
     */
    public record Result(boolean ok, BitSet expected, BitSet actual, int currentCpu, String message) { }

    /** Verify the calling thread is confined to {@code expected}. */
    public static Result verifyCurrentThread(final CpuAffinity affinity, final BitSet expected) {
        final BitSet actual = affinity.currentThreadAffinity();
        final int cpu = affinity.currentCpu();
        if (!actual.equals(expected)) {
            return new Result(false, expected, actual, cpu,
                "affinity mask is [" + CpuList.format(actual) + "], expected [" + CpuList.format(expected) + "]");
        }
        if (cpu >= 0 && !expected.get(cpu)) {
            return new Result(false, expected, actual, cpu,
                "running on cpu " + cpu + " outside expected [" + CpuList.format(expected) + "]");
        }
        return new Result(true, expected, actual, cpu, "ok");
    }

    /** Throws if {@code requested} is not a non-empty subset of the topology's allowed CPUs. */
    public static void requireAllowed(final NumaTopology topology, final BitSet requested) {
        if (requested.isEmpty()) {
            throw new AffinityException("empty CPU set");
        }
        final BitSet bad = (BitSet) requested.clone();
        bad.andNot(topology.allowedCpus());
        if (!bad.isEmpty()) {
            throw new AffinityException(
                "CPUs [" + CpuList.format(bad) + "] are not allowed for this job (allowed: ["
                    + CpuList.format(topology.allowedCpus()) + "])");
        }
    }

    /**
     * Finds workers that were assigned to the same logical CPU or to SMT siblings of the same physical core.
     * Two busy-spinning workers on one core slow each other down, so callers should treat any result as a warning.
     *
     * @param cpuByWorker worker name to single assigned CPU
     * @return one message per shared physical core, empty when every worker has a core to itself
     */
    public static List<String> sharedPhysicalCores(final NumaTopology topology, final Map<String, Integer> cpuByWorker) {
        final Map<Long, List<String>> byCore = new HashMap<>();
        cpuByWorker.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
            byCore.computeIfAbsent(topology.cpu(e.getValue()).physicalCoreKey(), k -> new ArrayList<>()).add(e.getKey()));
        final List<String> out = new ArrayList<>();
        byCore.values().stream().filter(l -> l.size() > 1).sorted((a, b) -> a.get(0).compareTo(b.get(0)))
            .forEach(l -> out.add("workers " + l + " share one physical core"));
        return out;
    }
}
