package com.numadisruptor.runtime;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;

/** Consumer wait strategies, from lowest latency / highest CPU burn to the opposite. */
public enum WaitStrategyKind {
    BUSY_SPIN,
    YIELDING,
    SLEEPING,
    BLOCKING;

    public WaitStrategy create() {
        return switch (this) {
            case BUSY_SPIN -> new BusySpinWaitStrategy();
            case YIELDING -> new YieldingWaitStrategy();
            case SLEEPING -> new SleepingWaitStrategy();
            case BLOCKING -> new BlockingWaitStrategy();
        };
    }
}
