#!/usr/bin/env bash
# Gather result directories (from one or several allocations) into a single archive for transfer to your machine.
#   scripts/hpc/collect-results.sh <out.tar.gz> <results-dir> [<results-dir> ...]
# Adds a MANIFEST with sha256 sums. Run on the cluster; then `scp` the archive to your machine and run verify-results.sh there.
set -euo pipefail
if [[ $# -lt 2 ]]; then echo "usage: $0 <out.tar.gz> <results-dir>..." >&2; exit 2; fi
out="$1"; shift
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/results"
i=0
for d in "$@"; do
  [[ -f "$d/results.jsonl" ]] || { echo "skipping $d: no results.jsonl" >&2; continue; }
  dest="$tmp/results/$(basename "$d")"
  [[ -e "$dest" ]] && dest="${dest}-$i"
  mkdir -p "$dest"; cp "$d"/results.jsonl "$d"/results.csv "$dest"/; cp "$d"/run-summary-*.json "$dest"/ 2>/dev/null || true
  i=$((i+1))
done
( cd "$tmp/results" && find . -type f ! -name MANIFEST.sha256 -print0 | sort -z | xargs -0 sha256sum > MANIFEST.sha256 )
tar -C "$tmp" -czf "$out" results
echo "wrote $out ($(find "$tmp/results" -name results.jsonl | wc -l) result set(s), $(cat "$tmp"/results/*/results.jsonl | wc -l) record(s))"
