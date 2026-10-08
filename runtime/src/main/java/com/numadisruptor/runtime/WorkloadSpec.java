package com.numadisruptor.runtime;

/**
 * Shape of the workload, used to build the communication graph and the rings.
 *
 * <p>Each of the {@code partitions} is an independent stream with its own chain of rings:
 * <pre>
 *   producers --> ring0 --> [forwarder0] --> ring1 --> ... --> ring{F} --> consumers
 * </pre>
 * where F = {@code forwardingStages}. Ordering is preserved <i>per partition</i>; there is no order across partitions.
 *
 * @param partitions                 independent streams (each a separate set of rings)
 * @param producersPerPartition      framework-managed producer workers per partition (0 when only external threads publish)
 * @param forwardingStages           single-threaded 1:1 stages between rings (0 = producers feed the final consumers directly)
 * @param consumersPerPartition      final-stage consumers per partition
 * @param mode                       how the final consumers share the stream
 * @param ringBufferSize             slots per ring (power of two)
 * @param externalPublishers         true if arbitrary application threads will call {@code publish}; ring0 is then multi-producer
 * @param eventsPerSecondPerPartition expected rate, used only for placement estimates
 * @param payloadBytes               expected payload touched per event, used only for placement estimates
 * @param consumerBatch              expected events per consumer batch, used only for placement estimates
 */
public record WorkloadSpec(
    int partitions,
    int producersPerPartition,
    int forwardingStages,
    int consumersPerPartition,
    ConsumptionMode mode,
    int ringBufferSize,
    boolean externalPublishers,
    double eventsPerSecondPerPartition,
    int payloadBytes,
    int consumerBatch) {

    public WorkloadSpec {
        if (partitions < 1) {
            throw new IllegalArgumentException("partitions must be >= 1");
        }
        if (producersPerPartition < 0) {
            throw new IllegalArgumentException("producersPerPartition must be >= 0");
        }
        if (producersPerPartition == 0 && !externalPublishers) {
            throw new IllegalArgumentException("need framework producers or externalPublishers=true");
        }
        if (forwardingStages < 0) {
            throw new IllegalArgumentException("forwardingStages must be >= 0");
        }
        if (consumersPerPartition < 1) {
            throw new IllegalArgumentException("consumersPerPartition must be >= 1");
        }
        if (Integer.bitCount(ringBufferSize) != 1 || ringBufferSize < 2) {
            throw new IllegalArgumentException("ringBufferSize must be a power of two >= 2, was " + ringBufferSize);
        }
        if (eventsPerSecondPerPartition <= 0 || payloadBytes < 0 || consumerBatch < 1) {
            throw new IllegalArgumentException("invalid traffic estimates");
        }
    }

    public int rings() { return forwardingStages + 1; }

    /** Ring 0 has several writers (several producers, or any external thread) and so needs a multi-producer sequencer. */
    public boolean ring0IsMultiProducer() { return producersPerPartition > 1 || externalPublishers; }

    public int totalWorkers() {
        return partitions * (producersPerPartition + forwardingStages + consumersPerPartition);
    }
}
