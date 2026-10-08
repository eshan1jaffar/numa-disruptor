package com.numadisruptor.benchmarks;

import com.lmax.disruptor.EventHandler;
import com.numadisruptor.affinity.CpuAffinity;
import com.numadisruptor.memory.PayloadSlab;
import com.numadisruptor.runtime.ConsumptionMode;
import com.numadisruptor.runtime.ProducerContext;
import com.numadisruptor.runtime.ProducerTask;
import com.numadisruptor.runtime.StageFunction;
import java.util.ArrayList;
import java.util.List;
import org.HdrHistogram.Histogram;

/**
 * The simplified trading pipeline:
 * <pre>
 *   market-data producer --> market-data ring --> strategy stage --> order ring --> order gateway
 * </pre>
 * Ticks are deterministic functions of (producer, sequence); the strategy keeps a per-instrument moving average and emits a
 * signed order. Everything that affects the result is a pure function shared with {@link #referenceOrderChecksum}, so
 * each variant can be checked against an independent single-threaded computation.
 */
final class TradingWorkload {
    private TradingWorkload() { }

    // ---- deterministic data and logic (shared with the reference implementation) ----

    static long priceOf(final int producerId, final long seq) {
        return 10_000 + (((seq * 2_654_435_761L) + producerId * 40_503L) >>> 7) % 997;
    }

    static int instrumentOf(final long seq, final int instrumentsPerPartition) {
        return (int) (seq % instrumentsPerPartition);
    }

    static int quantityOf(final long seq) {
        return 1 + (int) (seq & 31);
    }

    static long payloadSeed(final int producerId, final long seq) {
        return seq * 31 + producerId;
    }

    static long expectedPayloadChecksum(final long seed, final int words) {
        long c = 0;
        for (int i = 0; i < words; i++) {
            c ^= seed + i;
        }
        return c;
    }

    /** CPU work proportional to {@code iterations}, folded into a value the caller must use so the JIT cannot drop it. */
    static long busyWork(final long seed, final int iterations) {
        long x = seed | 1;
        for (int i = 0; i < iterations; i++) {
            x = x * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L;
            x ^= x >>> 29;
        }
        return x;
    }

    /**
     * One strategy step. {@code ema} is the 8x-scaled moving average of the instrument; returns {signal, orderValue} packed via
     * the out array to avoid allocation.
     */
    static long strategy(final long[] ema, final int instrument, final long price, final int qty, final int work, final int[] signalOut) {
        long e = ema[instrument];
        if (e == 0) {
            e = price << 3;
        }
        e = e - (e >> 3) + price;
        ema[instrument] = e;
        final int signal = price > (e >> 3) ? 1 : -1;
        signalOut[0] = signal;
        long order = (long) signal * qty * price;
        if (work > 0 && busyWork(price + qty, work) == Long.MIN_VALUE + 17) {
            order++; // data-dependent, practically never true, but the JIT cannot prove it
        }
        return order;
    }

    /** Independent single-threaded recomputation of the sum of order values for one partition's first {@code events} ticks. */
    static long referenceOrderChecksum(final int producerId, final long events, final int instrumentsPerPartition, final int work) {
        final long[] ema = new long[instrumentsPerPartition];
        final int[] sig = new int[1];
        long sum = 0;
        for (long s = 0; s < events; s++) {
            sum += strategy(ema, instrumentOf(s, instrumentsPerPartition), priceOf(producerId, s), quantityOf(s), work, sig);
        }
        return sum;
    }

    // ---- pipeline components ----

    /** Market-data producer: closed loop (throughput) or open loop paced to an intended schedule (latency). */
    static ProducerTask<TradingEvent> producer(
        final ExperimentConfig c, final RunControl ctl, final PayloadSlab[] slabs, final CpuAffinity affinity,
        final MigrationProbe[] probes, final long[] publishedOut) {
        final int totalProducers = c.partitions * c.producersPerPartition;
        final double intervalNs = c.metric == ExperimentConfig.Metric.LATENCY ? 1e9 * totalProducers / (double) c.targetRate : 0;
        final int words = c.offheap ? 0 : c.eventBytes / 8;
        return ctx -> {
            final MigrationProbe probe = probes[ctx.producerId()];
            final PayloadSlab slab = slabs == null ? null : slabs[ctx.partition()];
            final int pid = ctx.producerId();
            long seq = 0;
            while (!ctl.stop && seq < ctl.limitPerProducer) {
                long intended = 0;
                if (intervalNs > 0) {
                    intended = ctl.originNanos + (long) (seq * intervalNs);
                    while (System.nanoTime() < intended) {
                        if (ctl.stop) {
                            publishedOut[pid] = seq;
                            return;
                        }
                        Thread.onSpinWait();
                    }
                }
                final long slot = ctx.claimBlocking();
                if (slot < 0) {
                    break;
                }
                final TradingEvent e = ctx.event(slot);
                e.producerSequence = seq;
                e.producerId = pid;
                e.partitionId = ctx.partition();
                e.instrument = instrumentOf(seq, c.instrumentsPerPartition);
                e.price = priceOf(pid, seq);
                e.quantity = quantityOf(seq);
                e.intendedNanos = intended;
                final long seed = payloadSeed(pid, seq);
                if (slab != null) {
                    slab.write(slot, seed);
                    e.payloadSlot = slot;
                } else {
                    final long[] p = e.payload;
                    for (int i = 0; i < words; i++) {
                        p[i] = seed + i;
                    }
                }
                ctx.publish(slot);
                seq++;
                if ((seq & 0x3FFF) == 0) {
                    probe.sample(affinity);
                }
            }
            publishedOut[pid] = seq;
        };
    }

