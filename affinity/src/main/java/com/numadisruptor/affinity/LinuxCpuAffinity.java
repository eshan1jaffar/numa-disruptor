package com.numadisruptor.affinity;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.BitSet;

/**
 * {@link CpuAffinity} over glibc {@code sched_setaffinity}/{@code sched_getaffinity}/{@code sched_getcpu}
 * via the Foreign Function &amp; Memory API. The downcalls run on the calling OS thread, so pid 0 addresses
 * exactly the Java platform thread that makes the call.
 *
 * <p>The mask buffer is a fixed 1024-bit {@code cpu_set_t}; machines with more logical CPUs are rejected.
 */
final class LinuxCpuAffinity implements CpuAffinity {
    private static final int CPU_SET_BYTES = 128;
    private static final int MAX_CPUS = CPU_SET_BYTES * 8;

    private static final Linker.Option CAPTURE_ERRNO = Linker.Option.captureCallState("errno");
    private static final VarHandle ERRNO = Linker.Option.captureStateLayout()
        .varHandle(MemoryLayout.PathElement.groupElement("errno"));

    private final MethodHandle setAffinity;
    private final MethodHandle getAffinity;
    private final MethodHandle getCpu;

    LinuxCpuAffinity() {
        final Linker linker = Linker.nativeLinker();
        final var lookup = linker.defaultLookup();
        final FunctionDescriptor affinityFn = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
        setAffinity = linker.downcallHandle(
            lookup.find("sched_setaffinity").orElseThrow(), affinityFn, CAPTURE_ERRNO);
        getAffinity = linker.downcallHandle(
            lookup.find("sched_getaffinity").orElseThrow(), affinityFn, CAPTURE_ERRNO);
        getCpu = linker.downcallHandle(
            lookup.find("sched_getcpu").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    @Override
    public BitSet currentThreadAffinity() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment errno = arena.allocate(Linker.Option.captureStateLayout());
            final MemorySegment mask = arena.allocate(CPU_SET_BYTES, 8);
            final int rc = (int) getAffinity.invokeExact(errno, 0, (long) CPU_SET_BYTES, mask);
            if (rc != 0) {
                throw new AffinityException("sched_getaffinity failed, errno=" + (int) ERRNO.get(errno, 0L));
            }
            return BitSet.valueOf(mask.toArray(ValueLayout.JAVA_BYTE));
        } catch (final AffinityException e) {
            throw e;
        } catch (final Throwable t) {
            throw new AffinityException("sched_getaffinity invocation failed", t);
        }
    }

    @Override
    public void pinCurrentThread(final BitSet cpus) {
        if (cpus.isEmpty()) {
            throw new IllegalArgumentException("Cannot pin to an empty CPU set");
        }
        if (Thread.currentThread().isVirtual()) {
            throw new IllegalArgumentException("Cannot pin a virtual thread; use a platform thread");
        }
        if (cpus.length() > MAX_CPUS) {
            throw new IllegalArgumentException("CPU id " + (cpus.length() - 1) + " exceeds supported maximum " + (MAX_CPUS - 1));
        }
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment errno = arena.allocate(Linker.Option.captureStateLayout());
            final MemorySegment mask = arena.allocate(CPU_SET_BYTES, 8);
            final byte[] bytes = cpus.toByteArray();
            MemorySegment.copy(bytes, 0, mask, ValueLayout.JAVA_BYTE, 0, bytes.length);
            final int rc = (int) setAffinity.invokeExact(errno, 0, (long) CPU_SET_BYTES, mask);
            if (rc != 0) {
                final int e = (int) ERRNO.get(errno, 0L);
                throw new AffinityException(
                    "sched_setaffinity(" + cpus + ") failed, errno=" + e + (e == 22 ? " (EINVAL: no CPU in the set is permitted for this job)" : ""));
            }
        } catch (final AffinityException e) {
            throw e;
        } catch (final Throwable t) {
            throw new AffinityException("sched_setaffinity invocation failed", t);
        }
    }

    @Override
    public int currentCpu() {
        try {
            return (int) getCpu.invokeExact();
        } catch (final Throwable t) {
            throw new AffinityException("sched_getcpu invocation failed", t);
        }
    }
}
