package com.numadisruptor.benchmarks;

import com.numadisruptor.affinity.CpuAffinity;
import com.numadisruptor.topology.NumaTopology;
import com.numadisruptor.topology.TopologyDiscovery;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end benchmark driver.
 *
 * <pre>
 *   topology                         print what this job may use
 *   list   --matrix NAME             print experiment ids
 *   run    --matrix NAME [options]   run a matrix, appending results.jsonl / results.csv
 *   run-one key=value ... --trial N  run one trial in this JVM, print RESULT_JSON:{...}
 *   verify [--events N] [--allow-skip-pinned]
 *                                    fixed-count check of V0..V3 against an independent reference; exits 1 if V0 fails or a
 *                                    pinned variant (V1-V3) is skipped, unless --allow-skip-pinned
 * </pre>
 * run options: {@code --out DIR --trials N --warmup S --measure S --budget-seconds B --resume --no-fork --keep-failed --filter TEXT --jvm-args "..."}
 */
public final class HarnessMain {
    private static final String RESULT_PREFIX = "RESULT_JSON:";
    private static final String DEFAULT_JVM_ARGS =
        "-Xms3g -Xmx3g -Xmn2g -XX:+UseParallelGC -XX:+UseNUMA -XX:+AlwaysPreTouch --enable-native-access=ALL-UNNAMED";

    private HarnessMain() { }

    public static void main(final String[] argv) throws Exception {
        if (argv.length == 0) {
            usage();
            return;
        }
        final String cmd = argv[0];
        final Map<String, String> opts = parse(argv, 1);
        switch (cmd) {
            case "topology" -> System.out.print(TopologyDiscovery.discover().describe());
            case "list" -> {
                final int trials = Integer.parseInt(opts.getOrDefault("trials", "1"));
                for (final ExperimentConfig c : Matrices.byName(opts.getOrDefault("matrix", "all"), 3, 5)) {
                    System.out.println(c.id() + " (x" + trials + ", ~" + c.expectedSeconds() + " s each)");
                }
            }
            case "run" -> runMatrix(opts);
            case "run-one" -> runOne(opts);
            case "verify" -> System.exit(verify(Long.parseLong(opts.getOrDefault("events", "200000")), opts.containsKey("allow-skip-pinned")) ? 0 : 1);
            default -> usage();
        }
    }

    private static void usage() {
        System.out.println("usage: HarnessMain topology | list --matrix M | run --matrix M [--out DIR --trials N --warmup S --measure S"
            + " --budget-seconds B --resume --no-fork --keep-failed --filter T --jvm-args \"...\"] | run-one k=v... --trial N | verify [--events N] [--allow-skip-pinned]");
    }

