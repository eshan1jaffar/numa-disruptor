package com.numadisruptor.benchmarks;

import com.numadisruptor.runtime.ConsumptionMode;
import com.numadisruptor.runtime.WaitStrategyKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named experiment lists. Order matters: the runner executes trial 1 of every experiment before trial 2 of any, so a run that
 * is cut short by the time budget still covers the whole matrix with fewer repetitions instead of part of it with many.
 */
final class Matrices {
    private Matrices() { }

    static List<ExperimentConfig> byName(final String name, final int warmup, final int measure) {
        final List<ExperimentConfig> list = switch (name) {
            case "smoke" -> smoke();
            case "locality" -> locality();
            case "scale" -> scale();
            case "sweeps" -> sweeps();
            case "all" -> {
                final List<ExperimentConfig> all = new ArrayList<>(locality());
                all.addAll(scale());
                all.addAll(sweeps());
                yield all;
            }
            default -> throw new IllegalArgumentException("unknown matrix '" + name + "' (smoke, locality, scale, sweeps, all)");
        };
        // de-duplicate by id, apply durations (smoke keeps its own short ones)
        final Map<String, ExperimentConfig> unique = new LinkedHashMap<>();
        for (final ExperimentConfig c : list) {
            if (!name.equals("smoke")) {
                c.warmupSeconds = warmup;
                c.measureSeconds = measure;
            }
            unique.putIfAbsent(c.id(), c);
        }
        return new ArrayList<>(unique.values());
    }

    private static ExperimentConfig cfg(final String... kv) {
        final Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return ExperimentConfig.of(m);
    }

    private static ExperimentConfig with(final ExperimentConfig base, final String... kv) {
        final Map<String, String> m = base.toStringMap();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return ExperimentConfig.of(m);
    }

    static List<ExperimentConfig> smoke() {
        final List<ExperimentConfig> l = new ArrayList<>();
        for (final String[] vl : new String[][] {{"V0", "OS"}, {"V1", "DENSE"}, {"V2", "SAME_NODE"}, {"V3", "AUTO"}}) {
            l.add(cfg("variant", vl[0], "locality", vl[1], "partitions", "2", "producers", "1", "consumers", "1",
                "ring", "4096", "bytes", "64", "wait", "YIELDING", "warmup", "1", "measure", "1"));
        }
        l.add(cfg("variant", "V0", "locality", "OS", "partitions", "2", "ring", "4096", "wait", "YIELDING", "metric", "LATENCY",
            "rate", "50000", "warmup", "1", "measure", "2"));
        return l;
    }

    /** Controlled locality: one producer, strategy stage and gateway per partition, placement is the only variable. */
    static List<ExperimentConfig> locality() {
        final List<ExperimentConfig> l = new ArrayList<>();
        for (final String metric : new String[] {"THROUGHPUT", "LATENCY"}) {
            final String[] common = {"partitions", "1", "producers", "1", "consumers", "1", "stages", "1", "ring", "65536",
                "bytes", "64", "wait", "BUSY_SPIN", "metric", metric, "rate", "200000"};
            l.add(with(cfg(common), "variant", "V0", "locality", "OS"));
            for (final String loc : new String[] {"SAME_NODE", "SAME_SOCKET", "CROSS_SOCKET"}) {
                for (final String home : new String[] {"PRODUCER", "CONSUMER"}) {
                    l.add(with(cfg(common), "variant", "V1", "locality", loc, "ringHome", home));
                    l.add(with(cfg(common), "variant", "V2", "locality", loc, "ringHome", home));
                }
            }
            l.add(with(cfg(common), "variant", "V3", "locality", "AUTO"));
            // multi-node: partitions spread over every usable node on both sockets
            for (final int parts : new int[] {8}) {
                final String[] multi = {"partitions", Integer.toString(parts), "producers", "1", "consumers", "1", "stages", "1",
                    "ring", "16384", "bytes", "64", "wait", "BUSY_SPIN", "metric", metric, "rate", "800000"};
                l.add(with(cfg(multi), "variant", "V0", "locality", "OS"));
                l.add(with(cfg(multi), "variant", "V1", "locality", "MULTI_NODE_SPREAD"));
                l.add(with(cfg(multi), "variant", "V1", "locality", "MULTI_NODE_LOCAL"));
                l.add(with(cfg(multi), "variant", "V2", "locality", "MULTI_NODE_SPREAD"));
                l.add(with(cfg(multi), "variant", "V2", "locality", "MULTI_NODE_LOCAL"));
                l.add(with(cfg(multi), "variant", "V3", "locality", "AUTO"));
            }
        }
        // off-heap payload, separately benchmarked (needs libnuma)
        final String[] oh = {"partitions", "1", "ring", "16384", "bytes", "1024", "wait", "BUSY_SPIN"};
        l.add(with(cfg(oh), "variant", "V2", "locality", "CROSS_SOCKET", "ringHome", "PRODUCER", "offheap", "true"));
        l.add(with(cfg(oh), "variant", "V2", "locality", "CROSS_SOCKET", "ringHome", "PRODUCER"));
        return l;
    }

