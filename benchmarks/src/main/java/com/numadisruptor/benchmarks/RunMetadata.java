package com.numadisruptor.benchmarks;

import com.numadisruptor.topology.CpuList;
import com.numadisruptor.topology.NumaTopology;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Everything needed to reproduce or interpret a measurement: code version, machine, kernel, JVM, topology and job limits. */
final class RunMetadata {
    private RunMetadata() { }

    static Map<String, Object> collect(final NumaTopology topology) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", Instant.now().toString());
        m.put("gitCommit", gitCommit());
        m.put("projectVersion", buildInfo().getProperty("version", "unknown"));
        m.put("host", anonymizedHost());
        m.put("cpuModel", cpuModel());
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        m.put("kernel", read(Path.of("/proc/sys/kernel/osrelease")));
        m.put("jvm", System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version"));
        m.put("jvmFlags", ManagementFactory.getRuntimeMXBean().getInputArguments());
        m.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        m.put("disruptorVersion", disruptorVersion());
        m.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        m.put("topologySource", topology.source().name());
        m.put("allowedCpus", CpuList.format(topology.allowedCpus()));
        m.put("allowedMemNodes", CpuList.format(topology.allowedNodes()));
        m.put("usableNodes", topology.usableNodes());
        m.put("topology", topology.describe());
        m.put("cgroupCpuMax", topology.cgroupCpuMax());
        m.put("slurm", slurmEnv());
        m.put("kernelSettings", kernelSettings());
        m.put("hardwareCounters", hardwareCounterStatus());
        return m;
    }

    static Properties buildInfo() {
        final Properties p = new Properties();
        try (InputStream in = RunMetadata.class.getResourceAsStream("/build-info.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            // leave empty
        }
        return p;
    }

    static String gitCommit() {
        final String env = System.getenv("GIT_COMMIT");
        if (env != null && !env.isBlank()) {
            return env;
        }
        final String stamped = buildInfo().getProperty("gitCommit", "unknown");
        return stamped.isBlank() || stamped.startsWith("$") ? "unknown" : stamped;
    }

    private static String disruptorVersion() {
        try (InputStream in = com.lmax.disruptor.RingBuffer.class.getResourceAsStream("/META-INF/maven/com.lmax/disruptor/pom.properties")) {
            if (in != null) {
                final Properties p = new Properties();
                p.load(in);
                return p.getProperty("version", "unknown");
            }
        } catch (IOException e) {
            // fall through
        }
        final String impl = com.lmax.disruptor.RingBuffer.class.getPackage().getImplementationVersion();
        return impl != null ? impl : "4.0.0 (pinned in gradle/libs.versions.toml)";
    }

    private static String anonymizedHost() {
        try {
            final String host = java.net.InetAddress.getLocalHost().getHostName();
            final byte[] d = MessageDigest.getInstance("SHA-256").digest(host.getBytes(StandardCharsets.UTF_8));
            return "host-" + HexFormat.of().formatHex(d, 0, 6);
        } catch (Exception e) {
            return "host-unknown";
        }
    }

    private static String cpuModel() {
        try {
            for (final String line : Files.readAllLines(Path.of("/proc/cpuinfo"), StandardCharsets.UTF_8)) {
                if (line.startsWith("model name")) {
                    return line.substring(line.indexOf(':') + 1).trim();
                }
            }
        } catch (IOException e) {
            // not Linux
        }
        return System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown");
    }

    private static Map<String, Object> slurmEnv() {
        final Map<String, Object> out = new TreeMap<>();
        System.getenv().forEach((k, v) -> {
            if (k.startsWith("SLURM_") && (k.contains("CPU") || k.contains("MEM") || k.contains("JOB_ID") || k.contains("NODE")
                || k.contains("TIME") || k.contains("PARTITION") || k.contains("DISTRIBUTION"))) {
                out.put(k, v);
            }
        });
        return out;
    }

    /** Linux knobs that change NUMA behaviour; recorded because they confound results. */
    private static Map<String, Object> kernelSettings() {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("numa_balancing", read(Path.of("/proc/sys/kernel/numa_balancing")));
        out.put("transparent_hugepage", read(Path.of("/sys/kernel/mm/transparent_hugepage/enabled")));
        out.put("perf_event_paranoid", read(Path.of("/proc/sys/kernel/perf_event_paranoid")));
        out.put("scaling_governor", read(Path.of("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")));
        return out;
    }

    /** What hardware-counter access is possible; the harness itself does not read PMCs (use perf around it if allowed). */
    static Map<String, Object> hardwareCounterStatus() {
        final Map<String, Object> out = new LinkedHashMap<>();
        final String paranoid = read(Path.of("/proc/sys/kernel/perf_event_paranoid"));
        boolean perfOnPath = false;
        final String path = System.getenv("PATH");
        if (path != null) {
            for (final String dir : path.split(java.io.File.pathSeparator)) {
                if (Files.isExecutable(Path.of(dir, "perf"))) {
                    perfOnPath = true;
                    break;
                }
            }
        }
        out.put("perfBinaryOnPath", perfOnPath);
        out.put("perfEventParanoid", paranoid);
        final boolean usable = perfOnPath && paranoid != null && !paranoid.isBlank() && Integer.parseInt(paranoid.trim()) <= 2;
        out.put("userSpaceCountersLikelyUsable", usable);
        out.put("note", usable
            ? "perf may be usable: run via scripts/hpc/perf-wrap.sh to collect remote-memory/coherence events"
            : "no hardware counters available to this job; only software proxies (numastat deltas, migration probes) are recorded");
        return out;
    }

    private static String read(final Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    static List<String> jvmFlags() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments();
    }
}
