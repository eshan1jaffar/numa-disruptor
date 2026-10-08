package com.numadisruptor.runtime;

/** Thrown by {@code stop()} when a handler, stage or producer failed, or shutdown could not complete in time. */
public class ProcessorFailedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ProcessorFailedException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
