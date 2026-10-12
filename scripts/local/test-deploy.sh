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
echo "### 7. benchmark.slurm: time-limit parsing (squeue %l formats)"
eval "$(sed -n '/^slurm_limit_seconds()/,/^}/p' "$DIST/scripts-hpc/benchmark.slurm")"
check_limit() { SQ="$1"; got="$(squeue() { echo "$SQ"; }; slurm_limit_seconds)"; [[ "$got" == "$2" ]] || { echo "LIMIT PARSE FAILED: '$1' -> $got, want $2"; exit 1; }; }
check_limit 1-02:03:04 93784; check_limit 02:03:04 7384; check_limit 03:04 184; check_limit 45 2700; check_limit 1-12 129600
check_limit 2-00:00:00 172800; check_limit UNLIMITED 3600; check_limit INVALID 3600; check_limit "" 3600
echo "limit parsing OK"

echo "### 8. smoke gate: all-skipped variant must fail unless SMOKE_ALLOW_SKIPS=1"
fake="$work/fakedist/benchmarks/bin"; mkdir -p "$fake"
cat > "$fake/benchmarks" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$(dirname "$0")/calls.log"
if [[ "$1" == run ]]; then
  while [[ $# -gt 0 && "$1" != --out ]]; do shift; done
  mkdir -p "$2"
  { for v in V0 V1 V2; do echo "{\"status\":\"ok\",\"config\":{\"variant\":\"$v\"}}"; done
    echo '{"status":"skipped","config":{"variant":"V3"}}'; } > "$2/results.jsonl"
fi
exit 0
EOF
chmod +x "$fake/benchmarks"
smoke_err="$( ( DIST_DIR="$work/fakedist" SMOKE_OUT="$work/fs1" "$DIST/scripts-hpc/smoke-test.sh" ) 2>&1 >/dev/null || true )"
grep -q 'no ok trial for variant(s): V3' <<<"$smoke_err" || { echo "SMOKE GATE FAILED TO CATCH SKIPS: $smoke_err"; exit 1; }
! grep -q -- --allow-skip-pinned "$fake/calls.log" || { echo "allow-skip-pinned passed without SMOKE_ALLOW_SKIPS"; exit 1; }
DIST_DIR="$work/fakedist" SMOKE_OUT="$work/fs2" SMOKE_ALLOW_SKIPS=1 "$DIST/scripts-hpc/smoke-test.sh" | tail -1
grep -q -- --allow-skip-pinned "$fake/calls.log" || { echo "--allow-skip-pinned not forwarded"; exit 1; }
echo "smoke gate OK"

echo "### 9. environment-check: java 1.8 spec version fails cleanly; missing sysfs node dir does not abort; CSV with quoted newlines"
mkdir -p "$work/jdk8/bin"
printf '#!/usr/bin/env bash\necho "    java.specification.version = 1.8" >&2\necho "openjdk version \\"1.8.0\\"" >&2\n' > "$work/jdk8/bin/java"; chmod +x "$work/jdk8/bin/java"
env_out="$( ( JAVA_HOME="$work/jdk8" DIST_DIR="$DIST" "$DIST/scripts-hpc/environment-check.sh" ) 2>&1 || true )"
grep -q "specification version '8'" <<<"$env_out" && grep -q 'environment check FAILED' <<<"$env_out" || { echo "ENV CHECK JAVA PARSE FAILED: $env_out"; exit 1; }
n0="$( set -euo pipefail; { ls /nonexistent-node-dir 2>/dev/null || true; } | grep -c '^node' || true )"; [[ "$n0" == 0 ]] || { echo "node count idiom wrong: $n0"; exit 1; }
mkdir -p "$work/csvq"
printf '{"status":"skipped","id":"a"}\n{"status":"skipped","id":"b"}\n' > "$work/csvq/results.jsonl"
printf 'id,status,reason\na,skipped,"line one\nline two"\nb,skipped,x\n' > "$work/csvq/results.csv"
"$DIST/scripts-hpc/verify-results.sh" "$work/csvq" || { echo "CSV QUOTED NEWLINE MISCOUNTED"; exit 1; }

echo "### 10. benchmark.slurm: SIGTERM during the run still syncs, cleans scratch and exits"
sig="$work/sig"; mkdir -p "$sig"; : > "$sig/numa-disruptor-4243.out"
printf '#!/usr/bin/env bash\necho 30:00\n' > "$work/shim/squeue"; chmod +x "$work/shim/squeue"
( cd "$sig" && exec env PATH="$work/shim:$PATH" SLURM_JOB_ID=4243 SLURM_SUBMIT_DIR="$sig" SLURM_TMPDIR="$sig/tmp" SLURM_CPUS_PER_TASK=4 \
  MATRIX=locality TRIALS=1 WARMUP=1 MEASURE=1 TERM_GRACE_SECONDS=10 VERIFY_EVENTS=50000 JVM_ARGS="$JARGS" \
  DIST_TARBALL="$tarball" bash "$DIST/scripts-hpc/benchmark.slurm" ) > "$sig/log" 2>&1 &
spid=$!
for _ in $(seq 180); do grep -q 'starting matrix' "$sig/log" 2>/dev/null && break; sleep 1; done
grep -q 'starting matrix' "$sig/log" || { echo "run never started"; cat "$sig/log"; kill "$spid" 2>/dev/null; exit 1; }
sleep 8; kill -TERM "$spid"
for _ in $(seq 60); do kill -0 "$spid" 2>/dev/null || break; sleep 1; done
if kill -0 "$spid" 2>/dev/null; then echo "SCRIPT DID NOT EXIT AFTER SIGTERM"; kill -9 "$spid"; exit 1; fi
srcrc=0; wait "$spid" || srcrc=$?
grep -E 'job time limit|starting matrix|caught TERM' "$sig/log"
echo "exit code: $srcrc"
[[ "$srcrc" == 143 ]] || { echo "unexpected exit code"; exit 1; }
grep -q 'job time limit 1800s, budget 1380s' "$sig/log" || { echo "LIMIT NOT READ FROM squeue"; exit 1; }
[[ -f "$sig/results/job-4243/numa-disruptor-4243.out" ]] || { echo "FINAL SYNC DID NOT HAPPEN"; exit 1; }
[[ ! -e "$sig/tmp/numa-disruptor-4243" ]] || { echo "SCRATCH NOT REMOVED"; exit 1; }
! pgrep -f "$sig/tmp" >/dev/null || { echo "ORPHAN PROCESSES LEFT"; exit 1; }
echo "sigterm handling OK"
echo "ALL DEPLOYMENT SCRIPT CHECKS PASSED"
