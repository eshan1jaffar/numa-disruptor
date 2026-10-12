package com.numadisruptor.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.numadisruptor.affinity.SimulatedCpuAffinity;
import com.numadisruptor.optimizer.InfeasiblePlacementException;
import com.numadisruptor.optimizer.PlacementPlan;
import com.numadisruptor.runtime.event.OrderingVerifier;
import com.numadisruptor.runtime.event.SequencedEvent;
import com.numadisruptor.topology.CpuList;
import com.numadisruptor.topology.NumaTopology;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(120)
class RuntimeCorrectnessTest {

    private static void assertCleanMulticast(final PipelineHarness h, final OrderingVerifier.Report r) {
        assertTrue(r.clean(), "violations: " + r);
        assertEquals((long) h.partitions * h.consumers * h.eventsPerPartition(), r.processed(), "every consumer sees every event");
        assertTrue(h.processor.failures().isEmpty());
    }

    private static void assertCleanWorkDistribution(final PipelineHarness h, final OrderingVerifier.Report r) {
        assertTrue(r.clean(), "violations: " + r);
        assertEquals((long) h.partitions * h.eventsPerPartition(), r.processed(), "each event processed exactly once");
        assertEquals(0, h.tracker.missing(), "lost events");
        assertEquals(0, h.tracker.duplicates(), "duplicate processing");
    }

    @Test
    void baselineSingleProducerSingleConsumer() throws Exception {
        final PipelineHarness h = new PipelineHarness();
        assertCleanMulticast(h, h.run());
    }

    @Test
    void ringWrapsManyTimesWithoutOverwritingUnconsumedEvents() throws Exception {
        final PipelineHarness h = new PipelineHarness();
        h.ringSize = 8;
        h.eventsPerProducer = 100_000; // 12,500 laps of the ring
        assertCleanMulticast(h, h.run());
    }

    @Test
    void multipleProducersKeepPerProducerOrder() throws Exception {
        final PipelineHarness h = new PipelineHarness();
        h.producers = 4;
        h.consumers = 2;
        h.ringSize = 64;
        assertCleanMulticast(h, h.run());
    }

    @Test
    void multicastDeliversEveryEventToEveryConsumerAcrossStages() throws Exception {
        final PipelineHarness h = new PipelineHarness();
        h.partitions = 2;
        h.producers = 2;
        h.forwardingStages = 2;
        h.consumers = 3;
        h.ringSize = 32;
        assertCleanMulticast(h, h.run());
    }

    @Test
    void workDistributionProcessesEachEventExactlyOnce() throws Exception {
        final PipelineHarness h = new PipelineHarness();
        h.partitions = 2;
        h.producers = 3;
        h.consumers = 4;
        h.mode = ConsumptionMode.WORK_DISTRIBUTION;
        h.ringSize = 128;
        assertCleanWorkDistribution(h, h.run());
    }

    @Test
    void workDistributionAfterAForwardingStage() throws Exception {
        final PipelineHarness h = new PipelineHarness();
        h.forwardingStages = 1;
        h.consumers = 3;
        h.mode = ConsumptionMode.WORK_DISTRIBUTION;
        assertCleanWorkDistribution(h, h.run());
    }

    @Test
    void slowConsumerCausesBackpressureNotLossOrOverwrite() throws Exception {
        final PipelineHarness h = new PipelineHarness();
        h.ringSize = 16;
        h.eventsPerProducer = 3_000;
        h.perEventWork = () -> {
            final long end = System.nanoTime() + 20_000;
            while (System.nanoTime() < end) {
                Thread.onSpinWait();
            }
        };
        assertCleanMulticast(h, h.run());
        assertTrue(h.processor.stats().producerStalls() > 0, "a 20us consumer behind a 16-slot ring must stall the producer");
    }

