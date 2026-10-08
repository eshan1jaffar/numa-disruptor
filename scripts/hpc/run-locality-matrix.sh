#!/usr/bin/env bash
# Run a benchmark matrix with a hard time budget, appending results durably so an allocation can end at any moment.
# Re-running the same command in a later allocation continues where this one stopped (--resume skips finished trials).
#
#   MATRIX          smoke | locality | scale | sweeps | all        (default locality)
#   TRIALS          independent trials per experiment (default 3)  - each trial is a fresh JVM
#   WARMUP/MEASURE  seconds per trial (default 5 / 10)
#   BUDGET_SECONDS  stop launching new trials once the next one could not finish in time (default 3000)
#   OUT_DIR         results directory (default results/$MATRIX)
#   JVM_ARGS        flags for each trial JVM
set -euo pipefail
export DIST_DIR="${DIST_DIR:-$PWD/dist/numa-disruptor}"
BENCH="$DIST_DIR/benchmarks/bin/benchmarks"
MATRIX="${MATRIX:-locality}"
OUT_DIR="${OUT_DIR:-results/$MATRIX}"

if [[ -z "${SLURM_JOB_ID:-}" && "${NUMA_BENCH_ALLOW_LOCAL:-0}" != "1" ]]; then
  echo "Refusing to run sustained benchmarks outside a Slurm allocation (login nodes are not for this)." >&2
  echo "Submit scripts/hpc/benchmark.slurm, or set NUMA_BENCH_ALLOW_LOCAL=1 on your own machine." >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
echo "matrix=$MATRIX trials=${TRIALS:-3} budget=${BUDGET_SECONDS:-3000}s out=$OUT_DIR"
exec "$BENCH" run \
  --matrix "$MATRIX" --trials "${TRIALS:-3}" --warmup "${WARMUP:-5}" --measure "${MEASURE:-10}" \
  --budget-seconds "${BUDGET_SECONDS:-3000}" --resume --out "$OUT_DIR" \
  --jvm-args "${JVM_ARGS:--Xms6g -Xmx6g -Xmn4g -XX:+UseParallelGC -XX:+UseNUMA -XX:+AlwaysPreTouch --enable-native-access=ALL-UNNAMED}"
