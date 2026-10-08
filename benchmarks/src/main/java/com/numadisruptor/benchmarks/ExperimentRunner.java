package com.numadisruptor.benchmarks;

import com.numadisruptor.affinity.CpuAffinity;
import com.numadisruptor.memory.NumaAllocator;
import com.numadisruptor.memory.NumaMapsReader;
import com.numadisruptor.memory.NumaMemoryRegion;
import com.numadisruptor.memory.NumaMemoryVerifier;
import com.numadisruptor.memory.NumaUnavailableException;
import com.numadisruptor.memory.PayloadSlab;
import com.numadisruptor.optimizer.PlacementConstraints;
import com.numadisruptor.optimizer.PlacementCostModel;
import com.numadisruptor.optimizer.PlacementPlan;
import com.numadisruptor.runtime.NumaEventProcessor;
import com.numadisruptor.runtime.PlacementPolicy;
import com.numadisruptor.runtime.VertexIds;
import com.numadisruptor.runtime.WorkloadGraphBuilder;
import com.numadisruptor.runtime.WorkloadSpec;
import com.numadisruptor.topology.NumaTopology;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.HdrHistogram.Histogram;

/** Runs one trial of one experiment end to end and returns a self-describing result record. */
final class ExperimentRunner {
    private ExperimentRunner() { }

