package com.numadisruptor.optimizer;

/** The graph cannot be placed on the available topology without violating a constraint. */
public class InfeasiblePlacementException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InfeasiblePlacementException(final String message) {
        super(message);
    }
}
