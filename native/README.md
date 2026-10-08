# native/

Intentionally empty of C/C++ sources.

Everything that needs the OS is reached through the Java Foreign Function & Memory API (JDK 25, final):

| capability | libc / libnuma symbols | Java class |
|---|---|---|
| per-thread CPU affinity | `sched_setaffinity`, `sched_getaffinity`, `sched_getcpu` | `affinity/LinuxCpuAffinity` |
| NUMA-bound allocation | `numa_alloc_onnode`, `numa_free`, `numa_max_node`, `numa_available` | `memory/NumaAllocator` |
| physical page residency | `numa_move_pages` (query mode, moves nothing) | `memory/NumaMemoryVerifier` |

A JNI shim would only add a build step on the cluster; this directory is kept as the place for one if a future capability
(for example reading PMU counters through `perf_event_open`) cannot be expressed through FFM.