    @Test
    void externalPublishersAreRoutedToPartitionsAndStayOrdered() throws Exception {
        final int partitions = 4;
        final int threads = 3;
        final long perPartitionPerThread = 5_000;
        final List<OrderingVerifier> verifiers = Collections.synchronizedList(new ArrayList<>());
        final NumaEventProcessor<SequencedEvent> p = NumaEventProcessor.<SequencedEvent>builder()
            .eventFactory(SequencedEvent.factory(1))
            .partitions(partitions)
            .producersPerPartition(0)
            .externalPublishers(true)
            .router(EventRouter.modulo())
            .ringBufferSize(64)
            .placementPolicy(PlacementPolicy.NONE)
            .consumers(1, ConsumptionMode.MULTICAST, (partition, i) -> {
                final OrderingVerifier v = new OrderingVerifier(
                    partition, threads * partitions, OrderingVerifier.Mode.CONTIGUOUS, null, true);
                verifiers.add(v);
                return v;
            })
            .build();
        p.start();
        final Thread[] pubs = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int thread = t;
            pubs[t] = new Thread(() -> {
                final long[] next = new long[partitions];
                for (long i = 0; i < perPartitionPerThread * partitions; i++) {
                    final int part = (int) (i % partitions);
                    final int producerId = thread * partitions + part;
                    p.publish(part, (event, sequence, seq) -> event.init(part, producerId, seq, 0), next[part]++);
                }
            });
            pubs[t].start();
        }
        for (final Thread t : pubs) {
            t.join();
        }
        p.stop(Duration.ofSeconds(30));
        OrderingVerifier.Report sum = OrderingVerifier.Report.empty();
        for (final OrderingVerifier v : verifiers) {
            sum = sum.plus(v.report());
        }
        assertTrue(sum.clean(), sum.toString());
        assertEquals((long) threads * partitions * perPartitionPerThread, sum.processed());
    }

    @Test
    void everyAcceptedExternalPublishIsConsumedEvenWhenStopRaces() throws Exception {
        for (int round = 0; round < 20; round++) {
            final java.util.concurrent.atomic.LongAdder consumed = new java.util.concurrent.atomic.LongAdder();
            final NumaEventProcessor<SequencedEvent> p = NumaEventProcessor.<SequencedEvent>builder()
                .eventFactory(SequencedEvent.factory(1))
                .producersPerPartition(0)
                .externalPublishers(true)
                .ringBufferSize(64)
                .placementPolicy(PlacementPolicy.NONE)
                .consumers(1, ConsumptionMode.MULTICAST, (partition, i) -> (event, sequence, endOfBatch) -> consumed.increment())
                .build();
            p.start();
            final int threads = 4;
            final java.util.concurrent.atomic.LongAdder accepted = new java.util.concurrent.atomic.LongAdder();
            final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            final Thread[] pubs = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                pubs[t] = new Thread(() -> {
                    try {
                        go.await();
                        while (true) {
                            if (p.tryPublish(0, (event, sequence, arg) -> event.init(0, 0, arg, 0), 1L)) {
                                accepted.increment();
                            }
                        }
                    } catch (final IllegalStateException stopped) {
                        // expected once stop() flips the state
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                pubs[t].start();
            }
            go.countDown();
            Thread.sleep(2 + round % 5);
            p.stop(Duration.ofSeconds(30));
            for (final Thread t : pubs) {
                t.join(10_000);
            }
            assertEquals(accepted.sum(), consumed.sum(), "round " + round + ": accepted publishes must all be consumed");
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> p.tryPublish(0, (event, sequence, arg) -> event.init(0, 0, arg, 0), 1L));
        }
    }

    @Test
    void pinnedWorkersRunOnTheCpusInThePlan() throws Exception {
        final NumaTopology topology = NumaTopology.epyc7513DualSocket();
        final SimulatedCpuAffinity affinity = new SimulatedCpuAffinity(topology.allowedCpus());
        final ConcurrentHashMap<String, BitSet> consumerMasks = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, BitSet> producerMasks = new ConcurrentHashMap<>();
        final NumaEventProcessor<SequencedEvent> p = NumaEventProcessor.<SequencedEvent>builder()
            .eventFactory(SequencedEvent.factory(1))
            .topology(topology)
            .affinity(affinity)
            .partitions(4)
            .producersPerPartition(1)
            .forwardingStages(1, (partition, in, out) -> out.copyFrom(in))
            .ringBufferSize(256)
            .placementPolicy(PlacementPolicy.MIN_REMOTE_TRAFFIC)
            .consumers(2, ConsumptionMode.MULTICAST, (partition, c) -> new com.lmax.disruptor.EventHandler<>() {
                @Override
                public void onStart() {
                    consumerMasks.put(VertexIds.consumer(partition, c), affinity.currentThreadAffinity());
                }

                @Override
                public void onEvent(final SequencedEvent event, final long sequence, final boolean endOfBatch) { }
            })
            .build();
        p.start(ctx -> producerMasks.put(VertexIds.producer(ctx.partition(), ctx.indexInPartition()), affinity.currentThreadAffinity()));
        assertTrue(p.awaitProducers(Duration.ofSeconds(10)));
        p.stop(Duration.ofSeconds(10));

        final PlacementPlan plan = p.placementPlan().orElseThrow();
        assertEquals(8, consumerMasks.size());
        assertEquals(4, producerMasks.size());
        consumerMasks.forEach((id, mask) -> assertEquals(CpuList.parse(Integer.toString(plan.cpuOf(id))), mask, id));
        producerMasks.forEach((id, mask) -> assertEquals(CpuList.parse(Integer.toString(plan.cpuOf(id))), mask, id));
        assertTrue(plan.validate(
            com.numadisruptor.runtime.WorkloadGraphBuilder.build(p.spec()), topology,
            com.numadisruptor.optimizer.PlacementConstraints.defaults()).isEmpty());
        // 4 partitions x 4 workers each fit one node apiece, so the optimizer should find zero remote traffic
        assertEquals(0.0, plan.modelCost(), 1e-6);
        assertTrue(p.optimizerNanos() > 0);
    }

    @Test
    void unpinnablePlanFailsStartCleanly() {
        final NumaTopology topology = NumaTopology.epyc7513DualSocket().restrictedTo(CpuList.parse("0-7"));
        // the "kernel" only lets threads use cpu 0, but the plan will want a second cpu for the second worker
        final SimulatedCpuAffinity affinity = new SimulatedCpuAffinity(CpuList.parse("0"));
        final PipelineHarness h = new PipelineHarness();
        h.topology = topology;
        h.affinity = affinity;
        h.policy = PlacementPolicy.GREEDY;
        h.build();
        assertThrows(PlacementException.class, () -> h.processor.start(h.task()));
        assertThrows(IllegalStateException.class, () -> h.processor.start(h.task()), "a failed start leaves a stopped processor");
    }

    @Test
    void infeasibleWorkloadIsRejectedAtStart() {
        final NumaTopology tiny = NumaTopology.epyc7513DualSocket().restrictedTo(CpuList.parse("0-1")); // one core
        final PipelineHarness h = new PipelineHarness();
        h.topology = tiny;
        h.affinity = new SimulatedCpuAffinity(tiny.allowedCpus());
        h.policy = PlacementPolicy.MIN_REMOTE_TRAFFIC;
        h.producers = 2;
        h.consumers = 3; // 5 workers, 2 usable cpus
        h.build();
        assertThrows(InfeasiblePlacementException.class, () -> h.processor.start(h.task()));
    }

    @Test
    void policiesNeedingPinningFailLoudlyWhereAffinityIsUnsupported() {
        final PipelineHarness h = new PipelineHarness();
        h.policy = PlacementPolicy.GREEDY;
        h.affinity = new com.numadisruptor.affinity.CpuAffinity() {
            @Override public boolean isSupported() { return false; }
            @Override public BitSet currentThreadAffinity() { return new BitSet(); }
            @Override public void pinCurrentThread(final BitSet cpus) { throw new UnsupportedOperationException(); }
            @Override public int currentCpu() { return -1; }
        };
        h.topology = NumaTopology.synthetic(1, 1, 4, 1, 12, 32);
        h.build();
        assertThrows(PlacementException.class, () -> h.processor.start(h.task()));
    }

    @Test
    void stuckConsumerCannotHangShutdownOrProducers() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final PipelineHarness h = new PipelineHarness();
        h.ringSize = 16;
        h.eventsPerProducer = 1_000_000; // producer would run for ever against a stuck consumer
        h.perEventWork = () -> {
            try {
                release.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        h.build();
        h.processor.start(h.task());
        Thread.sleep(200); // let the ring fill and the producer stall
        final long t0 = System.nanoTime();
        final ProcessorFailedException e = assertThrows(ProcessorFailedException.class, () -> h.processor.stop(Duration.ofSeconds(2)));
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        release.countDown();
        assertTrue(ms < 10_000, "stop must return near its timeout, took " + ms + "ms");
        assertTrue(e.getMessage().contains("did not"), e.getMessage());
        assertTrue(h.processor.stats().producerStalls() > 0);
        final var st = h.processor.stats();
        assertTrue(st.producerStallEpisodes() > 0 && st.producerStallEpisodes() < st.producerStalls(),
            "episodes " + st.producerStallEpisodes() + " vs spins " + st.producerStalls());
    }

    @Test
    void handlerExceptionStopsProducersAndIsReported() throws Exception {
        final AtomicInteger seen = new AtomicInteger();
        final PipelineHarness h = new PipelineHarness();
        h.eventsPerProducer = 1_000_000;
        h.ringSize = 64;
        h.perEventWork = () -> {
            if (seen.incrementAndGet() == 100) {
                throw new IllegalStateException("boom");
            }
        };
        h.build();
        h.processor.start(h.task());
        assertTrue(h.processor.awaitProducers(Duration.ofSeconds(30)), "producers must stop once a worker has failed");
        final ProcessorFailedException e = assertThrows(ProcessorFailedException.class, () -> h.processor.stop(Duration.ofSeconds(30)));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertFalse(h.processor.isAcceptingEvents());
        assertEquals(1, h.processor.stats().failedEvents());
    }

    @Test
    void stageFunctionExceptionDoesNotDeadlockDownstream() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final List<OrderingVerifier> verifiers = Collections.synchronizedList(new ArrayList<>());
        final NumaEventProcessor<SequencedEvent> p = NumaEventProcessor.<SequencedEvent>builder()
            .eventFactory(SequencedEvent.factory(1))
            .producersPerPartition(1)
            .ringBufferSize(64)
            .placementPolicy(PlacementPolicy.NONE)
            .forwardingStages(1, (partition, in, out) -> {
                out.copyFrom(in);
                if (calls.incrementAndGet() == 50) {
                    throw new IllegalArgumentException("stage boom");
                }
            })
            .consumers(1, ConsumptionMode.MULTICAST, (partition, i) -> {
                final OrderingVerifier v = new OrderingVerifier(partition, 1, OrderingVerifier.Mode.CONTIGUOUS, null, true);
                verifiers.add(v);
                return v;
            })
            .build();
        final long total = 2_000;
        p.start(ctx -> {
            for (long s = 0; s < total; s++) {
                final long slot = ctx.claimBlocking();
                if (slot < 0) {
                    return;
                }
                ctx.event(slot).init(0, 0, s, 0);
                ctx.publish(slot);
            }
        });
        assertTrue(p.awaitProducers(Duration.ofSeconds(30)));
        assertThrows(ProcessorFailedException.class, () -> p.stop(Duration.ofSeconds(30)));
        // the failing event was still forwarded (as written), so the downstream order is intact up to what was published
        assertTrue(verifiers.get(0).report().processed() > 0);
        assertEquals(0, verifiers.get(0).report().duplicateOrReordered());
    }

    @Test
    void lifecycleMisuseIsRejected() throws Exception {
        final PipelineHarness h = new PipelineHarness();
        h.eventsPerProducer = 10;
        h.build();
        h.processor.start(h.task());
        assertThrows(IllegalStateException.class, () -> h.processor.start(h.task()));
        assertTrue(h.processor.awaitProducers(Duration.ofSeconds(10)));
        h.processor.stop(Duration.ofSeconds(10));
        h.processor.stop(Duration.ofSeconds(1)); // idempotent
        assertThrows(IllegalStateException.class, () -> h.processor.tryPublish(0, (e, s, a) -> { }, 0));
        assertThrows(IllegalArgumentException.class, () -> NumaEventProcessor.<SequencedEvent>builder()
            .eventFactory(SequencedEvent.factory(0)).consumers(1, ConsumptionMode.MULTICAST, (p, i) -> (e, s, b) -> { })
            .forwardingStages(1, null).build());
    }

    @Test
    void verifierDetectsEveryKindOfViolation() {
        final OrderingVerifier v = new OrderingVerifier(0, 2, OrderingVerifier.Mode.CONTIGUOUS, null, true);
        final SequencedEvent e = new SequencedEvent(2);
        e.init(0, 0, 0, 0);
        v.onEvent(e, 0, false);
        e.init(0, 0, 1, 0);
        v.onEvent(e, 1, false);
        e.init(0, 0, 4, 0); // 2 and 3 lost
        v.onEvent(e, 2, false);
        e.init(0, 0, 3, 0); // goes backwards
        v.onEvent(e, 3, false);
        e.init(1, 1, 0, 0); // wrong partition
        v.onEvent(e, 4, false);
        e.init(0, 1, 1, 0);
        e.payload[1] ^= 1; // torn payload
        v.onEvent(e, 5, false);
        final SequencedEvent unset = new SequencedEvent(2); // never initialised: a slot published too early
        v.onEvent(unset, 6, false);

        final OrderingVerifier.Report r = v.report();
        assertEquals(new OrderingVerifier.Report(7, 2, 1, 2, 2, 0), r); // the never-initialised slot has partitionId -1, so it is also "misrouted"
        assertFalse(r.clean());
    }

    @Test
    void randomizedStressAcrossShapesAndModes() throws Exception {
        final int[] ringSizes = {8, 64, 512};
        for (int seed = 0; seed < 12; seed++) {
            final Random rnd = new Random(seed);
            final PipelineHarness h = new PipelineHarness();
            h.partitions = 1 + rnd.nextInt(3);
            h.producers = 1 + rnd.nextInt(3);
            h.forwardingStages = rnd.nextInt(3);
            h.consumers = 1 + rnd.nextInt(3);
            h.mode = rnd.nextBoolean() ? ConsumptionMode.MULTICAST : ConsumptionMode.WORK_DISTRIBUTION;
            h.ringSize = ringSizes[rnd.nextInt(ringSizes.length)];
            h.eventsPerProducer = 2_000 + rnd.nextInt(6_000);
            h.payloadWords = rnd.nextInt(4);
            h.wait = rnd.nextBoolean() ? WaitStrategyKind.YIELDING : WaitStrategyKind.BLOCKING;
            final String shape = "seed " + seed + " partitions=" + h.partitions + " producers=" + h.producers
                + " stages=" + h.forwardingStages + " consumers=" + h.consumers + " " + h.mode + " ring=" + h.ringSize;
            final OrderingVerifier.Report r = h.run();
            if (h.mode == ConsumptionMode.MULTICAST) {
                assertTrue(r.clean(), shape + ": " + r);
                assertEquals((long) h.partitions * h.consumers * h.eventsPerPartition(), r.processed(), shape);
            } else {
                assertTrue(r.clean(), shape + ": " + r);
                assertEquals((long) h.partitions * h.eventsPerPartition(), r.processed(), shape);
                assertEquals(0, h.tracker.missing(), shape);
            }
        }
    }
}
