package com.numadisruptor.optimizer;

/**
 * Estimated load of one edge. These are <b>estimates</b> supplied by the workload description, not hardware
 * measurements.
 *
 * @param eventsPerSecond   events that traverse the edge per second
 * @param payloadBytes      bytes of event data touched per event (ignored for {@link EdgeKind#OWNERSHIP_TRANSFER})
 * @param eventsPerHandoff  average events per synchronisation handoff (batch size); 1 means every event causes a
 *                          cursor/sequence line transfer, larger values amortise it
 * @param linesPerHandoff   cache lines moved per handoff (cursor + sequence is usually 1-2)
 */
public record TrafficProfile(double eventsPerSecond, int payloadBytes, double eventsPerHandoff, double linesPerHandoff) {
    public static final int CACHE_LINE_BYTES = 64;

    public TrafficProfile {
        if (eventsPerSecond < 0 || payloadBytes < 0 || eventsPerHandoff < 1 || linesPerHandoff < 0) {
            throw new IllegalArgumentException("invalid traffic profile: " + eventsPerSecond + "," + payloadBytes
                + "," + eventsPerHandoff + "," + linesPerHandoff);
        }
    }

    /** Payload traffic: handoff parameters are irrelevant. */
    public static TrafficProfile payload(final double eventsPerSecond, final int payloadBytes) {
        return new TrafficProfile(eventsPerSecond, payloadBytes, 1, 0);
    }

    /** Sequencing traffic: one handoff of {@code linesPerHandoff} lines every {@code eventsPerHandoff} events. */
    public static TrafficProfile sequencing(final double eventsPerSecond, final double eventsPerHandoff, final double linesPerHandoff) {
        return new TrafficProfile(eventsPerSecond, 0, eventsPerHandoff, linesPerHandoff);
    }

    /** Cache lines of payload moved per second. */
    public double payloadLinesPerSecond() {
        return eventsPerSecond * Math.ceil(payloadBytes / (double) CACHE_LINE_BYTES);
    }

    /** Sequencing handoffs per second. */
    public double handoffsPerSecond() {
        return eventsPerSecond / eventsPerHandoff;
    }

    /** Cache lines moved per second by sequencing handoffs. */
    public double coherenceLinesPerSecond() {
        return handoffsPerSecond() * linesPerHandoff;
    }
}
