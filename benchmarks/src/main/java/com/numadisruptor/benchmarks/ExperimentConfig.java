package com.numadisruptor.benchmarks;

import com.numadisruptor.runtime.ConsumptionMode;
import com.numadisruptor.runtime.WaitStrategyKind;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One experiment (a point in the benchmark matrix). Two configs with the same {@link #workloadKey()} do identical work with
 * identical ordering semantics, so throughput/latency ratios between them are apples-to-apples; they differ only in variant
 * and locality (placement).
 */
public final class ExperimentConfig {
    public enum Metric { THROUGHPUT, LATENCY }
    /** Where the ring (ring 0) lives in the controlled locality scenarios. */
    public enum RingHome { PRODUCER, CONSUMER }

    Variant variant = Variant.V0;
    Locality locality = Locality.OS;
    RingHome ringHome = RingHome.PRODUCER;
    int partitions = 1;
    int producersPerPartition = 1;
    int consumersPerPartition = 1;
    /** 1 = trading pipeline (market data ring -> strategy -> order ring -> gateway); 0 = producer feeds consumers directly. */
    int stages = 1;
    ConsumptionMode mode = ConsumptionMode.MULTICAST;
    int ringSize = 1 << 16;
    int eventBytes = 64;
    WaitStrategyKind wait = WaitStrategyKind.BUSY_SPIN;
    int workIterations;
    Metric metric = Metric.THROUGHPUT;
    /** Total offered load for LATENCY runs, events per second across all producers. */
    long targetRate = 200_000;
    int warmupSeconds = 3;
    int measureSeconds = 5;
    boolean offheap;
    int instrumentsPerPartition = 64;

    public static ExperimentConfig of(final Map<String, String> kv) {
        final ExperimentConfig c = new ExperimentConfig();
        kv.forEach((k, v) -> {
            switch (k) {
                case "variant" -> c.variant = Variant.valueOf(v);
                case "locality" -> c.locality = Locality.valueOf(v);
                case "ringHome" -> c.ringHome = RingHome.valueOf(v);
                case "partitions" -> c.partitions = Integer.parseInt(v);
                case "producers" -> c.producersPerPartition = Integer.parseInt(v);
                case "consumers" -> c.consumersPerPartition = Integer.parseInt(v);
                case "stages" -> c.stages = Integer.parseInt(v);
                case "mode" -> c.mode = ConsumptionMode.valueOf(v);
                case "ring" -> c.ringSize = Integer.parseInt(v);
                case "bytes" -> c.eventBytes = Integer.parseInt(v);
                case "wait" -> c.wait = WaitStrategyKind.valueOf(v);
                case "work" -> c.workIterations = Integer.parseInt(v);
                case "metric" -> c.metric = Metric.valueOf(v);
                case "rate" -> c.targetRate = Long.parseLong(v);
                case "warmup" -> c.warmupSeconds = Integer.parseInt(v);
                case "measure" -> c.measureSeconds = Integer.parseInt(v);
                case "offheap" -> c.offheap = Boolean.parseBoolean(v);
                case "instruments" -> c.instrumentsPerPartition = Integer.parseInt(v);
                default -> throw new IllegalArgumentException("unknown experiment key: " + k);
            }
        });
        c.validate();
        return c;
    }

    public ExperimentConfig copy() {
        return of(toStringMap());
    }

    /** Validates cross-field rules; throws IllegalArgumentException describing the first violation. */
    public void validate() {
        if (partitions < 1 || producersPerPartition < 1 || consumersPerPartition < 1) {
            throw new IllegalArgumentException("counts must be >= 1");
        }
        if (stages < 0 || stages > 1) {
            throw new IllegalArgumentException("stages must be 0 or 1");
        }
        if (Integer.bitCount(ringSize) != 1 || ringSize < 2) {
            throw new IllegalArgumentException("ring must be a power of two");
        }
        if (eventBytes < 0 || eventBytes % 8 != 0) {
            throw new IllegalArgumentException("bytes must be a non-negative multiple of 8");
        }
        if (variant == Variant.V0 && locality != Locality.OS) {
            throw new IllegalArgumentException("V0 is unpinned: locality must be OS");
        }
        if (variant == Variant.V3 && locality != Locality.AUTO) {
            throw new IllegalArgumentException("V3 uses the optimizer: locality must be AUTO");
        }
        if ((variant == Variant.V1 || variant == Variant.V2) && (locality == Locality.OS || locality == Locality.AUTO)) {
            throw new IllegalArgumentException(variant + " needs an explicit pinned locality");
        }
        if (offheap && variant != Variant.V2) {
            throw new IllegalArgumentException("off-heap payload is a V2 feature");
        }
        if (offheap && (stages != 1 || eventBytes == 0)) {
            throw new IllegalArgumentException("off-heap payload needs stages=1 and bytes>0");
        }
        if (metric == Metric.LATENCY && targetRate < 1) {
            throw new IllegalArgumentException("latency runs need rate >= 1");
        }
        if (warmupSeconds < 0 || measureSeconds < 1) {
            throw new IllegalArgumentException("measure must be >= 1 s");
        }
    }

    /** Everything except variant/locality/ringHome/offheap: configs sharing this key are comparable. */
    public String workloadKey() {
        return "p" + partitions + "x" + producersPerPartition + "x" + consumersPerPartition + "-s" + stages + "-" + mode
            + "-r" + ringSize + "-b" + eventBytes + "-" + wait + "-w" + workIterations
            + "-" + (metric == Metric.THROUGHPUT ? "tp" : "lat" + targetRate)
            + "-wu" + warmupSeconds + "-m" + measureSeconds + "-i" + instrumentsPerPartition;
    }

    /** Unique, human-readable, filesystem-safe identifier of this configuration. */
    public String id() {
        return variant + "-" + locality + (locality == Locality.OS || locality == Locality.AUTO || locality == Locality.DENSE
            || locality == Locality.ROUND_ROBIN ? "" : "-home" + ringHome) + (offheap ? "-offheap" : "") + "--" + workloadKey();
    }

    public Map<String, Object> toMap() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("variant", variant.name());
        m.put("locality", locality.name());
        m.put("ringHome", ringHome.name());
        m.put("partitions", partitions);
        m.put("producers", producersPerPartition);
        m.put("consumers", consumersPerPartition);
        m.put("stages", stages);
        m.put("mode", mode.name());
        m.put("ring", ringSize);
        m.put("bytes", eventBytes);
        m.put("wait", wait.name());
        m.put("work", workIterations);
        m.put("metric", metric.name());
        m.put("rate", targetRate);
        m.put("warmup", warmupSeconds);
        m.put("measure", measureSeconds);
        m.put("offheap", offheap);
        m.put("instruments", instrumentsPerPartition);
        return m;
    }

    /** Same map as {@link #toMap()} with string values, for command lines. */
    public Map<String, String> toStringMap() {
        final Map<String, String> m = new LinkedHashMap<>();
        toMap().forEach((k, v) -> m.put(k, String.valueOf(v)));
        return m;
    }

    /** Upper bound on wall-clock seconds one trial of this config takes, excluding JVM start. */
    public int expectedSeconds() {
        return warmupSeconds + measureSeconds + 8; // build + drain + metadata
    }

    @Override
    public String toString() {
        return id();
    }
}
