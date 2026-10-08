package com.numadisruptor.affinity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.numadisruptor.topology.CpuList;
import com.numadisruptor.topology.NumaTopology;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class AffinityTest {
    private static BitSet cpus(final String list) {
        return CpuList.parse(list);
    }

    @Test
    void simulatedPinAppliesOnlyToCallingThread() throws Exception {
        final SimulatedCpuAffinity a = new SimulatedCpuAffinity(cpus("0-7"));
        a.pinCurrentThread(cpus("2"));
        assertEquals(cpus("2"), a.currentThreadAffinity());

        final AtomicReference<BitSet> other = new AtomicReference<>();
        final Thread t = new Thread(() -> other.set(a.currentThreadAffinity()));
        t.start();
        t.join();
        assertEquals(cpus("0-7"), other.get(), "a new thread must not inherit the parent's simulated pin");
    }

    @Test
    void rejectsEmptyAndDisallowedMasks() {
        final SimulatedCpuAffinity a = new SimulatedCpuAffinity(cpus("0-3"));
        assertThrows(IllegalArgumentException.class, () -> a.pinCurrentThread(new BitSet()));
        assertThrows(AffinityException.class, () -> a.pinCurrentThread(cpus("9")));
    }

    @Test
    void scopedPinRestoresPreviousMask() {
        final SimulatedCpuAffinity a = new SimulatedCpuAffinity(cpus("0-3"));
        try (CpuAffinity.Restore scope = a.pinScoped(cpus("1"))) {
            assertTrue(scope != null);
            assertEquals(cpus("1"), a.currentThreadAffinity());
        }
        assertEquals(cpus("0-3"), a.currentThreadAffinity());
    }

    @Test
    void pinnedFactoryPinsEachThreadInOrderAndVerifies() throws Exception {
        final SimulatedCpuAffinity a = new SimulatedCpuAffinity(cpus("0-7"));
        final PinnedThreadFactory f = PinnedThreadFactory.forCpus("w", a, 3, 5);
        final AtomicReference<BitSet> seen0 = new AtomicReference<>();
        final AtomicReference<BitSet> seen1 = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(2);
        f.newThread(() -> { seen0.set(a.currentThreadAffinity()); done.countDown(); }).start();
        f.newThread(() -> { seen1.set(a.currentThreadAffinity()); done.countDown(); }).start();
        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals(cpus("3"), seen0.get());
        assertEquals(cpus("5"), seen1.get());
        assertEquals(2, f.pinnedCount());
        assertTrue(f.failures().isEmpty());
        assertThrows(IllegalStateException.class, () -> f.newThread(() -> { }), "no assignment left must be an error");
    }

    @Test
    void pinFailureIsRecordedAndTaskDoesNotRun() throws Exception {
        final SimulatedCpuAffinity a = new SimulatedCpuAffinity(cpus("0-3"));
        final PinnedThreadFactory f = PinnedThreadFactory.forCpus("w", a, 42);
        final boolean[] ran = {false};
        final Thread t = f.newThread(() -> ran[0] = true);
        t.start();
        t.join(10_000);

        assertFalse(ran[0]);
        assertEquals(0, f.pinnedCount());
        assertEquals(1, f.failures().size());
        assertTrue(f.failures().get(0) instanceof AffinityException);
    }

    @Test
    void verifierDetectsMismatch() {
        final SimulatedCpuAffinity a = new SimulatedCpuAffinity(cpus("0-3"));
        a.pinCurrentThread(cpus("1"));
        assertTrue(AffinityVerifier.verifyCurrentThread(a, cpus("1")).ok());
        assertFalse(AffinityVerifier.verifyCurrentThread(a, cpus("2")).ok());
    }

    @Test
    void requireAllowedChecksJobMask() {
        final NumaTopology t = NumaTopology.epyc7513DualSocket().restrictedTo(cpus("0-7"));
        AffinityVerifier.requireAllowed(t, cpus("0-3"));
        assertThrows(AffinityException.class, () -> AffinityVerifier.requireAllowed(t, cpus("8")));
        assertThrows(AffinityException.class, () -> AffinityVerifier.requireAllowed(t, new BitSet()));
    }

    @Test
    void detectsWorkersSharingAPhysicalCore() {
        final NumaTopology t = NumaTopology.epyc7513DualSocket();
        // synthetic layout: cpus 0,1 are SMT siblings of one core; 2 is another core
        assertTrue(AffinityVerifier.sharedPhysicalCores(t, Map.of("a", 0, "b", 2)).isEmpty());
        final List<String> shared = AffinityVerifier.sharedPhysicalCores(t, Map.of("a", 0, "b", 1));
        assertEquals(1, shared.size());
    }

    @Test
    void unsupportedImplementationFailsLoudly() {
        final CpuAffinity u = new UnsupportedCpuAffinity("test");
        assertFalse(u.isSupported());
        assertThrows(AffinityException.class, () -> u.pinCurrentThread(cpus("0")));
        assertEquals(-1, u.currentCpu());
    }

    /** Real syscalls: runs wherever Linux sched_setaffinity is available (e.g. CI, WSL, the HPC node). */
    @Test
    void linuxPinsOnlyTheWorkerThread() throws Exception {
        final CpuAffinity real = CpuAffinity.system();
        assumeTrue(real.isSupported(), "Linux affinity not available on this host");

        final BitSet mainBefore = real.currentThreadAffinity();
        final int target = mainBefore.nextSetBit(0);
        final AtomicReference<AffinityVerifier.Result> result = new AtomicReference<>();
        final Thread t = new Thread(() -> {
            real.pinCurrentThread(CpuAffinity.cpu(target));
            result.set(AffinityVerifier.verifyCurrentThread(real, CpuAffinity.cpu(target)));
        });
        t.start();
        t.join();

        assertTrue(result.get().ok(), result.get().message());
        assertEquals(mainBefore, real.currentThreadAffinity(), "pinning a worker must not change the caller's mask");
        assertThrows(AffinityException.class, () -> {
            final BitSet impossible = new BitSet();
            impossible.set(1023);
            real.pinCurrentThread(impossible);
        });
    }
}
