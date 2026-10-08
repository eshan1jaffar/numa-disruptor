package com.numadisruptor.benchmarks.jmh;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.ProducerType;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Microbenchmark of the sequencing cost the framework relies on: claim + publish of one slot with no consumer gating (so only
 * the sequencer itself is measured). Single-producer rings use plain stores; multi-producer rings pay one atomic
 * fetch-and-add per claim, which grows with the number of contending threads.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
public class SequencerClaimBenchmark {
    static final class Slot {
        long value;
    }

    @Param({"SINGLE", "MULTI"})
    public ProducerType type;

    private RingBuffer<Slot> ring;

    @Setup
    public void setup() {
        ring = RingBuffer.create(type, Slot::new, 1 << 14, new YieldingWaitStrategy());
    }

    @Benchmark
    @Threads(1)
    public long claimPublish1Thread() {
        return claimPublish();
    }

    @Benchmark
    @Threads(4)
    public long claimPublish4Threads() {
        // SINGLE rings are not thread-safe; that configuration is only meaningful for MULTI
        if (type == ProducerType.SINGLE) {
            return 0;
        }
        return claimPublish();
    }

    private long claimPublish() {
        final long seq = ring.next();
        ring.get(seq).value = seq;
        ring.publish(seq);
        return seq;
    }
}
