#!/usr/bin/env bash
# Exercise the HPC deployment scripts on a Linux machine WITHOUT Slurm, using the real distribution tarball:
# unpack, environment check, smoke test, the Slurm script's control flow (with a fake srun), resume, time-budget
# enforcement, and result collection/verification. This tests the scripts, not the cluster: real Slurm binding,
# multi-node NUMA and hardware counters can only be checked on the cluster.
#
#   JAVA_HOME=/path/to/jdk25 scripts/local/test-deploy.sh dist/numa-disruptor-<commit>.tar.gz
set -euo pipefail
tarball="$(readlink -f "${1:?usage: $0 <tarball>}")"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 25}"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
here="$(cd "$(dirname "$0")" && pwd)"
export NUMA_BENCH_ALLOW_LOCAL=1
JARGS="-Xms1g -Xmx1g -XX:+UseParallelGC --enable-native-access=ALL-UNNAMED"

mkdir -p "$work/dist" "$work/shim"
cp "$tarball" "$work/dist/"; cp "${tarball}.sha256" "$work/dist/"
cp "$here/fake-srun.sh" "$work/shim/srun"; chmod +x "$work/shim/srun"
tar -C "$work" -xzf "$tarball"
DIST="$work/numa-disruptor"; B="$DIST/benchmarks/bin/benchmarks"

echo "### 1. benchmark.slurm under a fake srun (smoke matrix)"
( cd "$work" && PATH="$work/shim:$PATH" SLURM_JOB_ID=4242 SLURM_SUBMIT_DIR="$work" SLURM_CPUS_PER_TASK=4 \
  MATRIX=smoke TRIALS=1 WARMUP=1 MEASURE=1 BUDGET_SECONDS=300 VERIFY_EVENTS=50000 JVM_ARGS="$JARGS" \
  DIST_TARBALL="$tarball" bash "$DIST/scripts-hpc/benchmark.slurm" ) | tail -4

echo "### 2. login-node guard: must refuse outside Slurm"
guard_out="$( ( unset NUMA_BENCH_ALLOW_LOCAL SLURM_JOB_ID; DIST_DIR="$DIST" "$DIST/scripts-hpc/run-locality-matrix.sh" ) 2>&1 || true )"
if grep -q Refusing <<<"$guard_out"; then echo "guard OK"; else echo "GUARD FAILED: $guard_out"; exit 1; fi

echo "### 3. resume skips finished trials"
out="$work/results/resume"
"$B" run --matrix smoke --trials 1 --out "$out" --jvm-args "$JARGS" | tail -1
second="$("$B" run --matrix smoke --trials 1 --resume --out "$out" --jvm-args "$JARGS" | tail -1)"
echo "$second"; grep -q '"ran":0' <<<"$second" && grep -q '"alreadyDone":5' <<<"$second" || { echo "RESUME FAILED"; exit 1; }

echo "### 4. time budget stops launching new trials (100 s budget, 16 s trials need 60 s margin)"
bud="$work/results/budget"
first="$("$B" run --matrix locality --trials 1 --warmup 1 --measure 1 --budget-seconds 100 --out "$bud" --jvm-args "$JARGS" | tail -1)"
echo "$first"; grep -q '"budgetExhausted":true' <<<"$first" || { echo "BUDGET NOT ENFORCED"; exit 1; }
nrec="$(wc -l < "$bud/results.jsonl")"; echo "records written before budget stop: $nrec"
[[ "$nrec" -ge 1 ]] || { echo "nothing was recorded"; exit 1; }

echo "### 5. collect + verify"
"$DIST/scripts-hpc/collect-results.sh" "$work/archive.tar.gz" "$bud" "$out"
"$DIST/scripts-hpc/verify-results.sh" "$work/archive.tar.gz"

echo "### 6. verify-results must reject a corrupted checksum"
mkdir -p "$work/x" && tar -C "$work/x" -xzf "$work/archive.tar.gz"
echo '{"id":"tampered"}' >> "$(find "$work/x" -name results.jsonl | head -1)"
if "$DIST/scripts-hpc/verify-results.sh" "$work/x/results" >/dev/null 2>&1; then echo "TAMPERING NOT DETECTED"; exit 1; else echo "tampering detected OK"; fi
echo "ALL DEPLOYMENT SCRIPT CHECKS PASSED"