    /** Strategy stage: reads the tick (including its payload), updates per-instrument state, writes the order to the next ring. */
    static final class Strategy implements StageFunction<TradingEvent> {
        private final long[][] ema;
        private final long[] payloadErrors; // padded: index = partition * 16
        private final MigrationProbe[] probes;
        private final PayloadSlab[] slabs;
        private final CpuAffinity affinity;
        private final int work;
        private final int words;
        private final long[] counters;
        private final int[][] signal;
        private final int probeBase;

        Strategy(
            final ExperimentConfig c, final PayloadSlab[] slabs, final CpuAffinity affinity, final MigrationProbe[] probes,
            final int probeBase) {
            this.probeBase = probeBase;
            signal = new int[c.partitions][2];
            ema = new long[c.partitions][];
            for (int p = 0; p < c.partitions; p++) {
                ema[p] = new long[c.instrumentsPerPartition + 16];
            }
            payloadErrors = new long[c.partitions * 16];
            counters = new long[c.partitions * 16];
            this.probes = probes;
            this.slabs = slabs;
            this.affinity = affinity;
            work = c.workIterations;
            words = c.eventBytes / 8;
        }

        @Override
        public void apply(final int partition, final TradingEvent in, final TradingEvent out) {
            out.copyTickFrom(in);
            final long seed = payloadSeed(in.producerId, in.producerSequence);
            final long sum = slabs != null ? slabs[partition].checksum(in.payloadSlot) : checksum(in.payload);
            if (sum != expectedPayloadChecksum(seed, words)) {
                payloadErrors[partition * 16]++;
            }
            final int[] sig = signal[partition];
            out.orderValue = strategy(ema[partition], in.instrument, in.price, in.quantity, work, sig);
            out.signal = sig[0];
            if ((++counters[partition * 16] & 0x3FFF) == 0) {
                probes[probeBase + partition].sample(affinity);
            }
        }

        long payloadErrors() {
            long s = 0;
            for (int i = 0; i < payloadErrors.length; i += 16) {
                s += payloadErrors[i];
            }
            return s;
        }
    }

    static long checksum(final long[] payload) {
        long c = 0;
        for (final long w : payload) {
            c ^= w;
        }
        return c;
    }

    /** Order gateway: terminal consumer. Counts, validates, optionally records end-to-end latency. */
    static final class Gateway implements EventHandler<TradingEvent> {
        final int partition;
        final int index;
        final boolean countsUniqueEvents;
        final boolean checkContiguous;
        final int words;
        final Histogram histogram;
        final boolean latency;
        final RunControl ctl;
        final MigrationProbe probe;
        final CpuAffinity affinity;
        final long[] expectedNext;
        final int producersPerPartition;
        final boolean staged;

        long total;
        long measured;
        long orderChecksum;
        long lost;
        long outOfOrder;
        long payloadErrors;
        long payloadXor;
        private boolean recording;

        Gateway(
            final ExperimentConfig c, final int partition, final int index, final RunControl ctl, final CpuAffinity affinity,
            final MigrationProbe probe) {
            this.partition = partition;
            this.index = index;
            this.ctl = ctl;
            this.affinity = affinity;
            this.probe = probe;
            this.latency = c.metric == ExperimentConfig.Metric.LATENCY;
            this.histogram = latency ? new Histogram(3_600_000_000_000L, 3) : null;
            this.countsUniqueEvents = c.mode == ConsumptionMode.WORK_DISTRIBUTION || index == 0;
            this.checkContiguous = c.mode == ConsumptionMode.MULTICAST;
            this.words = c.offheap ? 0 : c.eventBytes / 8;
            this.producersPerPartition = c.producersPerPartition;
            this.expectedNext = new long[c.partitions * c.producersPerPartition];
            this.staged = c.stages == 1;
        }

        @Override
        public void onBatchStart(final long batchSize, final long queueDepth) {
            recording = ctl.phase == RunControl.MEASURE;
        }

        @Override
        public void onEvent(final TradingEvent e, final long sequence, final boolean endOfBatch) {
            total++;
            orderChecksum += staged ? e.orderValue : 0;
            if (words > 0) {
                final long sum = checksum(e.payload);
                payloadXor ^= sum;
                if (sum != expectedPayloadChecksum(payloadSeed(e.producerId, e.producerSequence), words)) {
                    payloadErrors++;
                }
            }
            final int pi = e.producerId;
            if (pi >= 0 && pi < expectedNext.length) {
                final long expect = expectedNext[pi];
                if (e.producerSequence == expect || !checkContiguous && e.producerSequence > expect) {
                    expectedNext[pi] = e.producerSequence + 1;
                } else if (e.producerSequence > expect) {
                    lost += e.producerSequence - expect;
                    expectedNext[pi] = e.producerSequence + 1;
                } else {
                    outOfOrder++;
                }
            } else {
                outOfOrder++;
            }
            if (recording && countsUniqueEvents) {
                measured++;
                if (latency && e.intendedNanos != 0) {
                    final long d = System.nanoTime() - e.intendedNanos;
                    histogram.recordValue(d < 0 ? 0 : d);
                }
            }
            if ((total & 0x3FFF) == 0) {
                probe.sample(affinity);
            }
        }
    }

    static List<Gateway> newGatewayList() {
        return new ArrayList<>();
    }
}
