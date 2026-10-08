package com.numadisruptor.runtime;

/** Naming scheme shared by the graph builder and the runtime so a plan can be looked up by role. */
public final class VertexIds {
    private VertexIds() { }

    public static String producer(final int partition, final int index) {
        return String.format("p%03d/producer%02d", partition, index);
    }

    public static String forwarder(final int partition, final int stage) {
        return String.format("p%03d/forwarder%02d", partition, stage);
    }

    public static String consumer(final int partition, final int index) {
        return String.format("p%03d/consumer%02d", partition, index);
    }

    public static String ring(final int partition, final int ring) {
        return String.format("p%03d/ring%02d", partition, ring);
    }

    public static String cursor(final int partition, final int ring) {
        return String.format("p%03d/cursor%02d", partition, ring);
    }

    public static String gating(final int partition, final int ring) {
        return String.format("p%03d/gating%02d", partition, ring);
    }

    /** Group forcing a ring, its cursor and its gating sequences onto one node. */
    public static String ringGroup(final int partition, final int ring) {
        return String.format("p%03d/ringgroup%02d", partition, ring);
    }
}