    /** Partition-count scaling: independent streams across the allowed topology, all variants. */
    static List<ExperimentConfig> scale() {
        final List<ExperimentConfig> l = new ArrayList<>();
        for (final int p : new int[] {1, 2, 4, 8, 16}) {
            final String[] common = {"partitions", Integer.toString(p), "producers", "1", "consumers", "1", "stages", "1",
                "ring", "16384", "bytes", "64", "wait", "BUSY_SPIN"};
            l.add(with(cfg(common), "variant", "V0", "locality", "OS"));
            l.add(with(cfg(common), "variant", "V1", "locality", "DENSE"));
            l.add(with(cfg(common), "variant", "V1", "locality", "ROUND_ROBIN"));
            l.add(with(cfg(common), "variant", "V2", "locality", "DENSE"));
            l.add(with(cfg(common), "variant", "V3", "locality", "AUTO"));
        }
        return l;
    }

    /** One-factor-at-a-time sweeps around a base configuration, for V0, V1(dense) and V3. */
    static List<ExperimentConfig> sweeps() {
        final List<ExperimentConfig> l = new ArrayList<>();
        final ExperimentConfig base = cfg("partitions", "4", "producers", "1", "consumers", "1", "stages", "1", "ring", "16384",
            "bytes", "64", "wait", "BUSY_SPIN", "variant", "V0", "locality", "OS");
        final List<ExperimentConfig> shapes = new ArrayList<>();
        shapes.add(base);
        for (final int v : new int[] {2, 4}) {
            shapes.add(with(base, "producers", Integer.toString(v)));
        }
        for (final int v : new int[] {2, 4}) {
            shapes.add(with(base, "consumers", Integer.toString(v)));
            shapes.add(with(base, "consumers", Integer.toString(v), "mode", ConsumptionMode.WORK_DISTRIBUTION.name()));
        }
        for (final int v : new int[] {1024, 65536, 1 << 20}) {
            shapes.add(with(base, "ring", Integer.toString(v), "bytes", "64"));
        }
        for (final int v : new int[] {256, 1024, 4096}) {
            shapes.add(with(base, "bytes", Integer.toString(v), "ring", "4096"));
        }
        for (final WaitStrategyKind w : new WaitStrategyKind[] {WaitStrategyKind.YIELDING, WaitStrategyKind.SLEEPING, WaitStrategyKind.BLOCKING}) {
            shapes.add(with(base, "wait", w.name()));
        }
        for (final int v : new int[] {100, 1000, 10000}) {
            shapes.add(with(base, "work", Integer.toString(v)));
        }
        shapes.add(with(base, "stages", "0"));
        for (final ExperimentConfig s : shapes) {
            l.add(with(s, "variant", "V0", "locality", "OS"));
            l.add(with(s, "variant", "V1", "locality", "DENSE"));
            l.add(with(s, "variant", "V3", "locality", "AUTO"));
        }
        return l;
    }
}
