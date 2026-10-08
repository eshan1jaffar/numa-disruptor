package com.numadisruptor.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.numadisruptor.affinity.CpuAffinity;
import com.numadisruptor.optimizer.PlacementPlan;
import com.numadisruptor.runtime.event.OrderingVerifier;
import com.numadisruptor.runtime.event.SequencedEvent;
import com.numadisruptor.topology.NumaTopology;
import com.numadisruptor.topology.TopologyDiscovery;
import java.time.Duration;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Opt-in (-PhardwareTests): exercises real sched_setaffinity and real topology on a Linux host. */
@Tag("hardware")
@Timeout(120)
class RealAffinityRuntimeTest {
    @Test
    void workersRunOnPlannedCpusOnThisMachine() throws Exception {
        final CpuAffinity real = CpuAffinity.system();
        assumeTrue(real.isSupported(), "Linux affinity not available");
        final NumaTopology topology = TopologyDiscovery.discover();
        assumeTrue(topology.allowedCpus().cardinality() >= 3, "need at least 3 allowed CPUs");

        final AtomicReference<BitSet> consumerMask = new AtomicReference<>();
        final AtomicInteger wrongCpu = new AtomicInteger();
        final OrderingVerifier verifier = new OrderingVerifier(0, 1, OrderingVerifier.Mode.CONTIGUOUS, null, true);
        final AtomicInteger plannedConsumerCpu = new AtomicInteger(-1); // set once start() has returned the plan
        final AtomicInteger samples = new AtomicInteger();
        final NumaEventProcessor<SequencedEvent> p = NumaEventProcessor.<SequencedEvent>builder()
            .eventFactory(SequencedEvent.factory(2))
            .topology(topology)
            .affinity(real)
            .ringBufferSize(1024)
            .placementPolicy(PlacementPolicy.MIN_REMOTE_TRAFFIC)
            .consumers(1, ConsumptionMode.MULTICAST, (partition, c) -> new com.lmax.disruptor.EventHandler<>() {
                @Override
                public void onStart() {
                    consumerMask.set(real.currentThreadAffinity());
                }

                @Override
                public void onEvent(final SequencedEvent event, final long sequence, final boolean endOfBatch) {
                    verifier.onEvent(event, sequence, endOfBatch);
                    final int planned = plannedConsumerCpu.get();
                    if ((sequence & 0xFF) == 0 && planned >= 0) {
                        samples.incrementAndGet();
                        if (real.currentCpu() != planned) {
                            wrongCpu.incrementAndGet();
                        }
                    }
                }
            })
            .build();
        final long events = 200_000;
        // plan is resolved inside start(); read the consumer's CPU lazily from it
        p.start(ctx -> {
            for (long s = 0; s < events; s++) {
                final long slot = ctx.claimBlocking();
                if (slot < 0) {
                    return;
                }
                ctx.event(slot).init(0, 0, s, 0);
                ctx.publish(slot);
            }
        });
        final PlacementPlan plan = p.placementPlan().orElseThrow();
        plannedConsumerCpu.set(plan.cpuOf(VertexIds.consumer(0, 0)));
        assertTrue(p.awaitProducers(Duration.ofSeconds(60)));
        p.stop(Duration.ofSeconds(60));

        final BitSet expected = new BitSet();
        expected.set(plannedConsumerCpu.get());
        assertEquals(expected, consumerMask.get());
        assertTrue(verifier.report().clean(), verifier.report().toString());
        assertEquals(events, verifier.report().processed());
        assertEquals(0, wrongCpu.get(), "consumer migrated off its planned CPU");
        assertTrue(samples.get() > 0, "no CPU samples were taken, the check proved nothing");
    }
}
