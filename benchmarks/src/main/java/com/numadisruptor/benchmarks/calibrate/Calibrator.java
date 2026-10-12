package com.numadisruptor.benchmarks.calibrate;

import com.numadisruptor.affinity.CpuAffinity;
import com.numadisruptor.benchmarks.RunMetadata;
import com.numadisruptor.memory.NumaAllocator;
import com.numadisruptor.memory.NumaMemoryRegion;
import com.numadisruptor.memory.NumaMemoryVerifier;
import com.numadisruptor.topology.NumaTopology;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures, for every ordered pair of usable NUMA nodes (a, b): dependent-load latency from a thread pinned on node a into memory
 * bound to node b, and the round-trip cost of bouncing one cache line between a thread on a and a thread on b.
 * Pairs that cannot be measured are left NaN and listed with a reason; nothing is silently filled in.
 */
public final class Calibrator {
    private static final int LINE = 64;
    private static final VarHandle COUNTER;

    static {
        try {
            COUNTER = MethodHandles.lookup().findVarHandle(Cell.class, "counter", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * @param bufferBytes    pointer-chase buffer per target node (should be much larger than L3)
     * @param reps           repetitions per measurement; the median is reported
     * @param loadsPerRep    dependent loads timed per repetition
     * @param pingPongRounds round trips timed per repetition
     */
    public record Params(long bufferBytes, int reps, long loadsPerRep, int pingPongRounds, long seed) {
        public Params {
            if (bufferBytes < 2L * LINE * 1024 || reps < 1 || loadsPerRep < 1 || pingPongRounds < 1) {
                throw new IllegalArgumentException("bad calibration parameters " + this);
            }
        }

        public static Params defaults() {
            return new Params(256L << 20, 7, 2_000_000, 200_000, 42);
        }
    }

    /** Cache-line sized (padded) shared counter for the ping-pong. */
    @SuppressWarnings("unused")
    private static final class Cell {
        long p1, p2, p3, p4, p5, p6, p7;
        volatile long counter;
        long q1, q2, q3, q4, q5, q6, q7;
        volatile boolean ready;
        volatile boolean abort;
        volatile String error;
    }

    private static volatile long sink;

    private Calibrator() { }

    public static Map<String, Object> run(final NumaTopology topology, final CpuAffinity affinity, final NumaAllocator allocator,
                                          final Params p) {
        final List<Integer> nodes = topology.usableNodes();
        final int n = nodes.size();
        final double[][] lat = nan(n);
        final double[][] latMin = nan(n);
        final double[][] latMax = nan(n);
        final double[][] pp = nan(n);
        final List<Map<String, Object>> invalid = new ArrayList<>();
        final NumaMemoryVerifier verifier = new NumaMemoryVerifier(allocator);

        final int lines = (int) Math.min(Integer.MAX_VALUE / 2, p.bufferBytes() / LINE);
        final int[] cycle = PointerChase.buildCycle(lines, p.seed());

        for (int bi = 0; bi < n; bi++) {
            final int b = nodes.get(bi);
            NumaMemoryRegion region = null;
            String bad = null;
            try {
                region = allocator.allocateOnNode((long) lines * LINE, b);
                region.prefault();
                final NumaMemoryVerifier.Report rep = verifier.verify(region, 4096);
                if (!rep.allOnExpectedNode()) {
                    bad = "residency mismatch: expected node " + b + ", sampled pages by node " + rep.pagesByNode()
                        + ", unmapped " + rep.unmappedPages();
                } else {
                    final MemorySegment seg = region.segment();
                    for (int i = 0; i < lines; i++) {
                        seg.set(ValueLayout.JAVA_LONG, (long) i * LINE, (long) cycle[i] * LINE);
                    }
                }
            } catch (RuntimeException e) {
                bad = "allocation on node " + b + " failed: " + e.getMessage();
            }
            for (int ai = 0; ai < n; ai++) {
                final int a = nodes.get(ai);
                final List<Integer> cpusA = topology.allowedCpusOnNode(a);
                if (bad != null) {
                    invalid.add(invalid("latency", a, b, bad));
                } else if (cpusA.isEmpty()) {
                    invalid.add(invalid("latency", a, b, "no allowed CPU on node " + a));
                } else {
                    final double[] r = latency(affinity, region.segment(), cpusA.get(0), p);
                    if (Double.isNaN(r[0])) {
                        invalid.add(invalid("latency", a, b, "pinning to cpu " + cpusA.get(0) + " failed or was not effective"));
                    } else {
                        lat[ai][bi] = r[0];
                        latMin[ai][bi] = r[1];
                        latMax[ai][bi] = r[2];
                    }
                }
                final List<Integer> cpusB = topology.allowedCpusOnNode(b);
                final int cpuA = cpusA.isEmpty() ? -1 : cpusA.get(0);
                final int cpuB;
                if (a == b) {
                    cpuB = cpusA.size() >= 2 ? cpusA.get(1) : -1;
                } else {
                    cpuB = cpusB.isEmpty() ? -1 : cpusB.get(0);
                }
                if (cpuA < 0 || cpuB < 0) {
                    invalid.add(invalid("pingPong", a, b, a == b ? "not enough CPUs: node " + a + " has " + cpusA.size()
                        + " allowed CPU(s), need 2 for the same-node ping-pong" : "no allowed CPU on node " + (cpuA < 0 ? a : b)));
                } else {
                    final String err = pingPong(affinity, cpuA, cpuB, p, pp, ai, bi);
                    if (err != null) {
                        invalid.add(invalid("pingPong", a, b, err));
                    }
                }
            }
            if (region != null) {
                region.close();
            }
        }

        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "numa-disruptor-calibration-v1");
        out.put("nodeIds", nodes);
        out.put("latencyNs", rows(lat));
        out.put("latencyMinNs", rows(latMin));
        out.put("latencyMaxNs", rows(latMax));
        out.put("pingPongNs", rows(pp));
        out.put("pingPongNote", "median round trip (A->B->A, two ownership transfers) in ns; same-node entries use two distinct CPUs");
        out.put("invalid", invalid);
        final Map<String, Object> params = new LinkedHashMap<>();
        params.put("bufferBytes", (long) lines * LINE);
        params.put("reps", p.reps());
        params.put("loadsPerRep", p.loadsPerRep());
        params.put("pingPongRounds", p.pingPongRounds());
        params.put("seed", p.seed());
        out.put("params", params);
        out.put("metadata", RunMetadata.collect(topology));
        return out;
    }

    /** @return {median, min, max} ns/load, or NaN median if the thread could not be pinned */
    private static double[] latency(final CpuAffinity affinity, final MemorySegment seg, final int cpu, final Params p) {
        final double[] result = {Double.NaN, Double.NaN, Double.NaN};
        final Thread t = new Thread(() -> {
            try {
                affinity.pinCurrentThread(CpuAffinity.cpu(cpu));
            } catch (RuntimeException e) {
                return;
            }
            final int now = affinity.currentCpu();
            if (now >= 0 && now != cpu) {
                return;
            }
            long idx = chase(seg, 0, p.loadsPerRep() / 4 + 1000); // warm up JIT, TLB and the page tables
            final double[] ns = new double[p.reps()];
            for (int r = 0; r < ns.length; r++) {
                final long t0 = System.nanoTime();
                idx = chase(seg, idx, p.loadsPerRep());
                ns[r] = (System.nanoTime() - t0) / (double) p.loadsPerRep();
            }
            sink = idx;
            Arrays.sort(ns);
            result[0] = median(ns);
            result[1] = ns[0];
            result[2] = ns[ns.length - 1];
        }, "calibrate-chase-" + cpu);
        run(t);
        return result;
    }

    private static long chase(final MemorySegment seg, final long start, final long loads) {
        long off = start;
        for (long i = 0; i < loads; i++) {
            off = seg.get(ValueLayout.JAVA_LONG, off);
        }
        return off;
    }

    /** @return null on success (result stored), otherwise the reason it failed */
    private static String pingPong(final CpuAffinity affinity, final int cpuA, final int cpuB, final Params p, final double[][] out,
                                   final int ai, final int bi) {
        final double[] ns = new double[p.reps()];
        for (int r = 0; r < ns.length; r++) {
            final Cell c = new Cell();
            final int rounds = p.pingPongRounds();
            final double[] elapsed = {Double.NaN};
            final Thread tb = new Thread(() -> {
                if (!pin(affinity, cpuB, c)) {
                    return;
                }
                c.ready = true;
                for (long i = 0; i < rounds; i++) {
                    if (!await(c, 2 * i + 1)) {
                        return;
                    }
                    COUNTER.setRelease(c, 2 * i + 2);
                }
            }, "calibrate-pp-b-" + cpuB);
            final Thread ta = new Thread(() -> {
                if (!pin(affinity, cpuA, c)) {
                    return;
                }
                while (!c.ready) {
                    if (c.abort) {
                        return;
                    }
                    Thread.onSpinWait();
                }
                final long t0 = System.nanoTime();
                for (long i = 0; i < rounds; i++) {
                    COUNTER.setRelease(c, 2 * i + 1);
                    if (!await(c, 2 * i + 2)) {
                        return;
                    }
                }
                elapsed[0] = (System.nanoTime() - t0) / (double) rounds;
            }, "calibrate-pp-a-" + cpuA);
            tb.start();
            ta.start();
            join(ta);
            join(tb);
            if (Double.isNaN(elapsed[0])) {
                return "ping-pong threads failed: " + c.error;
            }
            ns[r] = elapsed[0];
        }
        Arrays.sort(ns);
        out[ai][bi] = median(ns);
        return null;
    }

    private static boolean pin(final CpuAffinity affinity, final int cpu, final Cell c) {
        try {
            affinity.pinCurrentThread(CpuAffinity.cpu(cpu));
            final int now = affinity.currentCpu();
            if (now >= 0 && now != cpu) {
                throw new IllegalStateException("running on cpu " + now + " after pinning to " + cpu);
            }
            return true;
        } catch (RuntimeException e) {
            c.error = "cpu " + cpu + ": " + e.getMessage();
            c.abort = true;
            return false;
        }
    }

    private static boolean await(final Cell c, final long value) {
        while ((long) COUNTER.getAcquire(c) != value) {
            if (c.abort) {
                return false;
            }
            Thread.onSpinWait();
        }
        return true;
    }

    private static void run(final Thread t) {
        t.start();
        join(t);
    }

    private static void join(final Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    static double median(final double[] sorted) {
        final int m = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[m] : (sorted[m - 1] + sorted[m]) / 2;
    }

    private static double[][] nan(final int n) {
        final double[][] m = new double[n][n];
        for (final double[] row : m) {
            Arrays.fill(row, Double.NaN);
        }
        return m;
    }

    /** NaN is written as JSON null by MiniJson. */
    private static List<List<Double>> rows(final double[][] m) {
        final List<List<Double>> out = new ArrayList<>();
        for (final double[] row : m) {
            final List<Double> r = new ArrayList<>();
            for (final double v : row) {
                r.add(v);
            }
            out.add(r);
        }
        return out;
    }

    private static Map<String, Object> invalid(final String measurement, final int from, final int to, final String reason) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("measurement", measurement);
        m.put("from", from);
        m.put("to", to);
        m.put("reason", reason);
        return m;
    }
}
