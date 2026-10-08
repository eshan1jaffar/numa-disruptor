# Experimental results

**Status: no NUMA experiment has been run yet.** The target 8-node EPYC machine has not been used. Everything below was
measured on a development laptop and says nothing about NUMA effects.

## What exists

`results/examples/wsl-single-node-sweeps/` holds a real output of the harness (`results.jsonl`, `results.csv`, run summary):
the `sweeps` matrix, 2 trials per experiment, 2 s warm-up + 3 s measurement, forked JVMs.

* Host: AMD Ryzen 9 7940HS laptop, Windows 11 + WSL2 (Linux 6.18), **1 NUMA node, 16 logical CPUs**, shared with other desktop
  software, no `perf`. Commit stamp reads `unknown` because the WSL copy of the tree has no `.git`.
* 120 records: 84 `ok`, 36 `skipped` (workload needs more CPUs than the host has, or would oversubscribe spinning workers; reasons
  are in the file), 0 `failed`. Every `ok` trial passed conservation, order and payload-integrity checks.
* Regenerate tables and figures: `python analysis/plot_results.py results/examples/wsl-single-node-sweeps --out analysis/out`.

## What it shows, and what it does not

* The harness, metadata capture, resume/skip logic, analysis and verification scripts work end to end on real runs.
* All four variants deliver exactly the orders of an independent reference computation (`benchmarks verify`, 200,000 ticks x 2
  partitions each, on Linux with real pinning).
* **No performance conclusion is supported.** With n = 2 the 95% intervals are enormous and 39 of 42 comparisons against V0 are
  `inconclusive`. Two comparisons (V1 and V3 about 6x V0 at ring size 65536) look large, but the raw trials show V0 at 5 M events/s
  versus 33 M for the others, while in the 16384-slot configuration the *same* kind of dip appears in a V3 trial (5.3 M versus 35 M)
  and in a V0 trial. That is bimodal run-to-run noise on a shared laptop, not a placement effect: V0 workers did migrate between
  CPUs (observed CPU changes 7-15) while pinned variants did not (0), which is consistent with scheduler interference, but this run
  cannot attribute the cause. Treat it as a reminder that three trials on a noisy host cannot rank variants.
* There is one NUMA node, so locality scenarios (`SAME_SOCKET`, `CROSS_SOCKET`, `MULTI_NODE_*`) were correctly skipped and the
  optimizer's choices are trivial.

## Optimizer, measured separately (portable)

On 300 seeded random tiny instances the greedy + local-search heuristic reached the exact (brute-force) optimum in 271 (90.3%),
never exceeded plain greedy and never beat the optimum. For 16 independent pipelines (48 workers) on the 64-CPU EPYC layout it finds a
zero-remote-cost plan, with each pipeline on one node, in well under 5 s in the test (JMH numbers have not been collected). These
are model results, not hardware measurements.

## To be filled in after the Slurm runs

Throughput and p50/p95/p99/p99.9 by locality (H1, H2), multi-node scaling for V0-V3 (H3), off-heap payload effect (H4), optimizer
overhead (H5); each with trial counts, intervals and, where the data say so, an explicit "no measurable effect" or "V3 is worse"
and an explanation of the bottleneck. See [performance-methodology.md](performance-methodology.md) for the hypotheses.
