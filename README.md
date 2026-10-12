# numa-disruptor

A NUMA-aware runtime around the [LMAX Disruptor](https://github.com/LMAX-Exchange/disruptor) (4.0.0), plus the benchmark
and analysis tooling to find out **whether** NUMA-aware placement helps. The performance benefit is a hypothesis under test, not
a result: see [PROGRESS.md](PROGRESS.md) for the current state and [docs/experimental-results.md](docs/experimental-results.md) for what has and has not been measured so far.

```
Java 25 · Gradle (Kotlin DSL) · Disruptor 4.0.0 · JUnit 5 · JMH · HdrHistogram · FFM (no JNI) · Python 3 / pandas / matplotlib
```

## The problem

On a multi-node machine, threads and memory sit in different NUMA domains. The 2 x AMD EPYC 7513 target has 8 nodes (4 per
socket); the kernel reports relative distances of 10 (local), 12 (same socket) and 32 (other socket). When a producer, a ring
and a consumer are on different nodes, three different things cost more:

| mechanism | when | Disruptor hot spot |
|---|---|---|
| remote **DRAM** access | a core reads/writes memory homed on another node | event payload in the ring |
| cache-line **ownership transfer** | a line modified by one core is requested by another, over the inter-node/-socket fabric | cursor, availability flags, consumer sequences |
| **cross-socket** fabric traffic | the two cores are on different sockets | both of the above, at the highest latency |

These are not the same: an event that crosses nodes is not automatically a remote write, and a cursor that ping-pongs is
coherence traffic, not DRAM bandwidth. The placement model keeps them separate
([ARCHITECTURE.md](ARCHITECTURE.md#communication-graph-and-cost-model)).

## How the Disruptor works (the parts that matter here)

A ring buffer of **preallocated, reused, mutable** events; a **sequence** number per participant, each written by exactly one
thread (padded to its own cache line); producers claim a slot (`next`), fill it, then **publish** (a release store of the cursor,
or of a per-slot availability flag for multi-producer rings). Consumers wait on the cursor/their dependencies (acquire), process
a **batch** of everything available, then advance their own sequence, which is what lets producers wrap the ring (backpressure).
There are no locks on the hot path, and no allocation. Ordering and visibility come entirely from those release/acquire pairs.

## What this project changes

It does **not** modify the Disruptor's sequencing. It decides *where things run and live*:

```
 workload spec ──► communication graph ──► optimizer ──► placement plan ──► pinned workers + node-local rings
 (partitions,      (producers, rings,      (naive / round-robin /   (node + CPU       (NumaEventProcessor)
  stages, rates)    cursors, gating,        greedy / greedy+local    per vertex)
                    consumers; traffic      search; brute force
                    by kind)                reference)
        ▲                                         ▲
   topology discovery (sysfs, /proc/self/status, cgroup)  — allowed CPUs and memory nodes of THIS job
```

* **Independent partitions** — each stream gets its own ring chain, so streams can be placed independently. Per-partition order
  is preserved; there is **no global order across partitions** ([docs/correctness.md](docs/correctness.md)).
* **Runtime-owned workers** — the Disruptor never creates producers; this runtime creates and pins producer threads and runs
  your `ProducerTask` on them, pins forwarding stages and consumers, and constructs each ring on a thread pinned to the ring's node.
* **Batch-publishing bridge** between ring stages (`CrossNodeBridge`), so the cursor line crosses nodes once per batch.
* **Optional off-heap payloads** bound to a node with `numa_alloc_onnode`, with residency verified through `numa_move_pages`.

```java
NumaEventProcessor<SequencedEvent> processor = NumaEventProcessor.<SequencedEvent>builder()
    .eventFactory(SequencedEvent.factory(2))
    .partitions(8)                                   // independent streams
    .producersPerPartition(1)                        // framework-created, pinned producer threads
    .forwardingStages(1, (partition, in, out) -> out.copyFrom(in))   // e.g. the strategy stage
    .consumers(1, ConsumptionMode.MULTICAST, (partition, i) -> myHandler(partition))
    .ringBufferSize(1 << 16)
    .waitStrategy(WaitStrategyKind.BUSY_SPIN)
    .placementPolicy(PlacementPolicy.MIN_REMOTE_TRAFFIC)
    .build();

processor.start(ctx -> {                             // ProducerTask: runs once on each pinned producer thread
    for (long seq = 0; ctx.isRunning(); seq++) {
        long slot = ctx.claimBlocking();             // -1 when stopping; spins (counted as stalls) when the ring is full
        if (slot < 0) return;
        ctx.event(slot).init(ctx.partition(), ctx.producerId(), seq, System.nanoTime());
        ctx.publish(slot);                           // always publish what you claimed
    }
});
// ... or publish from your own threads: processor.publish(routingKey, translator, arg) with .externalPublishers(true)
processor.stop(Duration.ofSeconds(30));              // drains every ring in pipeline order; throws if any worker failed
```

Runnable example: `./gradlew :examples:run`.

## Topology discovery

`TopologyDiscovery` reads `/sys/devices/system/{cpu,node}` (node CPU lists, distances, per-node `MemTotal`, package/core ids,
SMT sibling lists) and `/proc/self/status` (`Cpus_allowed_list`, `Mems_allowed_list`, which reflect the Slurm/cgroup cpuset and
`taskset`), plus the cgroup `cpu.max` quota. It never parses `lscpu`. Everything downstream uses the **allowed** masks, never
the hardware layout: `NumaTopology.usableNodes()`, `allowedCpusOnNode()`. Non-Linux hosts get a one-node fallback so the
portable tests run anywhere. Diagnostics: `benchmarks/bin/benchmarks topology` prints the topology and allowed masks this job sees.

## Placement optimizer

Minimises `Σ_(i,j) w_ij · (D(x_i, x_j) − 1)` where `w_ij` is the edge's *weighted cache lines per second* (separate
coefficients for payload reads, payload writes, ownership transfers) and `D` is node distance normalised so local = 1 (so a
fully local plan costs exactly 0), subject to per-node CPU capacity (allowed, non-reserved, one hardware thread per core before
SMT siblings), per-node memory, per-vertex allowed nodes, and colocation groups (a ring, its cursor and its gating sequences are
one unit). Four strategies plus an exhaustive reference:

| optimizer | idea |
|---|---|
| `NaivePlacementOptimizer` | first-fit: fill node after node, ignore traffic |
| `RoundRobinPlacementOptimizer` | rotate clusters across nodes, ignore traffic |
| `GreedyPlacementOptimizer` | place whole independent streams on the best-fit node, split only streams too big for one node |
| `LocalSearchOptimizer(greedy)` | relocate/swap moves while the cost strictly drops |
| `BruteForcePlacementOptimizer` | exact, for tiny graphs; used to test the heuristics |

On 300 random tiny instances greedy+local-search matched the exact optimum in 271 (90%) and never beat it or lost to plain
greedy. Costs are **model estimates**, not measured remote traffic; the model is separate from the optimizers so it can be
calibrated with measured latencies (`PlacementCostModel.fromLatencyMatrix`).

## Memory-ordering guarantees

Unchanged from the Disruptor and relied upon, not reimplemented: a write to a slot (heap fields, heap arrays or off-heap payload)
made before `publish` is visible to a consumer that observes the published sequence, and consumers never see a slot the
producer has not published. The runtime adds only: producers must publish every claimed slot; `CrossNodeBridge` publishes the
slots it wrote before it can wait for space (otherwise it would deadlock against its own consumers); `stop()` drains rings in
pipeline order so no in-flight event is dropped. Pinning and placement change *which core* runs a thread, not any ordering.

## Build and test

```bash
./gradlew build                      # compile everything and run all portable tests (no NUMA hardware needed)
./gradlew test -PhardwareTests       # also real sched_setaffinity / libnuma / sysfs tests (Linux)
```

JDK 25 is required (`java.toolchain` is pinned to 25; set `JAVA_HOME` or let Gradle find it). On Windows, Linux-only code can be
tested in WSL with `scripts/local/wsl-gradle.sh`. Python: `pip install -r analysis/requirements.txt`, then
`python -m unittest analysis/test_plot_results.py`.

## Local benchmarks (small machine, for development only)

```bash
./gradlew :benchmarks:installDist
benchmarks/build/install/benchmarks/bin/benchmarks verify --events 200000        # V0..V3 vs independent reference
benchmarks/build/install/benchmarks/bin/benchmarks run --matrix smoke --trials 1 --out results/smoke
benchmarks/build/install/benchmarks/bin/benchmarks list --matrix all             # every experiment id
./gradlew :benchmarks:run --args="..."                                           # or via Gradle
java -cp 'benchmarks/build/install/benchmarks/lib/*' org.openjdk.jmh.Main -l     # JMH microbenchmarks
```

A laptop with one NUMA node cannot show NUMA effects; results from it only prove the harness works.
See [BENCHMARKING.md](BENCHMARKING.md) for the four variants, the matrices, metrics and methodology.

## Deploying to Slurm

```bash
scripts/hpc/build-offline.sh                      # on your machine: tests + self-contained tarball (needs only a JDK 25 on the cluster)
scp dist/numa-disruptor-<commit>.tar.gz{,.sha256} <login-node>:<project-dir>/dist/
# on the login node (do NOT run benchmarks there):
sbatch --partition=<p> --account=<a> scripts/hpc/benchmark.slurm      # MATRIX=all by default; 1 h limit, ~53 min budget
# repeat the same sbatch for the next hour: finished trials are skipped (--resume) and results accumulate in results/<matrix>/
scripts/hpc/collect-results.sh results.tar.gz results/all             # then scp to your machine
scripts/hpc/verify-results.sh results.tar.gz && python analysis/plot_results.py results/all --out analysis/out
```
Details, assumptions and failure modes: [BENCHMARKING.md](BENCHMARKING.md#running-on-the-cluster).

## Reproducing reported results

Every figure and table is produced by `analysis/plot_results.py` from `results.jsonl` files. Each record carries the git commit,
JVM and flags, kernel, allowed CPU/memory masks, topology, Slurm environment and kernel NUMA settings. To reproduce a result:
check out the recorded commit, build with `build-offline.sh`, run the same matrix on a comparable allocation, compare.

## Limitations (read these)

* Java heap placement is **best effort**: rings are first-touched on a node-pinned thread, but Java offers no placement API and GC
  may move objects. Check `numa.jvmMappingsResidentKiBByNode` in the results. Only off-heap payloads have verified placement.
* Hardware remote-memory and coherence counters are **not read per trial** by the harness. Where `perf` is permitted,
  `PERF_WRAP=1` wraps the whole matrix run (aggregate only, see BENCHMARKING.md); otherwise only labelled software proxies are recorded (numastat page-allocation deltas, sampled CPU migrations).
* Multi-node behaviour has **not been measured yet** in this repository: development hosts have one NUMA node. The cluster runs
  are the experiment.
* Work distribution has no ordering between workers; multi-producer rings have no cross-producer order guarantee beyond a single
  agreed claim order.
* The cost model coefficients are uncalibrated priors.
