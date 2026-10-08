package com.numadisruptor.optimizer;

/**
 * The kind of traffic an edge {@code from -> to} carries. {@code from} is always the actor and {@code to} the
 * component whose memory is touched, so the direction encodes read vs write.
 *
 * <p>The three kinds are deliberately separate because they stress different hardware paths: payload kinds move data
 * lines to or from DRAM that lives on a particular node, whereas {@link #OWNERSHIP_TRANSFER} moves a few hot cache
 * lines between cores' caches (cursor/sequence ping-pong) and is governed by coherence latency, not DRAM bandwidth.
 * A cross-node <i>event</i> is not automatically a remote <i>write</i>.
 */
public enum EdgeKind {
    /** {@code from} writes event payload into memory homed at {@code to} (for example producer into ring). */
    PAYLOAD_WRITE,
    /** {@code from} reads event payload from memory homed at {@code to} (for example consumer from ring). */
    PAYLOAD_READ,
    /** Cache-line ownership/sharing transfer of sequencing state between {@code from} and {@code to}. */
    OWNERSHIP_TRANSFER
}