    /** Calibrates the cost of one System.nanoTime() call, which every latency sample includes twice (start and end stamps). */
    static double nanoTimeOverheadNs() {
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 20; round++) {
            final int n = 100_000;
            final long t0 = System.nanoTime();
            long sink = 0;
            for (int i = 0; i < n; i++) {
                sink += System.nanoTime();
            }
            final long per = (System.nanoTime() - t0) / n;
            best = Math.min(best, per);
            if (sink == 42) {
                System.out.print("");
            }
        }
        return best;
    }

    static Map<String, Object> run(
        final ExperimentConfig c, final int trial, final NumaTopology topology, final CpuAffinity affinity,
        final Map<String, Object> metadata) {
        final Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", c.id());
        r.put("trial", trial);
        r.put("workloadKey", c.workloadKey());
        r.put("config", c.toMap());
        r.put("metadata", metadata);
        try {
            final Map<String, Object> body = execute(c, topology, affinity, Long.MAX_VALUE);
            r.putAll(body);
        } catch (final SkipException e) {
            r.put("status", "skipped");
            r.put("reason", e.getMessage());
        } catch (final Throwable t) {
            r.put("status", "failed");
            r.put("reason", t.toString());
        }
        return r;
    }

    private static final class SkipException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SkipException(final String m) {
            super(m, null, false, false);
        }
    }

    private static Map<String, Object> execute(
        final ExperimentConfig c, final NumaTopology topology, final CpuAffinity affinity, final long limitPerProducer) throws Exception {
        final int totalProducers = c.partitions * c.producersPerPartition;
        final WorkloadSpec spec = new WorkloadSpec(c.partitions, c.producersPerPartition, c.stages, c.consumersPerPartition,
            c.mode, c.ringSize, false, Math.max(1, c.metric == ExperimentConfig.Metric.LATENCY ? c.targetRate / c.partitions : 1_000_000),
            c.eventBytes, 8);

        final double heapNeed = (double) c.partitions * (c.stages + 1) * c.ringSize * (120.0 + c.eventBytes);
        if (!c.offheap || c.stages == 0) {
            if (heapNeed > Runtime.getRuntime().maxMemory() * 0.6) {
                throw new SkipException("rings need ~" + (long) (heapNeed / 1e6) + " MB of heap but max heap is "
                    + Runtime.getRuntime().maxMemory() / 1_000_000 + " MB; raise -Xmx");
            }
        }
        if (c.variant != Variant.V0 && !affinity.isSupported()) {
            throw new SkipException("CPU pinning unsupported on this host");
        }

        final PlacementFactory.Resolved placement = PlacementFactory.resolve(c, spec, topology, PlacementConstraints.defaults());
        if (placement.skipReason() != null) {
            throw new SkipException(placement.skipReason());
        }
        final PlacementPlan plan = placement.plan();

        // optional off-heap payload (V2 only)
        final List<NumaMemoryRegion> regions = new ArrayList<>();
        PayloadSlab[] slabs = null;
        final Map<String, Object> offheapReport = new LinkedHashMap<>();
        NumaAllocator numa = null;
        try {
            if (c.offheap) {
                try {
                    numa = NumaAllocator.open();
                } catch (final NumaUnavailableException e) {
                    throw new SkipException("off-heap payload requested but libnuma is unavailable: " + e.getMessage());
                }
                slabs = new PayloadSlab[c.partitions];
                final NumaMemoryVerifier verifier = new NumaMemoryVerifier(numa);
                for (int p = 0; p < c.partitions; p++) {
                    final int node = plan.nodeOf(VertexIds.ring(p, 0));
                    final NumaMemoryRegion region = numa.allocateOnNode((long) c.ringSize * c.eventBytes, node);
                    regions.add(region);
                    region.prefault();
                    final NumaMemoryVerifier.Report rep = verifier.verify(region, 4096);
                    offheapReport.put("partition" + p, Map.of("expectedNode", node, "pagesByNode", rep.pagesByNode(),
                        "unmappedPages", rep.unmappedPages(), "allOnExpectedNode", rep.allOnExpectedNode()));
                    slabs[p] = new PayloadSlab(region, c.eventBytes, c.ringSize);
                }
            }
            return runPipeline(c, spec, topology, affinity, placement, slabs, offheapReport, totalProducers, limitPerProducer);
        } finally {
            for (final NumaMemoryRegion region : regions) {
                region.close();
            }
        }
    }

    private static Map<String, Object> runPipeline(
        final ExperimentConfig c, final WorkloadSpec spec, final NumaTopology topology, final CpuAffinity affinity,
        final PlacementFactory.Resolved placement, final PayloadSlab[] slabs, final Map<String, Object> offheapReport,
        final int totalProducers, final long limitPerProducer) throws Exception {
        final PlacementPlan plan = placement.plan();
        final RunControl ctl = new RunControl(limitPerProducer);
        final int probeCount = totalProducers + c.partitions + c.partitions * c.consumersPerPartition;
        final MigrationProbe[] probes = new MigrationProbe[probeCount];
        for (int i = 0; i < probeCount; i++) {
            probes[i] = new MigrationProbe();
        }
        final TradingWorkload.Strategy strategy = c.stages == 1
            ? new TradingWorkload.Strategy(c, slabs, affinity, probes, totalProducers) : null;
        final List<TradingWorkload.Gateway> gateways = new ArrayList<>();
        final long[] published = new long[totalProducers];
        final int words = c.offheap ? 0 : c.eventBytes / 8;

        final NumaEventProcessor.Builder<TradingEvent> b = NumaEventProcessor.<TradingEvent>builder()
            .eventFactory(TradingEvent.factory(words))
            .topology(topology)
            .affinity(affinity)
            .partitions(c.partitions)
            .producersPerPartition(c.producersPerPartition)
            .ringBufferSize(c.ringSize)
            .waitStrategy(c.wait)
            .trafficEstimate(spec.eventsPerSecondPerPartition(), c.eventBytes, 8)
            .ringInit(placement.ringInit())
            .consumers(c.consumersPerPartition, c.mode, (partition, index) -> {
                final TradingWorkload.Gateway g = new TradingWorkload.Gateway(
                    c, partition, index, ctl, affinity, probes[totalProducers + c.partitions + partition * c.consumersPerPartition + index]);
                synchronized (gateways) {
                    gateways.add(g);
                }
                return g;
            });
        if (c.stages == 1) {
            b.forwardingStages(1, strategy);
        }
        if (plan == null) {
            b.placementPolicy(PlacementPolicy.NONE);
        } else {
            b.placementPlan(plan);
        }
        final NumaEventProcessor<TradingEvent> processor = b.build();

        final SystemSnapshot s0;
        final SystemSnapshot s1;
        final Map<Integer, Long> heapKiBAtStart;
        final long[] stallsAtStart;
        final long[] stallsAtEnd;
        final long windowStartNanos;
        final long windowEndNanos;

        ctl.originNanos = System.nanoTime();
        processor.start(TradingWorkload.producer(c, ctl, slabs, affinity, probes, published));
        try {
            if (limitPerProducer != Long.MAX_VALUE) { // verification run: producers stop themselves
                ctl.phase = RunControl.DONE;
                if (!processor.awaitProducers(Duration.ofSeconds(120))) {
                    throw new IllegalStateException("producers did not finish the fixed-count run");
                }
                s0 = SystemSnapshot.take();
                s1 = s0;
                heapKiBAtStart = Map.of();
                stallsAtStart = stalls(processor);
                stallsAtEnd = stallsAtStart;
                windowStartNanos = System.nanoTime();
                windowEndNanos = windowStartNanos + 1;
            } else {
            sleepSeconds(c.warmupSeconds);
            heapKiBAtStart = NumaMapsReader.residentKiBByNode(java.nio.file.Path.of("/proc/self/numa_maps"), 1024);
            stallsAtStart = stalls(processor);
            s0 = SystemSnapshot.take();
            windowStartNanos = System.nanoTime();
            ctl.phase = RunControl.MEASURE;
            sleepSeconds(c.measureSeconds);
            windowEndNanos = System.nanoTime();
            ctl.phase = RunControl.DONE;
            s1 = SystemSnapshot.take();
            stallsAtEnd = stalls(processor);
            }
        } finally {
            ctl.stop = true;
        }
        if (!processor.awaitProducers(Duration.ofSeconds(30))) {
            throw new IllegalStateException("producers did not stop");
        }
        processor.stop(Duration.ofSeconds(60));

        return collect(c, processor, placement, gateways, strategy, published, probes, s0, s1, windowEndNanos - windowStartNanos,
            heapKiBAtStart, stallsAtEnd, stallsAtStart, offheapReport, totalProducers);
    }

    private static long[] stalls(final NumaEventProcessor<TradingEvent> p) {
        final var ctxs = p.producerContexts();
        final long[] s = new long[ctxs.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = ctxs.get(i).stalls();
        }
        return s;
    }

    private static void sleepSeconds(final int s) throws InterruptedException {
        final long end = System.nanoTime() + s * 1_000_000_000L;
        long left;
        while ((left = end - System.nanoTime()) > 0) {
            Thread.sleep(Math.min(100, Math.max(1, left / 1_000_000)));
        }
    }

    private static Map<String, Object> collect(
        final ExperimentConfig c, final NumaEventProcessor<TradingEvent> processor, final PlacementFactory.Resolved placement,
        final List<TradingWorkload.Gateway> gateways, final TradingWorkload.Strategy strategy, final long[] published,
        final MigrationProbe[] probes, final SystemSnapshot s0, final SystemSnapshot s1, final long windowNanos,
        final Map<Integer, Long> heapKiB, final long[] stallsEnd, final long[] stallsStart,
        final Map<String, Object> offheapReport, final int totalProducers) {
        final Map<String, Object> out = new LinkedHashMap<>();
        final SystemSnapshot.Delta d = SystemSnapshot.between(s0, s1);
        final double windowSec = windowNanos / 1e9;

        long measured = 0;
        long totalUnique = 0;
        long lost = 0;
        long outOfOrder = 0;
        long payloadErrors = strategy == null ? 0 : strategy.payloadErrors();
        long orderChecksum = 0;
        final Histogram merged = c.metric == ExperimentConfig.Metric.LATENCY ? new Histogram(3_600_000_000_000L, 3) : null;
        for (final TradingWorkload.Gateway g : gateways) {
            measured += g.measured;
            if (g.countsUniqueEvents) {
                totalUnique += g.total;
                orderChecksum += g.orderChecksum;
            }
            lost += g.lost;
            outOfOrder += g.outOfOrder;
            payloadErrors += g.payloadErrors;
            if (merged != null && g.histogram != null) {
                merged.add(g.histogram);
            }
        }
        long publishedTotal = 0;
        for (final long p : published) {
            publishedTotal += p;
        }
        final boolean conserved = totalUnique == publishedTotal;
        final boolean correct = conserved && lost == 0 && outOfOrder == 0 && payloadErrors == 0 && processor.failures().isEmpty();
        out.put("status", correct ? "ok" : "failed");
        if (!correct) {
            out.put("reason", "correctness violation: published=" + publishedTotal + " delivered=" + totalUnique + " lost=" + lost
                + " outOfOrder=" + outOfOrder + " payloadErrors=" + payloadErrors + " failures=" + processor.failures());
        }

        final Map<String, Object> tp = new LinkedHashMap<>();
        tp.put("eventsMeasured", measured);
        tp.put("windowSeconds", windowSec);
        tp.put("eventsPerSec", measured / windowSec);
        out.put("throughput", tp);

        if (merged != null) {
            final Map<String, Object> lat = new LinkedHashMap<>();
            lat.put("count", merged.getTotalCount());
            lat.put("p50Ns", merged.getValueAtPercentile(50));
            lat.put("p95Ns", merged.getValueAtPercentile(95));
            lat.put("p99Ns", merged.getValueAtPercentile(99));
            lat.put("p999Ns", merged.getValueAtPercentile(99.9));
            lat.put("maxNs", merged.getMaxValue());
            lat.put("meanNs", merged.getMean());
            lat.put("nanoTimeOverheadNs", nanoTimeOverheadNs());
            lat.put("note", "intended-send-time to gateway-receipt; includes queueing (coordinated-omission corrected) and two nanoTime calls");
            out.put("latency", lat);
        }

        long stalls = 0;
        for (int i = 0; i < stallsEnd.length; i++) {
            stalls += stallsEnd[i] - stallsStart[i];
        }
        final Map<String, Object> bp = new LinkedHashMap<>();
        bp.put("producerStallsInWindow", stalls);
        bp.put("stallsPerMillionEvents", measured == 0 ? 0 : 1e6 * stalls / measured);
        out.put("backpressure", bp);

        long migrations = 0;
        long samples = 0;
        int wanderers = 0;
        for (final MigrationProbe p : probes) {
            migrations += p.changes();
            samples += p.samples();
            if (p.distinctCpus() > 1) {
                wanderers++;
            }
        }
        final Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("processUtilizationPercent", d.cpuUtilizationPercent());
        cpu.put("migrationsObserved", migrations);
        cpu.put("migrationSamples", samples);
        cpu.put("workersSeenOnMoreThanOneCpu", wanderers);
        cpu.put("workers", probes.length);
        cpu.put("note", "software proxy: sched_getcpu sampled every 16384 events; a lower bound, not a kernel counter");
        out.put("cpu", cpu);

        final Map<String, Object> gc = new LinkedHashMap<>();
        gc.put("collectionsInWindow", d.gcCount());
        gc.put("pauseMillisInWindow", d.gcMillis());
        gc.put("allocatedBytesInWindow", d.allocatedBytes());
        gc.put("allocatedBytesPerEvent", measured == 0 || d.allocatedBytes() < 0 ? -1 : (double) d.allocatedBytes() / measured);
        out.put("gc", gc);

        final Map<String, Object> numa = new LinkedHashMap<>();
        numa.put("numastatDeltaInWindow", d.numaStat());
        numa.put("numastatNote", "kernel page-allocation counters, system-wide; a placement proxy, NOT remote-access counts");
        numa.put("jvmMappingsResidentKiBByNode", heapKiB);
        numa.put("offheapResidency", offheapReport);
        out.put("numa", numa);

        final Map<String, Object> pl = new LinkedHashMap<>();
        final PlacementPlan plan = placement.plan();
        pl.put("pinned", plan != null);
        pl.put("ringInit", placement.ringInit().name());
        pl.put("optimizerNanos", placement.optimizerNanos());
        if (plan != null) {
            pl.put("optimizer", plan.optimizerName());
            pl.put("modelCost", plan.modelCost());
            pl.put("modelCostNote", "optimizer estimate in relative units, not measured traffic");
            pl.put("nodes", plan.nodes());
            pl.put("cpus", plan.cpus());
            pl.put("warnings", plan.warnings());
            pl.put("modelBreakdown", PlacementCostModel.fromTopology(processor.topology()).breakdown(placement.graph(), plan.nodes()));
        }
        out.put("placement", pl);

        final Map<String, Object> corr = new LinkedHashMap<>();
        corr.put("published", publishedTotal);
        corr.put("delivered", totalUnique);
        corr.put("lost", lost);
        corr.put("outOfOrder", outOfOrder);
        corr.put("payloadErrors", payloadErrors);
        corr.put("orderChecksum", orderChecksum);
        corr.put("workers", c.partitions * (c.producersPerPartition + c.stages + c.consumersPerPartition));
        out.put("correctness", corr);
        return out;
    }

    /** Fixed-count verification run (no timing): every producer publishes exactly {@code events} ticks, then the pipeline drains. */
    static Map<String, Object> verify(
        final ExperimentConfig c, final long events, final NumaTopology topology, final CpuAffinity affinity) throws Exception {
        return execute(c, topology, affinity, events);
    }
}
