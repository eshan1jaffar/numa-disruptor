package com.numadisruptor.optimizer;

/**
 * Directed communication from an actor to a target.
 *
 * @param from    actor vertex id
 * @param to      target vertex id (whose memory/cache line is touched)
 * @param kind    traffic class
 * @param traffic estimated rates
 */
public record CommunicationEdge(String from, String to, EdgeKind kind, TrafficProfile traffic) {
    public CommunicationEdge {
        if (from.equals(to)) {
            throw new IllegalArgumentException("self edge on " + from);
        }
    }
}
