package com.numadisruptor.runtime;

import com.numadisruptor.affinity.CpuAffinity;
import com.numadisruptor.optimizer.PlacementPlan;
import com.numadisruptor.runtime.event.ExactlyOnceTracker;
import com.numadisruptor.runtime.event.OrderingVerifier;
import com.numadisruptor.runtime.event.SequencedEvent;
import com.numadisruptor.topology.NumaTopology;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Runs a producer/consumer scenario to completion with full verification; shared by the correctness tests. */
final class PipelineHarness {
    int partitions = 1;
    int producers = 1;
    int forwardingStages;
    int consumers = 1;
    ConsumptionMode mode = ConsumptionMode.MULTICAST;
    int ringSize = 1024;
    long eventsPerProducer = 20_000;
    int payloadWords = 2;
    PlacementPolicy policy = PlacementPolicy.NONE;
    PlacementPlan explicitPlan;
    NumaTopology topology;
    CpuAffinity affinity;
    WaitStrategyKind wait = WaitStrategyKind.YIELDING;
    boolean checkPayload = true;
    /** Optional hook run by every consumer for every event (for slow-consumer tests). */
    Runnable perEventWork = () -> { };

    final List<OrderingVerifier> verifiers = new ArrayList<>();
    ExactlyOnceTracker tracker;
    NumaEventProcessor<SequencedEvent> processor;

    NumaEventProcessor<SequencedEvent> build() {
        final int totalProducers = partitions * producers;
        tracker = new ExactlyOnceTracker(totalProducers, eventsPerProducer);
        final OrderingVerifier.Mode vm = mode == ConsumptionMode.MULTICAST ? OrderingVerifier.Mode.CONTIGUOUS : OrderingVerifier.Mode.MONOTONIC;
        final NumaEventProcessor.Builder<SequencedEvent> b = NumaEventProcessor.<SequencedEvent>builder()
            .eventFactory(SequencedEvent.factory(payloadWords))
            .partitions(partitions)
            .producersPerPartition(producers)
            .ringBufferSize(ringSize)
            .waitStrategy(wait)
            .placementPolicy(policy)
            .consumers(consumers, mode, (partition, index) -> {
                final OrderingVerifier base = new OrderingVerifier(
                    partition, totalProducers, vm, mode == ConsumptionMode.WORK_DISTRIBUTION ? tracker : null, checkPayload);
                synchronized (verifiers) {
                    verifiers.add(base);
                }
                return (event, sequence, endOfBatch) -> {
                    base.onEvent(event, sequence, endOfBatch);
                    perEventWork.run();
                };
            });
        if (forwardingStages > 0) {
            b.forwardingStages(forwardingStages, (partition, in, out) -> out.copyFrom(in));
        }
        if (topology != null) {
            b.topology(topology);
        }
        if (affinity != null) {
            b.affinity(affinity);
        }
        if (explicitPlan != null) {
            b.placementPlan(explicitPlan);
        }
        processor = b.build();
        return processor;
    }

    ProducerTask<SequencedEvent> task() {
        return ctx -> {
            for (long seq = 0; seq < eventsPerProducer; seq++) {
                final long slot = ctx.claimBlocking();
                if (slot < 0) {
                    return;
                }
                try {
                    ctx.event(slot).init(ctx.partition(), ctx.producerId(), seq, System.nanoTime());
                } finally {
                    ctx.publish(slot);
                }
            }
        };
    }

    /** Start, wait for producers, drain, stop. Throws if the processor reports a failure. */
    OrderingVerifier.Report run() throws Exception {
        build();
        processor.start(task());
        if (!processor.awaitProducers(Duration.ofSeconds(60))) {
            processor.stop(Duration.ofSeconds(5));
            throw new AssertionError("producers did not finish within 60s");
        }
        processor.stop(Duration.ofSeconds(60));
        return total();
    }

    OrderingVerifier.Report total() {
        OrderingVerifier.Report sum = OrderingVerifier.Report.empty();
        synchronized (verifiers) {
            for (final OrderingVerifier v : verifiers) {
                sum = sum.plus(v.report());
            }
        }
        return sum;
    }

    long eventsPerPartition() {
        return producers * eventsPerProducer;
    }
}
