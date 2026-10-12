package com.numadisruptor.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lmax.disruptor.EventHandler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkDistributionHandlerTest {
    private static final class Recorder implements EventHandler<Object> {
        final List<String> calls = new ArrayList<>();

        @Override
        public void onEvent(final Object e, final long sequence, final boolean endOfBatch) {
            calls.add(sequence + (endOfBatch ? "!" : ""));
        }

        @Override
        public void onBatchStart(final long batchSize, final long queueDepth) {
            calls.add("B" + batchSize);
        }
    }

    private static Recorder feed(final int ordinal, final int count, final long first, final int size) throws Exception {
        final Recorder r = new Recorder();
        final WorkDistributionHandler<Object> h = new WorkDistributionHandler<>(r, ordinal, count);
        h.onBatchStart(size, size);
        for (long s = first; s < first + size; s++) {
            h.onEvent(null, s, s == first + size - 1);
        }
        return r;
    }

    @Test
    void endOfBatchMarksTheWorkersLastOwnEventAndBatchStartCountsOwnEvents() throws Exception {
        // batch 10..16, 3 workers: ordinal 1 owns 10,13,16; ordinal 0 owns 12,15; ordinal 2 owns 11,14
        assertEquals(List.of("B3", "10", "13", "16!"), feed(1, 3, 10, 7).calls);
        assertEquals(List.of("B2", "12", "15!"), feed(0, 3, 10, 7).calls);
        assertEquals(List.of("B2", "11", "14!"), feed(2, 3, 10, 7).calls);
    }

    @Test
    void workerWithNoEventsInABatchSeesNothing() throws Exception {
        assertEquals(List.of(), feed(2, 3, 3, 2).calls); // batch 3,4 belongs to workers 0 and 1
        assertEquals(List.of("B1", "4!"), feed(1, 3, 3, 2).calls);
    }

    @Test
    void consecutiveBatchesAreTrackedIndependently() throws Exception {
        final Recorder r = new Recorder();
        final WorkDistributionHandler<Object> h = new WorkDistributionHandler<>(r, 0, 2);
        h.onBatchStart(3, 3);
        h.onEvent(null, 0, false);
        h.onEvent(null, 1, false);
        h.onEvent(null, 2, true);
        h.onBatchStart(1, 1);
        h.onEvent(null, 3, true); // not mine: nothing
        h.onBatchStart(2, 2);
        h.onEvent(null, 4, false);
        h.onEvent(null, 5, true);
        assertEquals(List.of("B2", "0", "2!", "B1", "4!"), r.calls);
    }
}
