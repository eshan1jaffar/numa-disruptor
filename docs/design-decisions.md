# Design decisions

Each entry: the choice, the reason, and what it costs.

1. **Wrap the Disruptor, do not fork it.** Sequencing is the library's strength and is heavily tested. The project controls only
   placement. Cost: placement cannot change padding or sequencer layout; if profiling later shows a layout problem it would need
   its own justified change.
2. **FFM instead of JNI.** `sched_setaffinity` and libnuma are plain C functions; FFM binds them without a native build step on a
   cluster node. Cost: needs JDK 22+ (we target 25) and `--enable-native-access`.
3. **Independent partitions as the unit of placement.** Streams that need no mutual ordering can be placed independently, which
   is where a placement optimizer has leverage. A single globally ordered stream cannot be sharded without changing semantics, so
   benchmarks keep the partition count fixed across variants.
4. **Framework-owned producers.** The Disruptor never creates producers; placing a producer needs control of its thread, so
   `ProducerScheduler` creates and pins them and runs a user `ProducerTask`. External threads remain possible (`publish`) at the price
   of a multi-producer ring and unpinned publishers.
5. **Cost model apart from the optimizers.** `PlacementCostModel` has no dependency on any algorithm and can be recalibrated with
   measured latencies. Three traffic kinds are modelled separately because they load different hardware paths.
6. **Colocation groups.** A ring, its cursor and its gating sequences are allocated together on the heap, so they are one placement
   unit; this removes meaningless choices like "cursor on node 3, ring on node 0".
7. **Component-first greedy.** A stream that fits one node should never be split; plain cluster-by-cluster greedy splits streams
   when a node fills mid-stream (found with the naive baseline in tests). Components are placed whole by best-fit; only oversized
   ones are split.
8. **Strict, early failure.** Infeasible plans, pin failures and unsupported scenarios raise or are skipped *with a reason*; the
   system never silently runs unpinned or approximates a topology. Pin failures abort `start()` so no half-placed system runs.
9. **Handler-order pinning contract.** Consumers are pinned through the Disruptor's thread factory using the order of handler
   registration instead of a wrapper handler, to keep the hot path free of an extra call. A test asserts each handler's thread
   has the planned CPU.
10. **Batch-publishing bridge, one writer per downstream ring.** Guarantees contiguous claims (so `publish(lo, hi)` is valid) and
    amortises cursor transfers. Publishing before waiting avoids a deadlock with the downstream consumers.
11. **Per-trial JVMs.** Fresh JVM per trial removes JIT-profile and heap-layout carry-over between variants, at the price of ~3 s
    startup + `AlwaysPreTouch` per trial, which the time budget accounts for.
12. **Open-loop paced latency, closed-loop throughput.** Latency from the intended send time avoids coordinated omission;
    throughput deliberately saturates.
13. **Software proxies, clearly labelled.** Without PMU access the harness records numastat deltas and sampled migrations and says
    what they are; it does not print an estimate as a measurement.
14. **No `--exclusive`, no assumed topology.** The job's real masks drive which experiments run.
15. **Python for analysis only.** The core is Java; plotting reads JSONL and refuses to invent data (it fails on missing files,
    and every figure footer states trials, commit, host and number of NUMA nodes).
