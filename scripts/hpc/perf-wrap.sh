#!/usr/bin/env bash
# Run a command under `perf stat` when permitted; otherwise run it unwrapped. Never fails the command because of perf.
#   perf-wrap.sh <outdir> -- <command...>
# Writes: perf-stat.csv + perf-events.txt (perf used) or perf-unavailable.txt (perf not used). Exits with the command's exit code.
# Counters cover the whole process tree of <command> (not any single trial inside it).
set -u
if [[ $# -lt 3 || "$2" != "--" ]]; then echo "usage: $0 <outdir> -- <command...>" >&2; exit 2; fi
out="$1"; shift 2
mkdir -p "$out" || { echo "perf-wrap: cannot create $out; running unwrapped" >&2; exec "$@"; }

why=""
if ! command -v perf >/dev/null 2>&1; then
  why="perf binary not found on PATH"
else
  para=$(cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null || echo unknown)
  if [[ "$(id -u)" != "0" && ( ! "$para" =~ ^-?[0-9]+$ || "$para" -gt 2 ) ]]; then
    why="perf_event_paranoid=$para (need <= 2, or root)"
  fi
fi
if [[ -n "$why" ]]; then
  echo "perf not used: $why" > "$out/perf-unavailable.txt"
  echo "perf-wrap: $why; running unwrapped" >&2
  exec "$@"
fi

# Candidate events; extra remote/far DRAM events are added from `perf list` when present.
cand=(cycles instructions cache-misses node-loads node-load-misses node-stores node-store-misses)
if grep -q 'AuthenticAMD' /proc/cpuinfo 2>/dev/null; then cand+=(ls_dmnd_fills_from_sys.dram_io_far); fi
while read -r e; do [[ -n "$e" ]] && cand+=("$e"); done < <(
  perf list 2>/dev/null | grep -oE '[A-Za-z0-9_.-]*(remote|far)[A-Za-z0-9_.-]*' | grep -iE 'dram|mem|node' | sort -u | head -8)

used=()
declare -A seen
for e in "${cand[@]}"; do
  [[ -n "${seen[$e]:-}" ]] && continue
  seen[$e]=1
  probe=$(perf stat -x, -e "$e" -- sleep 0.1 2>&1) || continue
  grep -qiE 'not supported|not counted|invalid|cannot find' <<<"$probe" || used+=("$e")
done
if [[ ${#used[@]} -eq 0 ]]; then
  echo "perf present and permitted, but no probed event worked on this host/VM" > "$out/perf-unavailable.txt"
  exec "$@"
fi
printf '%s\n' "${used[@]}" > "$out/perf-events.txt"
evs=$(IFS=,; echo "${used[*]}")
perf stat -x, -e "$evs" -o "$out/perf-stat.csv" -- "$@"
