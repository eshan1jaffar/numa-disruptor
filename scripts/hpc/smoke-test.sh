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
# SMOKE_ALLOW_SKIPS=1 tolerates skipped pinned variants (tiny cpuset / no pinning); default is to fail on them
vflags=(); [[ "${SMOKE_ALLOW_SKIPS:-0}" == "1" ]] && vflags=(--allow-skip-pinned)
"$BENCH" verify --events "${VERIFY_EVENTS:-300000}" ${vflags[@]+"${vflags[@]}"}

echo "== harness smoke matrix (1 trial each) =="
"$BENCH" run --matrix smoke --trials 1 --out "$OUT" \
  --jvm-args "${JVM_ARGS:--Xms2g -Xmx2g -Xmn1g -XX:+UseParallelGC -XX:+UseNUMA --enable-native-access=ALL-UNNAMED}"
bad="$(grep -c '"status":"failed"' "$OUT/results.jsonl" || true)"
if [[ "$bad" != "0" ]]; then echo "smoke FAILED: $bad failed trial(s) in $OUT/results.jsonl" >&2; exit 1; fi
# an all-skipped variant (pinning unsupported, cpuset too small) must not pass: need >= 1 ok trial per variant
if [[ "${SMOKE_ALLOW_SKIPS:-0}" != "1" ]]; then
  missing=""
  for v in V0 V1 V2 V3; do
    grep '"status":"ok"' "$OUT/results.jsonl" | grep -q "\"variant\":\"$v\"" || missing="$missing $v"
  done
  if [[ -n "$missing" ]]; then
    echo "smoke FAILED: no ok trial for variant(s):$missing (skipped? see $OUT/results.jsonl; SMOKE_ALLOW_SKIPS=1 overrides)" >&2; exit 1
  fi
fi
echo "smoke test passed (results in $OUT)"
