#!/usr/bin/env bash
# Integrity and sanity check of collected results (works on the cluster or locally).
#   scripts/hpc/verify-results.sh <results-dir | archive.tar.gz>
# Fails if: a manifest checksum mismatches, a record is not valid JSON or lacks metadata, a trial reports a correctness failure,
# or CSV and JSONL disagree on the number of records. Skipped trials are reported, not failed.
set -euo pipefail
target="${1:?usage: $0 <results-dir|archive.tar.gz>}"
if [[ -f "$target" && "$target" == *.tar.gz ]]; then
  work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
  tar -C "$work" -xzf "$target"; dir="$work/results"
else
  dir="$target"
fi

status=0
if [[ -f "$dir/MANIFEST.sha256" ]]; then
  ( cd "$dir" && sha256sum -c MANIFEST.sha256 --quiet ) || { echo "MANIFEST checksum mismatch" >&2; status=1; }
fi

total=0; ok=0; skipped=0; failed=0
while IFS= read -r f; do
  d="$(dirname "$f")"
  n=$(wc -l < "$f"); c=$(( $(wc -l < "$d/results.csv") - 1 ))
  [[ "$n" -eq "$c" ]] || { echo "$d: $n JSONL records but $c CSV rows" >&2; status=1; }
  total=$((total + n))
  ok=$((ok + $(grep -c '"status":"ok"' "$f" || true)))
  skipped=$((skipped + $(grep -c '"status":"skipped"' "$f" || true)))
  failed=$((failed + $(grep -c '"status":"failed"' "$f" || true)))
  if command -v python3 >/dev/null 2>&1 && python3 -c 'import json' >/dev/null 2>&1; then
    python3 - "$f" <<'PY' || status=1
import json, sys
bad = 0
for i, line in enumerate(open(sys.argv[1]), 1):
    try:
        r = json.loads(line)
    except Exception as e:
        print(f"{sys.argv[1]}:{i}: invalid JSON: {e}", file=sys.stderr); bad += 1; continue
    if r.get("status") == "skipped":
        continue
    md = r.get("metadata") or {}
    for k in ("gitCommit", "timestamp", "kernel", "jvm", "allowedCpus", "topology"):
        if k not in md:
            print(f"{sys.argv[1]}:{i}: metadata missing '{k}'", file=sys.stderr); bad += 1
sys.exit(1 if bad else 0)
PY
  fi
done < <(find "$dir" -name results.jsonl | sort)

echo "records=$total ok=$ok skipped=$skipped failed=$failed"
if [[ $failed -gt 0 ]]; then
  echo "FAILED trials present (correctness violations or crashes):" >&2
  find "$dir" -name results.jsonl -exec grep -h '"status":"failed"' {} + | sed -E 's/.*"id":"([^"]*)","trial":([0-9]+).*"reason":"([^"]{0,200}).*/  \1 trial \2: \3/' >&2
  status=1
fi
[[ $total -gt 0 ]] || { echo "no records found" >&2; status=1; }
exit $status
