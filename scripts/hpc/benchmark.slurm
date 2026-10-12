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
#   BUDGET_SECONDS  override the time budget (default: min(real job limit, 3600) - 420 s)
set -uo pipefail
set -m   # background children get their own process group, so a signal can be delivered to the whole tree

START=$(date +%s)
SAFETY_SECONDS=420          # leave time for the final sync and for the last trial to be killed cleanly

# real time limit of this job (sbatch --time= overrides the #SBATCH line); squeue %l is D-HH:MM:SS, HH:MM:SS, MM:SS, MM,
# UNLIMITED, ... Anything unparsable falls back to the 1 h default.
slurm_limit_seconds() {
  local t d=0 h=0 m=0 s=0
  t="$(squeue -h -j "${SLURM_JOB_ID:-}" -o %l 2>/dev/null | head -1 | tr -d '[:space:]')" || true
  [[ "$t" =~ ^([0-9]+-)?[0-9]+(:[0-9]+){0,2}$ ]] || { echo 3600; return; }
  case "$t" in
    *-*) d=${t%%-*}; IFS=: read -r h m s <<<"${t#*-}" ;;   # D-HH[:MM[:SS]]
    *:*:*) IFS=: read -r h m s <<<"$t" ;;
    *:*) IFS=: read -r m s <<<"$t" ;;
    *) m=$t ;;
  esac
  echo $(( 10#${d:-0} * 86400 + 10#${h:-0} * 3600 + 10#${m:-0} * 60 + 10#${s:-0} ))
}
LIMIT_SECONDS="$(slurm_limit_seconds)"
POLICY_SECONDS=$(( LIMIT_SECONDS < 3600 ? LIMIT_SECONDS : 3600 ))   # never plan beyond the 1 h policy unless BUDGET_SECONDS is given
default_budget=$(( POLICY_SECONDS - SAFETY_SECONDS )); (( default_budget >= 60 )) || default_budget=60
export BUDGET_SECONDS="${BUDGET_SECONDS:-$default_budget}"
echo "[$(date +%T)] job time limit ${LIMIT_SECONDS}s, budget ${BUDGET_SECONDS}s"

SUBMIT_DIR="${SLURM_SUBMIT_DIR:-$PWD}"
cd "$SUBMIT_DIR"
RESULTS_DIR="${RESULTS_DIR:-$SUBMIT_DIR/results/job-${SLURM_JOB_ID:-local}}"
MATRIX="${MATRIX:-all}"
# the matrix resumes across allocations, so results of one matrix always go to one directory
OUT_DIR="${OUT_DIR:-$SUBMIT_DIR/results/$MATRIX}"
export OUT_DIR MATRIX

if [[ -n "${JAVA_MODULE:-}" ]]; then
  if ! declare -F module >/dev/null 2>&1; then
    for init in /etc/profile.d/modules.sh /etc/profile.d/lmod.sh /usr/share/lmod/lmod/init/bash /usr/share/Modules/init/bash; do
      [[ -r $init ]] && { source "$init" || true; declare -F module >/dev/null 2>&1 && break; }
    done
  fi
  if ! declare -F module >/dev/null 2>&1; then echo "JAVA_MODULE=$JAVA_MODULE requested but the 'module' command is not available" >&2; exit 1; fi
  module load "$JAVA_MODULE" || { echo "module load $JAVA_MODULE FAILED" >&2; exit 1; }
fi

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
cleanup() { sync_results; rm -rf "$SCRATCH"; }
CHILD=
# bash defers traps until a foreground command returns, so every long command runs in the background under `wait`
run_fg() {
  local rc
  "$@" & CHILD=$!
  while :; do wait "$CHILD"; rc=$?; kill -0 "$CHILD" 2>/dev/null || break; done   # loop: a trapped signal interrupts wait
  CHILD=; return $rc
}
on_usr1() { echo "[$(date +%T)] caught Slurm warning signal; allocation ends soon, results already flushed per trial"; sync_results; }
on_term() {
  echo "[$(date +%T)] caught TERM; stopping the run"
  if [[ -n "$CHILD" ]]; then
    kill -TERM -- "-$CHILD" 2>/dev/null
    for _ in $(seq "${TERM_GRACE_SECONDS:-30}"); do kill -0 "$CHILD" 2>/dev/null || break; sleep 1; done
    kill -KILL -- "-$CHILD" 2>/dev/null
  fi
  exit 143   # EXIT trap does the final sync and removes the scratch copy
}
trap on_usr1 USR1
trap on_term TERM
trap cleanup EXIT

# --- validate, then gate on correctness
run_fg "$HERE/environment-check.sh" || { echo "environment check failed, not benchmarking" >&2; exit 1; }
SMOKE_OUT="$RESULTS_DIR/smoke" run_fg "$HERE/smoke-test.sh" || { echo "smoke test failed, not benchmarking" >&2; exit 1; }

# --- benchmark under the remaining budget. srun keeps Slurm's cpuset/cgroup; we pin threads ourselves, so no extra binding.
elapsed=$(( $(date +%s) - START ))
export BUDGET_SECONDS=$(( BUDGET_SECONDS - elapsed )); (( BUDGET_SECONDS >= 0 )) || BUDGET_SECONDS=0
echo "[$(date +%T)] starting matrix '$MATRIX' with ${BUDGET_SECONDS}s budget (after ${elapsed}s of setup)"
# the step inherits whatever allocation you requested: give it every CPU the allocation has on this node (never default to 1)
STEP_CPUS="${SLURM_CPUS_PER_TASK:-${SLURM_CPUS_ON_NODE:-}}"
cpu_args=(); [[ -n "$STEP_CPUS" ]] && cpu_args=(--cpus-per-task="$STEP_CPUS")
run_fg srun --ntasks=1 ${cpu_args[@]+"${cpu_args[@]}"} ${SRUN_BIND_ARGS:---cpu-bind=none --mem-bind=none} \
  "$HERE/run-locality-matrix.sh"
rc=$?

sync_results
echo "[$(date +%T)] done rc=$rc total $(( $(date +%s) - START ))s; results in $OUT_DIR"
echo "If the matrix is incomplete, submit the same command again: finished trials are skipped."
exit $rc
