# Benchmarking

## What is compared

All variants run the **same trading pipeline with the same partitioning and ordering**: per partition,
`market-data producer -> ring -> strategy stage -> order ring -> order gateway`, with deterministic ticks and a deterministic
per-instrument moving-average strategy. The only thing that differs is where threads run and where memory is first touched.

| variant | scheduling | rings | off-heap payload |
|---|---|---|---|
| **V0** vanilla | OS decides, no pinning | built by the launching thread | no |
| **V1** affinity | all workers pinned (placement given by `locality`) | built by the launching thread (placement-unaware first touch) | no |
| **V2** NUMA-local | pinned as V1 | built on a thread pinned to the ring's node | optional (`offheap=true`, needs libnuma) |
| **V3** optimized | optimizer decides (`locality=AUTO`) | as V2 | no |

V3 introduces no different semantics: it uses the same number of partitions as the other variants for a given workload, so
throughput ratios between configurations with the same `workloadKey` are apples-to-apples. A speedup from a *different*
partition count (sharding a stream that needs one total order) would **not** be comparable and is not reported as one.
`benchmarks verify` proves equivalence: it runs a fixed number of ticks through every variant and compares the order checksum with
an independent single-threaded computation (all four matched on Linux/WSL, and under simulated pinning in `HarnessTest`).

`locality` selects the placement for V1/V2: `SAME_NODE`, `SAME_SOCKET` (producer and consumers on two nodes of one socket),
`CROSS_SOCKET`, `MULTI_NODE_SPREAD` / `MULTI_NODE_LOCAL` (partitions over all usable nodes, remote or local), `DENSE`
(first-fit pinning), `ROUND_ROBIN`; `ringHome=PRODUCER|CONSUMER` says which side of the boundary holds ring 0. An experiment whose
topology the allocation cannot provide (for example `CROSS_SOCKET` with CPUs on one socket) is **skipped with a recorded reason**,
never approximated.

## Matrices

`benchmarks list --matrix NAME`: `smoke` (5 experiments, ~15 s), `locality` (locality x ring home x V1/V2, V0, V3, a multi-node
set, off-heap vs heap payload, throughput and latency), `scale` (1-16 partitions x V0/V1/V2/V3), `sweeps` (one factor at a time:
producers, consumers, multicast vs work distribution, ring size, event size, wait strategy, workload intensity, with/without the
strategy stage), `all`. Every experiment runs `--trials` times (default 3), **each in a fresh JVM**, so JIT profiles and heap state
cannot leak between variants. Trial 1 of every experiment runs before trial 2 of any, so a run cut short still covers the matrix.
At 5 s warm-up + 10 s measurement the full matrix needs more than one hour; `--resume` divides it across allocations.

## Measurements

| metric | how | caveat |
|---|---|---|
| events/s | completed orders at the gateways during the measurement window (unique events: gateway 0 when multicast, all when work-distributed) | closed loop: producers saturate the ring |
| p50/p95/p99/p99.9/max latency | HdrHistogram of `receipt − intended send time`; producers are paced open-loop at `rate`, so a late producer is charged the queueing delay (coordinated-omission corrected) | includes two `System.nanoTime` calls; the overhead is measured per run (`latency.nanoTimeOverheadNs`) and reported, not subtracted. `nanoTime` is `CLOCK_MONOTONIC`, comparable across cores |
| producer stalls | failed claims (ring full) in the window, absolute and per million events | |
| CPU utilisation | process CPU time / wall time in the window | busy-spinners read ~100% per worker by design |
| CPU migrations | `sched_getcpu` sampled every 16384 events per worker; changes counted | **software proxy**, a lower bound |
| memory placement | `/proc/self/numa_maps` per-node KiB of large mappings; off-heap residency by `numa_move_pages` | Java heap placement is best effort |
| NUMA counters | per-node `numastat` deltas | **page-allocation proxy**, system-wide, not remote-access counts |
| hardware remote-memory / coherence counters | **not collected per trial by the harness**; recorded availability (`metadata.hardwareCounters`) | optional whole-run `perf stat` via `PERF_WRAP=1`, see "Hardware counters" |
| optimizer overhead | wall time of graph build + optimization (`placement.optimizerNanos`), plus JMH `PlacementOptimizerBenchmark` | one-off at start, includes cold code in forked runs |
| GC / allocation | collections, pause ms, allocated bytes per event in the window | the steady state should allocate ~0 |

Each record also stores: commit, timestamp, anonymised host, CPU model, kernel, JVM + flags, Disruptor version, allowed CPU and
memory masks, discovered topology, cgroup quota, Slurm variables, and kernel settings that confound NUMA results
(`numa_balancing`, THP, governor, `perf_event_paranoid`).

### Hardware counters

