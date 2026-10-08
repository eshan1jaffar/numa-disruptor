#!/usr/bin/env bash
# Report what this allocation actually provides and fail if it cannot run the benchmarks safely.
# Run INSIDE a Slurm allocation (sbatch/srun/salloc), on the compute node.
#
#   DIST_DIR   unpacked distribution (default: $PWD/dist/numa-disruptor)
#   JAVA_HOME  JDK 25 (or `module load` one before calling)
#   NUMA_BENCH_ALLOW_LOCAL=1  allow running outside Slurm (developer machine only; never on a login node)
set -euo pipefail

DIST_DIR="${DIST_DIR:-$PWD/dist/numa-disruptor}"
fail=0
note() { printf '%s\n' "$*"; }
bad()  { printf 'ERROR: %s\n' "$*" >&2; fail=1; }

note "== host =="
note "hostname: $(hostname)  kernel: $(uname -r)  date: $(date -Is)"

if [[ -z "${SLURM_JOB_ID:-}" ]]; then
  if [[ "${NUMA_BENCH_ALLOW_LOCAL:-0}" != "1" ]]; then
    bad "not inside a Slurm allocation (SLURM_JOB_ID unset). Refusing: benchmarks must not run on a login node. Use sbatch scripts/hpc/benchmark.slurm."
  else
    note "WARNING: running outside Slurm because NUMA_BENCH_ALLOW_LOCAL=1"
  fi
else
  note "== slurm =="
  env | grep -E '^SLURM_(JOB_ID|JOB_NAME|JOB_PARTITION|JOB_NODELIST|CPUS_ON_NODE|CPUS_PER_TASK|JOB_CPUS_PER_NODE|MEM_PER_NODE|MEM_PER_CPU|CPU_BIND|MEM_BIND|NTASKS|STEP_ID)' | sort || true
  if command -v squeue >/dev/null 2>&1; then
    note "time limit / remaining: $(squeue -h -j "$SLURM_JOB_ID" -o '%l / %L' 2>/dev/null || echo unknown)"
  fi
fi

note "== allowed resources (what the kernel will let this process use) =="
grep -E '^(Cpus_allowed_list|Mems_allowed_list):' /proc/self/status || bad "cannot read /proc/self/status"
cpus_allowed="$(awk '/^Cpus_allowed_list:/{print $2}' /proc/self/status)"
mems_allowed="$(awk '/^Mems_allowed_list:/{print $2}' /proc/self/status)"
command -v taskset >/dev/null && taskset -cp $$ || true
command -v numactl >/dev/null && numactl --show || note "numactl not installed (optional)"
[[ -r /sys/fs/cgroup/cpu.max ]] && note "cgroup cpu.max: $(cat /sys/fs/cgroup/cpu.max)"
[[ -r /sys/fs/cgroup/cpuset.cpus.effective ]] && note "cgroup cpuset.cpus.effective: $(cat /sys/fs/cgroup/cpuset.cpus.effective)"
[[ -r /sys/fs/cgroup/cpuset.mems.effective ]] && note "cgroup cpuset.mems.effective: $(cat /sys/fs/cgroup/cpuset.mems.effective)"

note "== NUMA/kernel knobs that affect results =="
for f in /proc/sys/kernel/numa_balancing /sys/kernel/mm/transparent_hugepage/enabled \
         /proc/sys/kernel/perf_event_paranoid /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor; do
  [[ -r $f ]] && note "$f = $(cat "$f")" || note "$f = (unreadable)"
done
ls /sys/devices/system/node 2>/dev/null | grep -c '^node' | xargs -I{} echo "NUMA nodes visible: {}"
if { ldconfig -p 2>/dev/null || /sbin/ldconfig -p 2>/dev/null; } | grep -q libnuma; then note "libnuma: present"; else note "libnuma: NOT found (off-heap NUMA payload experiments will be skipped)"; fi
command -v perf >/dev/null && note "perf: $(command -v perf)" || note "perf: not installed (no hardware counters)"

note "== java =="
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then JAVA="$JAVA_HOME/bin/java"; else JAVA="$(command -v java || true)"; fi
if [[ -z "$JAVA" ]]; then
  bad "no java found: set JAVA_HOME or load a JDK 25 module"
else
  ver="$("$JAVA" -version 2>&1 | head -1)"
  note "$JAVA: $ver"
  major="$("$JAVA" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/{print $2}')"
  [[ "${major:-0}" -ge 25 ]] || bad "JDK 25+ required, found specification version '${major:-?}'"
fi

note "== distribution =="
if [[ -x "$DIST_DIR/benchmarks/bin/benchmarks" ]]; then
  note "found $DIST_DIR"
  "$DIST_DIR/benchmarks/bin/benchmarks" topology || bad "topology discovery failed"
else
  bad "distribution not found at $DIST_DIR (build with scripts/hpc/build-offline.sh and unpack it there)"
fi

note "allowedCpus=$cpus_allowed allowedMemNodes=$mems_allowed"
if [[ $fail -ne 0 ]]; then
  note "environment check FAILED"
  exit 1
fi
note "environment check passed"
