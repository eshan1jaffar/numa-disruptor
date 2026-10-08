# Performance methodology

**Question.** Does placing producers, rings and consumers by communication pattern on a multi-node machine improve throughput
and tail latency over (a) OS scheduling and (b) pinning without regard to memory or traffic, for workloads with equivalent work
and ordering?

**Hypotheses** (each may be refuted): (H1) cross-socket placement of producer/consumer lowers throughput and raises p99/p99.9
versus same-node; (H2) for a cross-node pair, placing the ring on the producer's vs the consumer's node changes the result
measurably; (H3) V3 matches or beats V0 and V1 for multi-partition workloads on both sockets; (H4) V2's node-bound off-heap payload
helps only when payloads are large enough for DRAM traffic to matter; (H5) optimizer overhead is negligible next to start-up.
Equally plausible outcomes: no difference (the hot path is dominated by cache-line ownership transfers whose cost the OS
scheduler already keeps low, or by the strategy's compute), or OS scheduling being competitive because the working set fits caches.

**Design.** Factors: variant x locality (x ring home) x partitions x producers x consumers x consumption mode x ring size x event
bytes x wait strategy x work per event x stage on/off. Sweeps are one-factor-at-a-time around a base so that every point is
interpretable; a full factorial is not attempted. Every cell runs >= 3 independent trials (fresh JVM, 5 s warm-up, 10 s measurement
by default). Order is trial-major so partial runs still span the matrix.

**Controls.** Identical code and wiring for all variants; fixed partition count within a `workloadKey`; no GC in the window
(allocation per event is reported and should be ~0); no oversubscription of spinning workers; driver thread on its own CPU for
pinned variants; kernel NUMA balancing, THP, governor and Slurm limits recorded; page-cache/network/disk not involved.

**Statistics.** Per cell: mean, sample standard deviation and a Student-t 95% interval over trials (`n < 2` is labelled
`n<2`). Ratios of means use a delta-method interval from the two cells' relative errors; a ratio is `better`/`worse` only if the
interval excludes 1, otherwise `inconclusive`. Latency percentiles are computed per trial from the full histogram and then
averaged across trials; they are not percentiles of pooled samples. Three trials give wide intervals: they support "large effect /
no effect" statements, not fine rankings. Run more trials (`TRIALS=5+`) before claiming small differences.

**Threats to validity.** Shared nodes (no `--exclusive`) allow interference from other jobs; frequency scaling and SMT siblings
(placement avoids sharing a core until cores run out and says so); Java heap placement is first-touch best effort; the cost
model is uncalibrated, so optimizer *rankings* are model predictions to be compared with measurements, not measurements; modelled
traffic is estimated from rates, not sampled; synthetic workload with a trivial strategy emphasises the messaging layer.

**Reporting rules.** Figures and tables come only from `results.jsonl` via `analysis/plot_results.py`; every figure carries trial
count, commit, host and NUMA node count; skipped and failed trials are listed in `report.md`; negative and inconclusive results are
reported with the same prominence as positive ones.
