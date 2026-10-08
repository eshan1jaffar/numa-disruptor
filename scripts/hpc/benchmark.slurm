#!/usr/bin/env bash
#SBATCH --job-name=numa-disruptor
#SBATCH --time=01:00:00
#SBATCH --nodes=1
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=64
#SBATCH --output=numa-disruptor-%j.out
#SBATCH --signal=B:USR1@300
# --partition / --account / --mem are cluster specific: pass them on the command line, e.g.
#   sbatch --partition=<p> --account=<a> --mem=0 scripts/hpc/benchmark.slurm
# This script does NOT request --exclusive. Without exclusivity, the other jobs on the node can perturb results; the harness
# records the Slurm environment and cpu masks it was actually given, so the run is still interpretable.
# --cpus-per-task=64 does not guarantee CPUs on both sockets: the harness reads the real masks and skips what cannot run.
#
# Environment (all optional):
#   DIST_TARBALL  path to numa-disruptor-<commit>.tar.gz   (default: ./dist/numa-disruptor-*.tar.gz, newest)
#   RESULTS_DIR   persistent results directory (default: $PWD/results/job-$SLURM_JOB_ID)
#   MATRIX        smoke|locality|scale|sweeps|all (default all); re-submit the same command for the next hour
#   TRIALS WARMUP MEASURE JVM_ARGS  see run-locality-matrix.sh
#   JAVA_MODULE   `module load` name for JDK 25, or set JAVA_HOME
set -uo pipefail

START=$(date +%s)
LIMIT_SECONDS=3600
SAFETY_SECONDS=420          # leave time for the final sync and for the last trial to be killed cleanly
export BUDGET_SECONDS="${BUDGET_SECONDS:-$(( LIMIT_SECONDS - SAFETY_SECONDS ))}"

SUBMIT_DIR="${SLURM_SUBMIT_DIR:-$PWD}"
cd "$SUBMIT_DIR"
RESULTS_DIR="${RESULTS_DIR:-$SUBMIT_DIR/results/job-${SLURM_JOB_ID:-local}}"
MATRIX="${MATRIX:-all}"
# the matrix resumes across allocations, so results of one matrix always go to one directory
OUT_DIR="${OUT_DIR:-$SUBMIT_DIR/results/$MATRIX}"
export OUT_DIR MATRIX

if [[ -n "${JAVA_MODULE:-}" ]]; then module load "$JAVA_MODULE"; fi

# --- stage the distribution on node-local storage so reading jars never touches the shared filesystem during measurement
SCRATCH="${SLURM_TMPDIR:-/tmp}/numa-disruptor-${SLURM_JOB_ID:-$$}"
mkdir -p "$SCRATCH"
TARBALL="${DIST_TARBALL:-$(ls -t "$SUBMIT_DIR"/dist/numa-disruptor-*.tar.gz 2>/dev/null | head -1)}"
if [[ -z "$TARBALL" || ! -f "$TARBALL" ]]; then echo "no distribution tarball found (set DIST_TARBALL)" >&2; exit 1; fi
if [[ -f "$TARBALL.sha256" ]]; then ( cd "$(dirname "$TARBALL")" && sha256sum -c "$(basename "$TARBALL").sha256" ) || exit 1; fi
tar -C "$SCRATCH" -xzf "$TARBALL"
export DIST_DIR="$SCRATCH/numa-disruptor"
HERE="$DIST_DIR/scripts-hpc"

sync_results() {
  mkdir -p "$OUT_DIR" "$RESULTS_DIR"
  # results are written straight to $OUT_DIR (persistent); keep a per-job copy of logs for the record
  cp -f "$SUBMIT_DIR"/numa-disruptor-"${SLURM_JOB_ID:-}".out "$RESULTS_DIR"/ 2>/dev/null || true
  sync
}
on_signal() { echo "[$(date +%T)] caught Slurm warning signal; allocation ends soon, results already flushed per trial"; sync_results; }
trap on_signal USR1 TERM
trap sync_results EXIT

# --- validate, then gate on correctness
"$HERE/environment-check.sh" || { echo "environment check failed, not benchmarking" >&2; exit 1; }
SMOKE_OUT="$RESULTS_DIR/smoke" "$HERE/smoke-test.sh" || { echo "smoke test failed, not benchmarking" >&2; exit 1; }

# --- benchmark under the remaining budget. srun keeps Slurm's cpuset/cgroup; we pin threads ourselves, so no extra binding.
elapsed=$(( $(date +%s) - START ))
export BUDGET_SECONDS=$(( BUDGET_SECONDS - elapsed ))
echo "[$(date +%T)] starting matrix '$MATRIX' with ${BUDGET_SECONDS}s budget (after ${elapsed}s of setup)"
srun --ntasks=1 --cpus-per-task="${SLURM_CPUS_PER_TASK:-1}" ${SRUN_BIND_ARGS:---cpu-bind=none --mem-bind=none} \
  "$HERE/run-locality-matrix.sh"
rc=$?

sync_results
echo "[$(date +%T)] done rc=$rc total $(( $(date +%s) - START ))s; results in $OUT_DIR"
echo "If the matrix is incomplete, submit the same command again: finished trials are skipped."
exit $rc
