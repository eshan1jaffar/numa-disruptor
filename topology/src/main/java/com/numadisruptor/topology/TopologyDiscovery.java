package com.numadisruptor.topology;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Discovers the NUMA topology from the Linux machine interfaces ({@code /sys/devices/system},
 * {@code /proc/self/status}, cgroup files). Never parses {@code lscpu}.
 *
 * <p>The roots are injectable so the parser is unit-tested against fabricated directory trees.
 */
public final class TopologyDiscovery {
    private static final Pattern MEMTOTAL = Pattern.compile("MemTotal:\\s+(\\d+)\\s*kB");

    private TopologyDiscovery() { }

    /** Discover the topology of the machine running this JVM. Falls back to one node on non-Linux hosts. */
    public static NumaTopology discover() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux") || !Files.isDirectory(Path.of("/sys/devices/system/cpu"))) {
            return fallback();
        }
        try {
            return discover(
                Path.of("/sys/devices/system"),
                Path.of("/proc/self/status"),
                firstExisting(Path.of("/sys/fs/cgroup/cpu.max"), Path.of("/sys/fs/cgroup/cpu/cpu.cfs_quota_us")));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to read Linux topology: " + e, e);
        }
    }

    private static Path firstExisting(final Path... paths) {
        for (final Path p : paths) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /** Single-node topology sized from {@link Runtime#availableProcessors()}; used where Linux data is absent. */
    public static NumaTopology fallback() {
        final int n = Runtime.getRuntime().availableProcessors();
        final List<Integer> ids = new ArrayList<>();
        final List<CpuTopology> cpus = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(i);
            // Without sysfs we cannot know the SMT layout; treat each CPU as its own core (documented limitation).
            cpus.add(new CpuTopology(i, 0, 0, i, List.of(i)));
        }
        final BitSet all = new BitSet();
        all.set(0, n);
        final BitSet node0 = new BitSet();
        node0.set(0);
        return new NumaTopology(
            NumaTopology.Source.FALLBACK_SINGLE_NODE,
            List.of(new NumaNode(0, 0, ids, -1)), cpus, new int[][] {{10}}, all, node0, null);
    }

    /**
     * Parses a topology from the given roots.
     *
     * @param sysDevicesSystem   directory laid out like {@code /sys/devices/system} (contains cpu/ and node/)
     * @param procSelfStatus     file laid out like {@code /proc/self/status}; may be null or missing (all allowed)
     * @param cgroupCpuMaxFile   optional file containing the cgroup cpu quota
     */
    public static NumaTopology discover(
        final Path sysDevicesSystem, final Path procSelfStatus, final Path cgroupCpuMaxFile) throws IOException {
        final Path cpuDir = sysDevicesSystem.resolve("cpu");
        final Path nodeDir = sysDevicesSystem.resolve("node");

        final BitSet online = CpuList.parse(readTrim(cpuDir.resolve("online")));

        // --- NUMA nodes ---
        final TreeMap<Integer, BitSet> nodeCpus = new TreeMap<>();
        final TreeMap<Integer, Long> nodeMem = new TreeMap<>();
        if (Files.isDirectory(nodeDir)) {
            try (Stream<Path> s = Files.list(nodeDir)) {
                for (final Path p : (Iterable<Path>) s::iterator) {
                    final String name = p.getFileName().toString();
                    if (name.matches("node\\d+") && Files.isDirectory(p)) {
                        final int id = Integer.parseInt(name.substring(4));
                        final BitSet cpus = CpuList.parse(readTrim(p.resolve("cpulist")));
                        cpus.and(online);
                        nodeCpus.put(id, cpus);
                        nodeMem.put(id, readMemTotal(p.resolve("meminfo")));
                    }
                }
            }
        }
        if (nodeCpus.isEmpty()) { // kernel without NUMA support: one implicit node
            nodeCpus.put(0, (BitSet) online.clone());
            nodeMem.put(0, readMemTotal(Path.of("/proc/meminfo")));
        }
        final List<Integer> nodeIds = List.copyOf(nodeCpus.keySet());

        // --- distances (columns are the online nodes in ascending order) ---
        final int[][] dist = new int[nodeIds.size()][nodeIds.size()];
        boolean distancesGuessed = false;
        for (int r = 0; r < nodeIds.size(); r++) {
            final Path f = nodeDir.resolve("node" + nodeIds.get(r)).resolve("distance");
            final String[] cols = Files.isRegularFile(f) ? readTrim(f).split("\\s+") : new String[0];
            for (int c = 0; c < nodeIds.size(); c++) {
                if (cols.length == nodeIds.size()) {
                    dist[r][c] = Integer.parseInt(cols[c]);
                } else {
                    dist[r][c] = r == c ? 10 : 20; // unknown: neutral remote distance
                    distancesGuessed |= nodeIds.size() > 1;
                }
            }
        }

        // --- CPUs ---
        final List<CpuTopology> cpuList = new ArrayList<>();
        final List<NumaNode> nodeList = new ArrayList<>();
        final TreeMap<Integer, Integer> nodeSocket = new TreeMap<>();
        for (int cpu = online.nextSetBit(0); cpu >= 0; cpu = online.nextSetBit(cpu + 1)) {
            final int nodeId = ownerNode(nodeCpus, cpu);
            final Path topo = cpuDir.resolve("cpu" + cpu).resolve("topology");
            final int socket = readIntOr(topo.resolve("physical_package_id"), 0);
            final int core = readIntOr(topo.resolve("core_id"), cpu);
            final Path sibFile = topo.resolve("thread_siblings_list");
            final List<Integer> siblings = new ArrayList<>();
            if (Files.isRegularFile(sibFile)) {
                final BitSet sib = CpuList.parse(readTrim(sibFile));
                sib.and(online);
                sib.stream().forEach(siblings::add);
            }
            if (!siblings.contains(cpu)) {
                siblings.add(cpu);
                siblings.sort(Integer::compare);
            }
            cpuList.add(new CpuTopology(cpu, nodeId, socket, core, siblings));
            nodeSocket.putIfAbsent(nodeId, socket);
        }
        for (final int id : nodeIds) {
            final List<Integer> cpus = new ArrayList<>();
            nodeCpus.get(id).stream().forEach(cpus::add);
            nodeList.add(new NumaNode(id, nodeSocket.getOrDefault(id, -1), cpus, nodeMem.get(id)));
        }

        // --- what this process may actually use ---
        final BitSet allowedCpus = (BitSet) online.clone();
        final BitSet allowedNodes = new BitSet();
        nodeIds.forEach(allowedNodes::set);
        if (procSelfStatus != null && Files.isRegularFile(procSelfStatus)) {
            for (final String line : Files.readAllLines(procSelfStatus, StandardCharsets.UTF_8)) {
                if (line.startsWith("Cpus_allowed_list:")) {
                    allowedCpus.and(CpuList.parse(line.substring("Cpus_allowed_list:".length())));
                } else if (line.startsWith("Mems_allowed_list:")) {
                    allowedNodes.and(CpuList.parse(line.substring("Mems_allowed_list:".length())));
                }
            }
        }
        final String cgroupCpuMax = cgroupCpuMaxFile != null && Files.isRegularFile(cgroupCpuMaxFile)
            ? readTrim(cgroupCpuMaxFile) : null;

        return new NumaTopology(NumaTopology.Source.LINUX_SYSFS, nodeList, cpuList, dist, allowedCpus, allowedNodes, cgroupCpuMax, distancesGuessed);
    }

    private static int ownerNode(final TreeMap<Integer, BitSet> nodeCpus, final int cpu) {
        for (final var e : nodeCpus.entrySet()) {
            if (e.getValue().get(cpu)) {
                return e.getKey();
            }
        }
        return nodeCpus.firstKey(); // CPU not attributed to any node (rare); keep it usable on the first node
    }

    private static String readTrim(final Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8).trim();
    }

    private static int readIntOr(final Path p, final int dflt) {
        try {
            return Integer.parseInt(readTrim(p));
        } catch (IOException | NumberFormatException e) {
            return dflt;
        }
    }

    private static long readMemTotal(final Path meminfo) {
        if (meminfo == null) {
            return -1;
        }
        try {
            for (final String line : Files.readAllLines(meminfo, StandardCharsets.UTF_8)) {
                final Matcher m = MEMTOTAL.matcher(line);
                if (m.find()) {
                    return Long.parseLong(m.group(1)) * 1024L;
                }
            }
        } catch (IOException e) {
            // fall through: unknown
        }
        return -1;
    }
}