    static Map<String, String> parse(final String[] argv, final int from) {
        final Map<String, String> m = new LinkedHashMap<>();
        for (int i = from; i < argv.length; i++) {
            String a = argv[i];
            if (a.startsWith("--")) {
                a = a.substring(2);
                final int eq = a.indexOf('=');
                if (eq >= 0) {
                    m.put(a.substring(0, eq), a.substring(eq + 1));
                } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                    m.put(a, argv[++i]);
                } else {
                    m.put(a, "true");
                }
            } else if (a.contains("=")) {
                m.put(a.substring(0, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ matrix driver

    private static void runMatrix(final Map<String, String> o) throws Exception {
        final String matrix = o.getOrDefault("matrix", "smoke");
        final int trials = Integer.parseInt(o.getOrDefault("trials", "3"));
        final int warmup = Integer.parseInt(o.getOrDefault("warmup", "3"));
        final int measure = Integer.parseInt(o.getOrDefault("measure", "5"));
        final long budgetSec = Long.parseLong(o.getOrDefault("budget-seconds", "0"));
        final boolean fork = !o.containsKey("no-fork");
        final String filter = o.get("filter");
        final ResultsStore store = new ResultsStore(Path.of(o.getOrDefault("out", "results/" + matrix)));
        final Set<String> done = o.containsKey("resume") ? store.completedKeys(o.containsKey("keep-failed")) : Set.of();
        final long start = System.nanoTime();

        final List<ExperimentConfig> configs = new ArrayList<>(Matrices.byName(matrix, warmup, measure));
        if (filter != null) {
            configs.removeIf(c -> !c.id().contains(filter));
        }
        final NumaTopology topology = TopologyDiscovery.discover();
        final Map<String, Object> inProcessMetadata = fork ? null : RunMetadata.collect(topology);
        final CpuAffinity affinity = CpuAffinity.system();

        int ran = 0;
        int skippedDone = 0;
        int notStarted = 0;
        boolean budgetHit = false;
        final Map<String, Integer> statusCount = new LinkedHashMap<>();
        for (int trial = 1; trial <= trials; trial++) {
            for (final ExperimentConfig c : configs) {
                if (done.contains(ResultsStore.key(c.id(), trial))) {
                    skippedDone++;
                    continue;
                }
                final double elapsed = (System.nanoTime() - start) / 1e9;
                if (budgetSec > 0 && elapsed + c.expectedSeconds() + 60 > budgetSec) {
                    budgetHit = true;
                    notStarted++;
                    continue;
                }
                System.out.printf("[%6.0fs] %s trial %d%n", elapsed, c.id(), trial);
                final Map<String, Object> result = fork
                    ? runForked(c, trial, o.getOrDefault("jvm-args", DEFAULT_JVM_ARGS))
                    : ExperimentRunner.run(c, trial, topology, affinity, inProcessMetadata);
                store.append(result);
                ran++;
                statusCount.merge(String.valueOf(result.get("status")), 1, Integer::sum);
                if (!"ok".equals(result.get("status"))) {
                    System.out.println("    -> " + result.get("status") + ": " + result.get("reason"));
                }
            }
        }
        final Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("matrix", matrix);
        summary.put("trialsRequested", trials);
        summary.put("ran", ran);
        summary.put("alreadyDone", skippedDone);
        summary.put("notStartedDueToBudget", notStarted);
        summary.put("budgetExhausted", budgetHit);
        summary.put("statusCounts", statusCount);
        summary.put("elapsedSeconds", (System.nanoTime() - start) / 1e9);
        Files.writeString(store.dir().resolve("run-summary-" + System.currentTimeMillis() + ".json"), MiniJson.write(summary) + "\n");
        System.out.println(MiniJson.write(summary));
    }

    private static Map<String, Object> runForked(final ExperimentConfig c, final int trial, final String jvmArgs) {
        final String java = ProcessHandle.current().info().command().orElse("java");
        final List<String> cmd = new ArrayList<>();
        cmd.add(java);
        for (final String a : jvmArgs.trim().split("\\s+")) {
            if (!a.isEmpty()) {
                cmd.add(a);
            }
        }
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(HarnessMain.class.getName());
        cmd.add("run-one");
        cmd.add("--trial=" + trial);
        c.toStringMap().forEach((k, v) -> cmd.add(k + "=" + v));
        final Map<String, Object> fail = new LinkedHashMap<>();
        fail.put("id", c.id());
        fail.put("trial", trial);
        fail.put("workloadKey", c.workloadKey());
        fail.put("config", c.toMap());
        try {
            final Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            String json = null;
            final StringBuilder other = new StringBuilder();
            final BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(c.expectedSeconds() + 120L);
            // read lines on this thread; a watchdog kills the child if it overruns
            final Thread watchdog = new Thread(() -> {
                try {
                    if (!p.waitFor(Math.max(1, (deadline - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS)) {
                        p.destroyForcibly();
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(RESULT_PREFIX)) {
                    json = line.substring(RESULT_PREFIX.length());
                } else {
                    other.append(line).append('\n');
                }
            }
            final int rc = p.waitFor();
            if (json == null) {
                fail.put("status", "failed");
                fail.put("reason", "child JVM exited " + rc + " without a result; output: " + other);
                return fail;
            }
            return MiniJson.parseObject(json);
        } catch (final IOException | InterruptedException e) {
            fail.put("status", "failed");
            fail.put("reason", "could not run child JVM: " + e);
            return fail;
        }
    }

    // ------------------------------------------------------------------ single trial (child process)

    private static void runOne(final Map<String, String> o) {
        final int trial = Integer.parseInt(o.getOrDefault("trial", "1"));
        final Map<String, String> kv = new LinkedHashMap<>(o);
        kv.remove("trial");
        final ExperimentConfig c = ExperimentConfig.of(kv);
        final NumaTopology topology = TopologyDiscovery.discover();
        final Map<String, Object> result = ExperimentRunner.run(c, trial, topology, CpuAffinity.system(), RunMetadata.collect(topology));
        System.out.println(RESULT_PREFIX + MiniJson.write(result));
        System.out.flush();
    }

    // ------------------------------------------------------------------ equivalence verification

    /**
     * Runs the same fixed-count workload under V0, V1, V2 and V3 and checks that every variant delivers exactly the same orders
     * as an independent single-threaded computation. This is the evidence that speed comparisons are between equivalent work.
     */
    static boolean verify(final long events, final boolean allowSkipPinned) throws Exception {
        return verify(events, allowSkipPinned, TopologyDiscovery.discover(), CpuAffinity.system());
    }

    /**
     * A pinned variant (V1-V3) that is skipped, for any reason, fails the check unless {@code allowSkipPinned}: a gate that
     * passes with nothing but V0 having run has verified nothing about pinning. V0 is always required.
     */
    static boolean verify(final long events, final boolean allowSkipPinned, final NumaTopology topology, final CpuAffinity affinity)
        throws Exception {
        boolean ok = true;
        final int partitions = 2;
        final long reference = sumReference(partitions, events);
        System.out.println("reference order checksum (" + partitions + " partitions x " + events + " ticks): " + reference);
        final String[][] variants = {{"V0", "OS"}, {"V1", "DENSE"}, {"V2", "DENSE"}, {"V3", "AUTO"}};
        for (final String[] vl : variants) {
            if (!"V0".equals(vl[0]) && !affinity.isSupported()) {
                System.out.println(vl[0] + ": SKIPPED (pinning unsupported on this host)" + (allowSkipPinned ? "" : " -> FAIL (use --allow-skip-pinned to accept)"));
                ok &= allowSkipPinned;
                continue;
            }
            final ExperimentConfig c = ExperimentConfig.of(Map.of("variant", vl[0], "locality", vl[1], "partitions", Integer.toString(partitions),
                "ring", "1024", "bytes", "64", "wait", "YIELDING"));
            final Map<String, Object> r = ExperimentRunner.verify(c, events, topology, affinity);
            @SuppressWarnings("unchecked")
            final Map<String, Object> corr = (Map<String, Object>) r.get("correctness");
            final boolean good = "ok".equals(r.get("status")) && corr != null
                && ((Number) corr.get("orderChecksum")).longValue() == reference;
            System.out.println(vl[0] + ": " + (good ? "OK " : "MISMATCH ") + (corr != null ? corr : r.get("reason") + " " + r));
            ok &= good || allowSkipPinned && !"V0".equals(vl[0]) && "skipped".equals(r.get("status"));
        }
        return ok;
    }

    private static long sumReference(final int partitions, final long events) {
        long s = 0;
        for (int p = 0; p < partitions; p++) {
            s += TradingWorkload.referenceOrderChecksum(p, events, 64, 0);
        }
        return s;
    }

    static List<String> jvmArgsOfThisProcess() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments();
    }
}
