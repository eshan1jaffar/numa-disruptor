package com.numadisruptor.benchmarks;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Point-in-time process/system counters; differences between two snapshots give per-window rates.
 *
 * <p>{@code numaStat} is the kernel's per-node <i>page-allocation</i> counters (numa_hit/miss/foreign/local_node/other_node).
 * They show where pages were allocated, not how often remote memory was accessed, so they are a placement proxy only and
 * are system-wide (other jobs on the node pollute them).
 */
record SystemSnapshot(
    long wallNanos, long processCpuNanos, long gcCount, long gcMillis, long allocatedBytes,
    Map<Integer, Map<String, Long>> numaStat) {

    static SystemSnapshot take() {
        long gcCount = 0;
        long gcMillis = 0;
        for (final GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMillis += Math.max(0, gc.getCollectionTime());
        }
        long cpu = -1;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            cpu = os.getProcessCpuTime();
        }
        long alloc = -1;
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean tmx && tmx.isThreadAllocatedMemorySupported()) {
            alloc = 0;
            for (final long b : tmx.getThreadAllocatedBytes(tmx.getAllThreadIds())) {
                alloc += Math.max(0, b);
            }
        }
        return new SystemSnapshot(System.nanoTime(), cpu, gcCount, gcMillis, alloc, readNumaStat());
    }

    static Map<Integer, Map<String, Long>> readNumaStat() {
        final Map<Integer, Map<String, Long>> out = new TreeMap<>();
        final Path root = Path.of("/sys/devices/system/node");
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (var dirs = Files.list(root)) {
            for (final Path d : (Iterable<Path>) dirs::iterator) {
                final String name = d.getFileName().toString();
                if (!name.matches("node\\d+")) {
                    continue;
                }
                final Map<String, Long> counters = new LinkedHashMap<>();
                for (final String line : Files.readAllLines(d.resolve("numastat"), StandardCharsets.UTF_8)) {
                    final String[] kv = line.trim().split("\\s+");
                    if (kv.length == 2) {
                        counters.put(kv[0], Long.parseLong(kv[1]));
                    }
                }
                out.put(Integer.parseInt(name.substring(4)), counters);
            }
        } catch (IOException | RuntimeException e) {
            return new TreeMap<>(); // unavailable in this environment
        }
        return out;
    }

    /** Result of {@code end - start}. */
    record Delta(
        double seconds, double cpuUtilizationPercent, long gcCount, long gcMillis, long allocatedBytes,
        Map<Integer, Map<String, Long>> numaStat) { }

    static Delta between(final SystemSnapshot a, final SystemSnapshot b) {
        final double sec = (b.wallNanos - a.wallNanos) / 1e9;
        final double util = a.processCpuNanos < 0 || b.processCpuNanos < 0 ? Double.NaN
            : 100.0 * (b.processCpuNanos - a.processCpuNanos) / (b.wallNanos - a.wallNanos);
        final Map<Integer, Map<String, Long>> numa = new TreeMap<>();
        b.numaStat.forEach((node, after) -> {
            final Map<String, Long> before = a.numaStat.getOrDefault(node, Map.of());
            final Map<String, Long> d = new LinkedHashMap<>();
            after.forEach((k, v) -> d.put(k, v - before.getOrDefault(k, 0L)));
            numa.put(node, d);
        });
        return new Delta(sec, util, b.gcCount - a.gcCount, b.gcMillis - a.gcMillis,
            a.allocatedBytes < 0 ? -1 : b.allocatedBytes - a.allocatedBytes, numa);
    }
}
