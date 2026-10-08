package com.numadisruptor.examples;

import com.numadisruptor.runtime.ConsumptionMode;
import com.numadisruptor.runtime.NumaEventProcessor;
import com.numadisruptor.runtime.PlacementPolicy;
import com.numadisruptor.runtime.WaitStrategyKind;
import com.numadisruptor.runtime.event.OrderingVerifier;
import com.numadisruptor.runtime.event.SequencedEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal end-to-end use of the runtime: 4 independent partitions, each with one producer, one forwarding stage and one
 * consumer, placed by the optimizer. Falls back to unpinned execution where CPU pinning is unavailable.
 *
 * <pre>  ./gradlew :examples:run   (needs JDK 25)</pre>
 */
public final class TradingDemo {
    private TradingDemo() { }

    public static void main(final String[] args) throws Exception {
        final int partitions = 4;
        final long eventsPerProducer = 1_000_000;
        final List<OrderingVerifier> verifiers = Collections.synchronizedList(new ArrayList<>());

        final boolean canPin = com.numadisruptor.affinity.CpuAffinity.system().isSupported();
        final NumaEventProcessor<SequencedEvent> processor = NumaEventProcessor.<SequencedEvent>builder()
            .eventFactory(SequencedEvent.factory(2))
            .partitions(partitions)
            .producersPerPartition(1)
            .forwardingStages(1, (partition, in, out) -> out.copyFrom(in)) // the "strategy" stage
            .consumers(1, ConsumptionMode.MULTICAST, (partition, index) -> {
                final OrderingVerifier gateway = new OrderingVerifier(
                    partition, partitions, OrderingVerifier.Mode.CONTIGUOUS, null, true);
                verifiers.add(gateway);
                return gateway;
            })
            .ringBufferSize(1 << 14)
            .waitStrategy(WaitStrategyKind.YIELDING)
            .placementPolicy(canPin ? PlacementPolicy.MIN_REMOTE_TRAFFIC : PlacementPolicy.NONE)
            .build();

        processor.start(ctx -> {
            for (long seq = 0; seq < eventsPerProducer; seq++) {
                final long slot = ctx.claimBlocking();
                if (slot < 0) {
                    return; // processor is stopping
                }
                ctx.event(slot).init(ctx.partition(), ctx.producerId(), seq, System.nanoTime());
                ctx.publish(slot); // always publish a claimed slot
            }
        });
        System.out.println(processor.describePlacement());
        processor.awaitProducers(Duration.ofSeconds(60));
        processor.stop(Duration.ofSeconds(60));

        OrderingVerifier.Report total = OrderingVerifier.Report.empty();
        for (final OrderingVerifier v : verifiers) {
            total = total.plus(v.report());
        }
        System.out.println("delivered " + total.processed() + " events, clean=" + total.clean()
            + ", producer stalls=" + processor.stats().producerStalls());
    }
}
