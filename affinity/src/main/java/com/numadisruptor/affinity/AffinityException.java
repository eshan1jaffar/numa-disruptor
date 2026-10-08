package com.numadisruptor.affinity;

/** Thrown when a CPU affinity request cannot be satisfied or verified. */
public class AffinityException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AffinityException(final String message) {
        super(message);
    }

    public AffinityException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
