# Progress

Snapshot of the implementation. For what the project is, see [README.md](README.md).

**Headline:** the framework, optimizer, runtime, native-memory layer, benchmark harness, deployment scripts and analysis tooling
are implemented and tested on Windows and on Linux (WSL2). **No measurement on the target NUMA hardware exists yet**, so there
is no evidence for or against the performance hypothesis.

## Checklist

| area | state | evidence |
|---|---|---|
| Gradle multi-module build, JDK 25 toolchain, pinned deps (Disruptor 4.0.0, JUnit 5.11.4, JMH 1.37, HdrHistogram 2.2.2), wrapper | done | `./gradlew build` passes (also runs in CI definition) |
| Topology discovery (sysfs, `/proc/self/status`, cgroup, SMT siblings, distances, allowed masks), synthetic topologies | done | 9 tests; real discovery verified on WSL (1 node, 16 CPUs) |
| CPU affinity via FFM, `PinnedThreadFactory`, verifier, shared-core detection | done | 10 tests; real `sched_setaffinity` verified on Linux |
| NUMA memory: libnuma allocator, regions, residency verifier, numa_maps, payload slab | done, optional | 8 tests on real libnuma (single node); use-after-close and leak checks |
| Communication graph, 3-kind cost model, naive / round-robin / greedy / greedy+local-search / brute-force | done | 18 tests; heuristic optimal in 271/300 random tiny cases |
| Runtime: partitioned rings, pinned producers/forwarders/consumers, routing, bridge, backpressure, drain/shutdown, failure handling | done | 19 tests incl. randomized stress and real-affinity test |
| Event model + verifiers (lost, duplicate, order, partial init, misroute, exactly-once) | done | see `docs/correctness.md` |
| Benchmark harness V0-V3, locality scenarios, matrices, metadata, JSONL/CSV, resume + time budget, JMH | done | 12 tests; `verify`: all variants equal the independent reference |
| HPC scripts (environment check, offline packaging, smoke gate, Slurm job, matrix runner, collect, verify) | done, **real Slurm untested** | `scripts/local/test-deploy.sh` passes on Linux with a fake `srun` |
| Analysis: tables, ratios with CIs and verdicts, figures from data only, report | done | 7 Python tests; run on real harness output |
| Docs: README, ARCHITECTURE, BENCHMARKING, CONTRIBUTING, design decisions, correctness, methodology, results | done | results doc is explicit that nothing NUMA was measured |
| CI workflow (portable build, analysis tests, optional Linux-native job) | written, **never run** (no remote yet) | `.github/workflows/ci.yml` |
| Experiments on the 2 x EPYC 7513 node | **not done** | needs a Slurm allocation |
| Hardware remote-memory / coherence counters | **not collected** | harness records availability only; software proxies are labelled |
| Calibration of the cost model coefficients | **not done** | defaults are priors; needs measured latencies |

## Test results (last run)

| platform | tests | skipped | failures |
|---|---|---|---|
| Windows 11, `./gradlew clean build` | 69 | 1 (real-Linux affinity test) | 0 |
| Linux (WSL2 Ubuntu 22.04), `test -PhardwareTests` | 76 | 0 | 0 |
| Python analysis, `unittest` | 7 | 0 | 0 |

Counts per module: `scripts/local/count-tests.sh`. Hardware-tagged tests are opt-in (`-PhardwareTests`).

## Findings so far (honest)

* A naive first-fit pinning splits a pipeline across nodes as soon as a node fills; whole-stream best-fit placement avoids that
  (model-level result, covered by a test).
* One bug class was found by the tests and fixed: the stage bridge must publish what it has written before waiting for space, and
  must publish a slot even if the stage function throws, otherwise downstream consumers wait forever.
* The first draft of the harness ran oversubscribed spinning workloads unpinned (V0), which would have measured the scheduler;
  it now skips them and reserves a CPU for the driver thread in pinned variants.
* On the laptop, run-to-run bimodality (5 M vs 35 M events/s in identical configurations) shows 2-3 trials cannot rank variants
  on a noisy host; the cluster plan uses >= 3 trials per cell and reports intervals.

## Known limitations

Java heap placement is first-touch best effort; single-node development hardware cannot exercise multi-node paths; the cost model is
uncalibrated; work distribution has no cross-worker order; the build stamp shows `unknown` commit unless built from a git checkout
(use `build-offline.sh`).

## Next steps

1. Push and enable CI; confirm the Linux-native job on a hosted runner.
2. First Slurm allocation: `environment-check`, `smoke-test`, then `MATRIX=locality`; inspect `report.md` skips to learn which
   nodes/sockets the allocation really provided.
3. Subsequent allocations: `scale`, `sweeps` (same `sbatch`, resumes automatically); `TRIALS=5` for cells that look close.
4. Run under `perf` if permitted to add remote-memory and coherence counters; calibrate `PlacementCostModel` from measured latencies.
5. Fill in `docs/experimental-results.md` from the real data, including any negative or inconclusive outcome.

## Commit history

See `git log --oneline`.
