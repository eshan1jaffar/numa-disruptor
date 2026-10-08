package com.numadisruptor.memory;

/** libnuma (or NUMA support in the kernel) is not usable on this host. Native NUMA memory is an optional feature. */
public class NumaUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NumaUnavailableException(final String message) {
        super(message);
    }

    public NumaUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
