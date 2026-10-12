package com.numadisruptor.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.numadisruptor.affinity.SimulatedCpuAffinity;
import com.numadisruptor.optimizer.PlacementConstraints;
import com.numadisruptor.optimizer.PlacementPlan;
import com.numadisruptor.runtime.ConsumptionMode;
import com.numadisruptor.runtime.VertexIds;
import com.numadisruptor.runtime.WorkloadGraphBuilder;
import com.numadisruptor.runtime.WorkloadSpec;
import com.numadisruptor.topology.CpuList;
import com.numadisruptor.topology.NumaTopology;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(120)
class HarnessTest {
    private final NumaTopology epyc = NumaTopology.epyc7513DualSocket();

    private static ExperimentConfig cfg(final String... kv) {
        final Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return ExperimentConfig.of(m);
    }

    private static PlacementFactory.Resolved resolve(final ExperimentConfig c, final NumaTopology t) {
        final WorkloadSpec spec = new WorkloadSpec(c.partitions, c.producersPerPartition, c.stages, c.consumersPerPartition,
            c.mode, c.ringSize, false, 1e6, c.eventBytes, 8);
        return PlacementFactory.resolve(c, spec, t, PlacementConstraints.defaults());
    }

    @Test
    void jsonRoundTripsNestedResults() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "a \"quoted\"\nline\\");
        m.put("n", 42L);
        m.put("d", 1.5);
        m.put("nan", Double.NaN);
        m.put("list", List.of(1L, "x", Map.of("k", true)));
        m.put("nothing", null);
        final Map<String, Object> back = MiniJson.parseObject(MiniJson.write(m));
        assertEquals("a \"quoted\"\nline\\", back.get("s"));
        assertEquals(42L, back.get("n"));
        assertEquals(1.5, back.get("d"));
        assertNull(back.get("nan"), "NaN is written as null");
        assertEquals(List.of(1L, "x", Map.of("k", true)), back.get("list"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("[1,2]"));
    }

    @Test
    void everyMatrixConfigIsValidAndIdsAreUnique() {
        for (final String name : List.of("smoke", "locality", "scale", "sweeps", "all")) {
            final List<ExperimentConfig> list = Matrices.byName(name, 3, 5);
            assertFalse(list.isEmpty(), name);
            final Set<String> ids = new HashSet<>();
            for (final ExperimentConfig c : list) {
                c.validate();
                assertTrue(ids.add(c.id()), "duplicate id " + c.id());
            }
        }
        // the full matrix must be splittable: trials x configs x ~16 s should exceed one hour so --resume is needed
        final long seconds = Matrices.byName("all", 3, 5).stream().mapToLong(ExperimentConfig::expectedSeconds).sum() * 3;
        assertTrue(seconds > 3600, "all x 3 trials is " + seconds + " s");
    }

    @Test
    void invalidConfigurationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> cfg("variant", "V0", "locality", "DENSE"));
        assertThrows(IllegalArgumentException.class, () -> cfg("variant", "V3", "locality", "DENSE"));
        assertThrows(IllegalArgumentException.class, () -> cfg("variant", "V1", "locality", "OS"));
        assertThrows(IllegalArgumentException.class, () -> cfg("ring", "1000"));
        assertThrows(IllegalArgumentException.class, () -> cfg("bytes", "13"));
        assertThrows(IllegalArgumentException.class, () -> cfg("variant", "V1", "locality", "DENSE", "offheap", "true"));
        assertThrows(IllegalArgumentException.class, () -> cfg("bogus", "1"));
    }

    @Test
    void idsSeparateComparableFromIncomparableWorkloads() {
        final ExperimentConfig a = cfg("variant", "V0", "locality", "OS", "partitions", "4");
        final ExperimentConfig b = cfg("variant", "V3", "locality", "AUTO", "partitions", "4");
        final ExperimentConfig c = cfg("variant", "V3", "locality", "AUTO", "partitions", "8");
        assertEquals(a.workloadKey(), b.workloadKey(), "same work, different placement: comparable");
        assertFalse(a.workloadKey().equals(c.workloadKey()), "different partition count is a different workload");
        assertFalse(a.id().equals(b.id()));
    }

    @Test
    void scenarioLocalitiesPlaceWorkersWhereTheyClaim() {
        final PlacementPlan sameNode = resolve(cfg("variant", "V1", "locality", "SAME_NODE"), epyc).plan();
        final Set<Integer> nodes = new HashSet<>(sameNode.nodes().values());
        assertEquals(1, nodes.size());

        final PlacementPlan sameSocket = resolve(cfg("variant", "V1", "locality", "SAME_SOCKET"), epyc).plan();
        final int p = sameSocket.nodeOf(VertexIds.producer(0, 0));
        final int q = sameSocket.nodeOf(VertexIds.consumer(0, 0));
        assertFalse(p == q);
        assertEquals(epyc.node(p).socketId(), epyc.node(q).socketId());

        final PlacementPlan cross = resolve(cfg("variant", "V1", "locality", "CROSS_SOCKET"), epyc).plan();
        assertFalse(epyc.node(cross.nodeOf(VertexIds.producer(0, 0))).socketId() == epyc.node(cross.nodeOf(VertexIds.consumer(0, 0))).socketId());

        final PlacementPlan ringAtConsumer = resolve(cfg("variant", "V1", "locality", "CROSS_SOCKET", "ringHome", "CONSUMER"), epyc).plan();
        assertEquals(ringAtConsumer.nodeOf(VertexIds.consumer(0, 0)), ringAtConsumer.nodeOf(VertexIds.ring(0, 0)));
        assertEquals(cross.nodeOf(VertexIds.producer(0, 0)), cross.nodeOf(VertexIds.ring(0, 0)));

        final PlacementPlan spread = resolve(cfg("variant", "V1", "locality", "MULTI_NODE_SPREAD", "partitions", "8"), epyc).plan();
        final Set<Integer> spreadNodes = new HashSet<>(spread.nodes().values());
        assertEquals(8, spreadNodes.size());
        final Set<Integer> sockets = new HashSet<>();
        spreadNodes.forEach(n -> sockets.add(epyc.node(n).socketId()));
        assertEquals(2, sockets.size());

        // the optimizer should beat the deliberately remote spread scenario on its own cost model
        final PlacementPlan auto = resolve(cfg("variant", "V3", "locality", "AUTO", "partitions", "8"), epyc).plan();
        assertTrue(auto.modelCost() < spread.modelCost());
        assertEquals(0.0, auto.modelCost(), 1e-6);
    }

    @Test
    void scenariosThatTheAllocationCannotHostAreSkippedWithAReason() {
        final NumaTopology oneSocket = epyc.restrictedTo(CpuList.parse("0-31"));
        assertNotNull(resolve(cfg("variant", "V1", "locality", "CROSS_SOCKET"), oneSocket).skipReason());
        assertNotNull(resolve(cfg("variant", "V1", "locality", "MULTI_NODE_LOCAL", "partitions", "4"), oneSocket).skipReason());
        assertNull(resolve(cfg("variant", "V1", "locality", "SAME_SOCKET"), oneSocket).skipReason());

        final NumaTopology oneNode = epyc.restrictedTo(CpuList.parse("0-7"));
        assertNotNull(resolve(cfg("variant", "V1", "locality", "SAME_SOCKET"), oneNode).skipReason());
        // 3 workers x 4 partitions = 12 workers do not fit one 8-cpu node
        assertNotNull(resolve(cfg("variant", "V1", "locality", "SAME_NODE", "partitions", "4"), oneNode).skipReason());
        assertNull(resolve(cfg("variant", "V0", "locality", "OS"), oneNode).skipReason());
    }

    @Test
    void oversubscribedSpinningWorkloadsAreSkippedEvenWhenUnpinned() {
        final NumaTopology small = epyc.restrictedTo(CpuList.parse("0-7"));
        final SimulatedCpuAffinity sim = new SimulatedCpuAffinity(small.allowedCpus());
        final ExperimentConfig spin = cfg("variant", "V0", "locality", "OS", "partitions", "4", "wait", "BUSY_SPIN"); // 12 workers, 8 cpus
        final Map<String, Object> r = ExperimentRunner.run(spin, 1, small, sim, Map.of());
        assertEquals("skipped", r.get("status"));
        assertTrue(String.valueOf(r.get("reason")).contains("oversubscription"), String.valueOf(r.get("reason")));
        // a blocking wait strategy yields the CPU, so oversubscription is legitimate there (reaches the pipeline, not skipped for this reason)
        final Map<String, Object> blocking = ExperimentRunner.run(
            cfg("variant", "V0", "locality", "OS", "partitions", "4", "wait", "BLOCKING", "warmup", "0", "measure", "1", "ring", "1024"),
            1, small, sim, Map.of());
        assertEquals("ok", blocking.get("status"), String.valueOf(blocking.get("reason")));
    }

    @Test
    void resultsStoreAppendsDurablyAndResumeRetriesSkippedOnly(@TempDir final Path dir) throws Exception {
        final ResultsStore store = new ResultsStore(dir);
        final Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("id", "A");
        ok.put("trial", 1);
        ok.put("status", "ok");
        ok.put("throughput", Map.of("eventsPerSec", 123.5));
        final Map<String, Object> skipped = new LinkedHashMap<>();
        skipped.put("id", "B");
        skipped.put("trial", 1);
        skipped.put("status", "skipped");
        skipped.put("reason", "needs two sockets, with a comma");
        store.append(ok);
        store.append(skipped);

        assertEquals(Set.of("A#1"), new ResultsStore(dir).completedKeys());
        final List<String> csv = Files.readAllLines(dir.resolve("results.csv"));
        assertEquals(3, csv.size());
        assertTrue(csv.get(1).startsWith("A,1,ok,"));
        assertTrue(csv.get(1).contains("123.5"));
        assertTrue(csv.get(2).contains("\"needs two sockets, with a comma\""));
        assertEquals(ResultsStore.CSV_COLUMNS.size() - 1, csv.get(1).chars().filter(ch -> ch == ',').count());
    }

    @Test
    void referenceImplementationIsDeterministicAndSensitive() {
        final long a = TradingWorkload.referenceOrderChecksum(0, 1000, 64, 0);
        assertEquals(a, TradingWorkload.referenceOrderChecksum(0, 1000, 64, 0));
        assertFalse(a == TradingWorkload.referenceOrderChecksum(1, 1000, 64, 0), "different producer must differ");
        assertFalse(a == TradingWorkload.referenceOrderChecksum(0, 1001, 64, 0), "one more tick must differ");
    }

    /**
     * All four variants, run with simulated pinning so the test is portable, must deliver exactly the orders an independent
     * single-threaded computation produces: the basis for comparing their speeds.
     */
    @Test
    void everyVariantMatchesTheIndependentReference() throws Exception {
        final long events = 30_000;
        final int partitions = 2;
        long reference = 0;
        for (int p = 0; p < partitions; p++) {
            reference += TradingWorkload.referenceOrderChecksum(p, events, 64, 0);
        }
        final SimulatedCpuAffinity sim = new SimulatedCpuAffinity(epyc.allowedCpus());
        for (final String[] vl : new String[][] {{"V0", "OS"}, {"V1", "DENSE"}, {"V1", "CROSS_SOCKET"}, {"V2", "SAME_NODE"}, {"V3", "AUTO"}}) {
            final ExperimentConfig c = cfg("variant", vl[0], "locality", vl[1], "partitions", Integer.toString(partitions),
                "ring", "512", "bytes", "128", "wait", "YIELDING");
            final Map<String, Object> r = ExperimentRunner.verify(c, events, epyc, sim);
            assertEquals("ok", r.get("status"), vl[0] + "/" + vl[1] + ": " + r.get("reason"));
            @SuppressWarnings("unchecked")
            final Map<String, Object> corr = (Map<String, Object>) r.get("correctness");
            assertEquals(reference, ((Number) corr.get("orderChecksum")).longValue(), vl[0] + "/" + vl[1]);
            assertEquals((long) partitions * events, ((Number) corr.get("delivered")).longValue());
        }
    }

    @Test
    void workDistributionAndMulticastBothConserveEvents() throws Exception {
        final SimulatedCpuAffinity sim = new SimulatedCpuAffinity(epyc.allowedCpus());
        for (final ConsumptionMode mode : ConsumptionMode.values()) {
            final ExperimentConfig c = cfg("variant", "V0", "locality", "OS", "partitions", "2", "consumers", "3", "producers", "2",
                "mode", mode.name(), "ring", "256", "wait", "YIELDING");
            final Map<String, Object> r = ExperimentRunner.verify(c, 20_000, epyc, sim);
            assertEquals("ok", r.get("status"), mode + ": " + r.get("reason"));
        }
    }

    @Test
    void timedTrialProducesMetricsAndCleanCorrectness() {
        final SimulatedCpuAffinity sim = new SimulatedCpuAffinity(epyc.allowedCpus());
        final ExperimentConfig c = cfg("variant", "V3", "locality", "AUTO", "partitions", "2", "ring", "1024", "wait", "YIELDING",
            "metric", "LATENCY", "rate", "20000", "warmup", "1", "measure", "2");
        final Map<String, Object> r = ExperimentRunner.run(c, 1, epyc, sim, Map.of("gitCommit", "test"));
        assertEquals("ok", r.get("status"), String.valueOf(r.get("reason")));
        @SuppressWarnings("unchecked")
        final Map<String, Object> tp = (Map<String, Object>) r.get("throughput");
        final double eps = ((Number) tp.get("eventsPerSec")).doubleValue();
        assertTrue(eps > 15_000 && eps < 25_000, "paced at 20k/s but measured " + eps);
        @SuppressWarnings("unchecked")
        final Map<String, Object> lat = (Map<String, Object>) r.get("latency");
        assertTrue(((Number) lat.get("p50Ns")).longValue() <= ((Number) lat.get("p99Ns")).longValue());
        assertTrue(((Number) lat.get("p99Ns")).longValue() <= ((Number) lat.get("maxNs")).longValue());
        assertTrue(((Number) lat.get("count")).longValue() > 10_000);
    }

    // ---- regression tests for the review findings ----

    @Test
    void driverPinIsRestoredAfterAnInProcessTrial() throws Exception {
        final SimulatedCpuAffinity sim = new SimulatedCpuAffinity(epyc.allowedCpus());
        final java.util.BitSet before = sim.currentThreadAffinity();
        final Map<String, Object> r = ExperimentRunner.verify(
            cfg("variant", "V1", "locality", "DENSE", "ring", "256", "wait", "YIELDING"), 5_000, epyc, sim);
        assertEquals("ok", r.get("status"), String.valueOf(r.get("reason")));
        assertEquals(before, sim.currentThreadAffinity(), "main must not stay pinned: later trials would inherit it");
    }

    @Test
    void paceOriginIsSharedAndLiesAheadOfTheFirstCaller() {
        final RunControl ctl = new RunControl(Long.MAX_VALUE);
        final long t0 = System.nanoTime();
        final long o = ctl.origin();
        assertTrue(o - t0 > 0, "origin leads the first producer so start-up cost cannot put it behind schedule");
        assertEquals(o, ctl.origin());
    }

    @Test
    void latencyIsSelectedByIntendedSendTimeNotByDeliveryPhase() {
        final ExperimentConfig c = cfg("metric", "LATENCY", "bytes", "0");
        final RunControl ctl = new RunControl(Long.MAX_VALUE);
        final long base = System.nanoTime() - 1_000_000;
        ctl.windowStartNanos = base + 1_000;
        ctl.windowEndNanos = base + 2_000;
        final TradingWorkload.Gateway g = new TradingWorkload.Gateway(c, 0, 0, ctl, new SimulatedCpuAffinity(epyc.allowedCpus()), new MigrationProbe());
        long seq = 0;
        for (final long intended : new long[] {500, 1_500, 1_999, 2_000, 3_000}) { // warm-up, in, in, end is exclusive, after
            final TradingEvent e = new TradingEvent(0);
            e.producerId = 0;
            e.producerSequence = seq++;
            e.intendedNanos = base + intended;
            g.onBatchStart(1, 0); // phase is still WARMUP: delivery phase must not matter for latency
            g.onEvent(e, seq, true);
        }
        assertEquals(2, g.histogram.getTotalCount());
        assertEquals(0, g.measured, "throughput counting is by delivery phase and the phase was never MEASURE");
    }

    @Test
    void resumeRetriesFailedIgnoresTornLinesAndAppendsOnAFreshLine(@TempDir final Path dir) throws Exception {
        final ResultsStore store = new ResultsStore(dir);
        for (final String[] r : new String[][] {{"OK", "ok"}, {"BAD", "failed"}, {"SKIP", "skipped"}}) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0]);
            m.put("trial", 1);
            m.put("status", r[1]);
            store.append(m);
        }
        assertEquals(Set.of("OK#1"), store.completedKeys(), "default: failed and skipped are retried");
        assertEquals(Set.of("OK#1", "BAD#1"), store.completedKeys(true));

        // a killed run leaves a torn last line without newline; it must not count and must not swallow the next record
        Files.writeString(dir.resolve("results.jsonl"), "{\"id\":\"TORN\",\"trial\":1,\"stat", java.nio.file.StandardOpenOption.APPEND);
        assertEquals(Set.of("OK#1"), store.completedKeys());
        final Map<String, Object> next = new LinkedHashMap<>();
        next.put("id", "NEXT");
        next.put("trial", 2);
        next.put("status", "ok");
        store.append(next);
        assertEquals(Set.of("OK#1", "NEXT#2"), store.completedKeys());
    }

    @Test
    void warmupIsPartOfTheIdentity() {
        assertFalse(cfg("warmup", "1").id().equals(cfg("warmup", "5").id()));
        assertFalse(cfg("warmup", "1").workloadKey().equals(cfg("warmup", "5").workloadKey()));
    }

    @Test
    void sameNodeCapacityExcludesTheReservedDriverCpu() {
        final java.util.BitSet reserved = new java.util.BitSet();
        reserved.set(0); // driver cpu lives on node 0
        final ExperimentConfig c = cfg("variant", "V1", "locality", "SAME_NODE", "partitions", "4", "stages", "0");
        final WorkloadSpec spec = new WorkloadSpec(c.partitions, c.producersPerPartition, c.stages, c.consumersPerPartition,
            c.mode, c.ringSize, false, 1e6, c.eventBytes, 8);
        final PlacementFactory.Resolved r = PlacementFactory.resolve(c, spec, epyc, new PlacementConstraints(true, reserved)); // 8 workers
        assertNull(r.skipReason(), "another node fits all 8 workers");
        assertFalse(r.plan().cpus().values().stream().anyMatch(cpu -> cpu == 0));
    }

    @Test
    void verifyFailsWhenPinnedVariantsCannotRunUnlessExplicitlyAllowed() throws Exception {
        final SimulatedCpuAffinity sim = new SimulatedCpuAffinity(epyc.allowedCpus());
        final com.numadisruptor.affinity.CpuAffinity unsupported = new com.numadisruptor.affinity.CpuAffinity() {
            @Override public boolean isSupported() { return false; }
            @Override public java.util.BitSet currentThreadAffinity() { return sim.currentThreadAffinity(); }
            @Override public void pinCurrentThread(final java.util.BitSet cpus) { sim.pinCurrentThread(cpus); }
            @Override public int currentCpu() { return sim.currentCpu(); }
        };
        assertFalse(HarnessMain.verify(2_000, false, epyc, unsupported));
        assertTrue(HarnessMain.verify(2_000, true, epyc, unsupported));
    }
}
