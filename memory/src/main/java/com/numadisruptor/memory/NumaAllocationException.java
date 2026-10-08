package com.numadisruptor.memory;

/** A NUMA allocation request was refused (bad argument for this machine, or the OS could not provide the memory). */
public class NumaAllocationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NumaAllocationException(final String message) {
        super(message);
    }
}
