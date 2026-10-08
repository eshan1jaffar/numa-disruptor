package com.numadisruptor.runtime;

/** Which thread constructs (and therefore first-touches) a ring's event array when a placement plan exists. */
public enum RingInit {
    /** A temporary thread pinned to the ring's planned node builds the ring (first-touch placement, best effort). */
    ON_PLANNED_NODE,
    /** The calling thread builds every ring wherever it happens to run (workers are still pinned): placement-unaware memory. */
    CALLER_THREAD
}