`PERF_WRAP=1 scripts/hpc/run-locality-matrix.sh` runs the matrix driver under `scripts/hpc/perf-wrap.sh`, which uses
`perf stat -x,` when `perf` exists and `perf_event_paranoid` <= 2 (or root). Events (cycles, instructions, cache-misses, plus
node-* / remote-or-far DRAM events the host supports) are probed one by one; unsupported ones are dropped. Output goes to
`$OUT_DIR/perf/`: `perf-stat.csv`, `perf-events.txt`, or `perf-unavailable.txt` with the reason (the run is never failed by
perf). `python analysis/parse_perf.py perf-stat.csv` flattens the CSV to JSON.

Limitation: the counters cover the **whole matrix process tree** (driver, every trial JVM, warmup and idle time together), not
individual trials, and are not joined to `results.jsonl`. They show whether remote-memory events are non-trivial for the run as
a whole; they cannot attribute a count to one variant. Per-trial counters would need the harness to wrap each trial JVM.

Safeguards: spinning workloads (`BUSY_SPIN`, `YIELDING`) with more workers than allowed CPUs minus one are skipped (they would
measure the scheduler); pinned variants reserve the highest allowed CPU for the driver thread; every trial checks conservation
(published = delivered), per-producer order, and payload integrity, and is recorded `failed` otherwise; nothing logs on the hot path.

Output: `results.jsonl` (full record per trial, fsync'd per append) and `results.csv` (flat columns).

## Running locally

```bash
./gradlew :benchmarks:installDist
B=benchmarks/build/install/benchmarks/bin/benchmarks
$B topology
$B verify --events 200000
$B run --matrix smoke --trials 1 --out results/smoke
$B run --matrix scale --trials 3 --warmup 5 --measure 10 --filter V3 --out results/scale     # filter by id substring
$B run-one variant=V3 locality=AUTO partitions=4 metric=LATENCY rate=400000 --trial 1        # one trial, one JVM
```
Forked trials use `--jvm-args` (default `-Xms3g -Xmx3g -Xmn2g -XX:+UseParallelGC -XX:+UseNUMA -XX:+AlwaysPreTouch`). With a young
generation large enough that no GC happens after setup (the steady state allocates nothing) the ring objects stay where they were
first touched. `--no-fork` runs trials inside the launching JVM (faster, less independent).

JMH: `java -cp 'benchmarks/build/install/benchmarks/lib/*' org.openjdk.jmh.Main SequencerClaimBenchmark PlacementOptimizerBenchmark`.

## Running on the cluster

Prerequisites: a JDK 25 reachable on the compute node (`module load …` or `JAVA_HOME`). Nothing else, no network.

1. **Package** (your machine): `scripts/hpc/build-offline.sh` (runs the whole test suite, then bundles benchmarks + examples with
   every dependency jar into `dist/numa-disruptor-<commit>.tar.gz` + `.sha256`). `--offline` proves Gradle's local cache suffices.
2. **Stage**: copy the tarball and checksum to project storage (`scp`/`rsync`, per your site's rules).
3. **Submit** from the login node (never run benchmarks there; the scripts refuse outside Slurm):
   `sbatch --partition=<p> --account=<a> [--mem=0] scripts/hpc/benchmark.slurm`. It requests one node, 1 h, 64 CPUs, no `--exclusive`.
   Environment: `MATRIX` (default `all`), `TRIALS`, `WARMUP`, `MEASURE`, `JVM_ARGS`, `DIST_TARBALL`, `JAVA_MODULE`, `BUDGET_SECONDS`.
4. What the job does: verify the tarball checksum, unpack to node-local scratch, `environment-check.sh` (Slurm env, **allowed CPU and
   memory masks**, cgroup limits, kernel knobs, libnuma/perf presence, JDK >= 25, topology), `smoke-test.sh` (all variants vs the
   reference, then the smoke matrix; any failure aborts before benchmarking), then the matrix with `--budget-seconds` ≈ 3600 − 420 − setup.
   The runner stops *launching* a trial when it could not finish plus a 60 s margin; each finished trial is already on disk.
   `--signal=B:USR1@300` triggers a final sync before the limit.
5. **Resume**: submit the same command again. Finished trials are skipped; trials that were *skipped for topology* are retried (a
   different allocation may provide the nodes).
6. **Collect and check**: `scripts/hpc/collect-results.sh results.tar.gz results/all`, copy it back, then
   `scripts/hpc/verify-results.sh results.tar.gz` (checksums, JSON validity, required metadata, no failed trials) and
   `python analysis/plot_results.py results/all --out analysis/out`.

Not assumed: that `--cpus-per-task=64` yields CPUs on both sockets, that all 8 nodes are allowed, or any CPU numbering. The job's
real masks decide which experiments run; the rest are skipped with reasons listed in `report.md`. Binding: the task runs with
`--cpu-bind=none --mem-bind=none` (override with `SRUN_BIND_ARGS`) because the harness pins its own threads inside the job's cpuset
and memory-binding the task to one node would defeat node-local placement experiments.

Tested without Slurm: `scripts/local/test-deploy.sh <tarball>` runs the scripts on Linux with a fake `srun` (login-node guard, resume,
budget enforcement, collection, tamper detection). Real Slurm behaviour (cpuset, binding, signals) is untested until the first
allocation.
