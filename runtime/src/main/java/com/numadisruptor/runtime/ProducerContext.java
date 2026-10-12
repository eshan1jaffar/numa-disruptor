package com.numadisruptor.runtime;

import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.InsufficientCapacityException;
import com.lmax.disruptor.RingBuffer;
import java.util.function.BooleanSupplier;

/**
 * Handle a framework-managed producer task uses to publish into its partition's ring 0. One instance belongs to exactly one
 * producer thread; it is <b>not</b> thread-safe.
 *
 * <p>Publishing is two-phase and allocation-free:
 * <pre>
 *   long seq = ctx.claimBlocking();      // -1 if the processor is stopping or failed
 *   if (seq < 0) return;
 *   MyEvent e = ctx.event(seq);          // fill every field
 *   ctx.publish(seq);                    // ALWAYS publish a claimed sequence, or consumers stall behind it
 * </pre>
 * A full ring is reported as backpressure: {@link #claim()} returns -1 and counts a stall; {@link #claimBlocking()} spins
 * (with {@link Thread#onSpinWait()}) but gives up as soon as the processor stops, so a producer can never block forever.
 */
public final class ProducerContext<E> {
    private final int partition;
    private final int producerId;
    private final int indexInPartition;
    private final RingBuffer<E> ring;
    private final BooleanSupplier running;

    /** Single writer (the owning thread); volatile so the controller can read it after the thread ends. */
    private volatile long stalls;
    private volatile long stallEpisodes;
    private boolean inStall; // owning thread only: true while the previous claim attempt failed
    private volatile long published;
    private long publishedLocal;

    ProducerContext(
        final int partition, final int producerId, final int indexInPartition,
        final RingBuffer<E> ring, final BooleanSupplier running) {
        this.partition = partition;
        this.producerId = producerId;
        this.indexInPartition = indexInPartition;
        this.ring = ring;
        this.running = running;
    }

    public int partition() { return partition; }

    /** Globally unique producer id (partition-major). */
    public int producerId() { return producerId; }

    public int indexInPartition() { return indexInPartition; }

    /** False once the processor is stopping or has failed; the task should return promptly. */
    public boolean isRunning() { return running.getAsBoolean(); }

    /** Claim one slot without waiting. Returns the sequence, or -1 when the ring is full (counted as a stall). */
    public long claim() {
        try {
            final long seq = ring.tryNext();
            inStall = false;
            return seq;
        } catch (final InsufficientCapacityException full) {
            stalls = stalls + 1;
            if (!inStall) {
                inStall = true;
                stallEpisodes = stallEpisodes + 1;
            }
            return -1;
        }
    }

    /** Claim one slot, spinning while the ring is full. Returns -1 only if the processor stopped meanwhile. */
    public long claimBlocking() {
        long seq;
        while ((seq = claim()) < 0) {
            if (!running.getAsBoolean()) {
                return -1;
            }
            Thread.onSpinWait();
        }
        return seq;
    }

    /** The preallocated event in a claimed slot. */
    public E event(final long sequence) {
        return ring.get(sequence);
    }

    /** Make a claimed slot visible to consumers. */
    public void publish(final long sequence) {
        ring.publish(sequence);
        publishedLocal++;
        if ((publishedLocal & 0x3FF) == 0) {
            published = publishedLocal;
        }
    }

    /** Convenience publish through a translator (allocation-free if the translator is). Returns false on backpressure. */
    public <A> boolean tryPublish(final EventTranslatorOneArg<E, A> translator, final A arg) {
        final long seq = claim();
        if (seq < 0) {
            return false;
        }
        try {
            translator.translateTo(ring.get(seq), seq, arg);
        } finally {
            publish(seq);
        }
        return true;
    }

    /**
     * Number of failed claim attempts (ring full) so far. Because {@link #claimBlocking()} retries in a spin loop this measures
     * spin time, not how often the producer was blocked; see {@link #stallEpisodes()} for the latter.
     */
    public long stalls() { return stalls; }

    /** Blocked episodes: runs of consecutive failed claims (each ended by a successful claim) counted once, not per spin. */
    public long stallEpisodes() { return stallEpisodes; }

    /** Events published, exact once the task has finished; approximate (within 1024) while running. */
    public long publishedApprox() { return published; }

    void flushCounters() {
        published = publishedLocal;
    }

    long publishedExact() { return publishedLocal; }
}
