package com.numadisruptor.runtime;

/** Workers could not be placed/pinned as planned. The processor does not start in a half-pinned state. */
public class PlacementException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PlacementException(final String message) {
        super(message);
    }

    public PlacementException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
