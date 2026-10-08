#!/usr/bin/env bash
# Short correctness gate run at the start of every allocation (about 1-2 minutes). Fails fast so a broken environment does not
# burn the one-hour slot on meaningless numbers.
#   1. environment check            2. all four variants deliver exactly the reference orders (fixed-count run)
#   3. one trial of each variant through the real harness
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
export DIST_DIR="${DIST_DIR:-$PWD/dist/numa-disruptor}"
BENCH="$DIST_DIR/benchmarks/bin/benchmarks"
OUT="${SMOKE_OUT:-${TMPDIR:-/tmp}/numa-disruptor-smoke-$$}"

"$here/environment-check.sh"

echo "== equivalence: V0..V3 vs independent reference =="
"$BENCH" verify --events "${VERIFY_EVENTS:-300000}"

echo "== harness smoke matrix (1 trial each) =="
"$BENCH" run --matrix smoke --trials 1 --out "$OUT" \
  --jvm-args "${JVM_ARGS:--Xms2g -Xmx2g -Xmn1g -XX:+UseParallelGC -XX:+UseNUMA --enable-native-access=ALL-UNNAMED}"
bad="$(grep -c '"status":"failed"' "$OUT/results.jsonl" || true)"
if [[ "$bad" != "0" ]]; then echo "smoke FAILED: $bad failed trial(s) in $OUT/results.jsonl" >&2; exit 1; fi
echo "smoke test passed (results in $OUT)"
