#!/usr/bin/env bash
# One file to go from "empty home directory on the cluster" to "benchmark running": downloads JDK 25, pulls the repo from
# GitHub, builds it, then runs the checks and the benchmark matrix inside your Slurm allocation.
#
#   epyc-run.sh stage    login node OK (network needed): JDK + clone + build only, nothing heavy runs. Do this first if compute
#                        nodes have no internet.
#   epyc-run.sh run      INSIDE an allocation (salloc/sbatch): stage if needed, environment check, smoke gate, then the matrix.
#                        This is the default when SLURM_JOB_ID is set.
#   epyc-run.sh submit   from the login node: sbatch this script as a 1 h job (SBATCH_ARGS="-p <partition> -A <account>").
#
# Typical interactive session:
#   salloc -N1 -c64 -t 1:00:00 -p <partition> -A <account>      # then, on the compute node:
#   GH_TOKEN=<token> bash epyc-run.sh
#
# Environment (all optional):
#   GH_TOKEN      GitHub token with read access to the repo (needed while it is private). Used only for git's HTTP header for
#                 this command; never written to disk or to .git/config.
#   REPO_URL      default https://github.com/eshan1jaffar/numa-disruptor.git        REF  branch/tag/commit (default main)
#   NUMA_HOME     where everything lives (default $HOME/numa-disruptor-epyc): jdk/, src/, gradle-home/, work/
#   MATRIX        smoke | locality | scale | sweeps | all (default all)             TRIALS WARMUP MEASURE JVM_ARGS
#   BUDGET_SECONDS  override the benchmark time budget (default: real job limit - 420 s)
#   NUMA_OFFLINE=1  never touch the network; require everything already staged
#   JAVA_HOME     an existing JDK >= 25 to use instead of downloading one
# Results: $NUMA_HOME/work/results/<matrix>/ (results.jsonl, results.csv), resumable by running this again.
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/eshan1jaffar/numa-disruptor.git}"
REF="${REF:-main}"
NUMA_HOME="${NUMA_HOME:-$HOME/numa-disruptor-epyc}"
JDK_DIR="$NUMA_HOME/jdk"
SRC_DIR="$NUMA_HOME/src"
WORK_DIR="$NUMA_HOME/work"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$NUMA_HOME/gradle-home}"

log() { printf '[%s] %s\n' "$(date +%T)" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
offline() { [[ "${NUMA_OFFLINE:-0}" == "1" ]]; }

fetch() { # fetch <url> <outfile>
  if command -v curl >/dev/null 2>&1; then curl -fsSL --retry 3 -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then wget -q -O "$2" "$1"
  else die "need curl or wget"; fi
}

java_major() { # java_major <java binary>
  "$1" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/{print $2}' | sed -E 's/^1\.//; s/\..*//' | tr -d '[:space:]'
}

ensure_jdk() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" && "$(java_major "$JAVA_HOME/bin/java")" -ge 25 ]] 2>/dev/null; then
    log "using JAVA_HOME=$JAVA_HOME"; return
  fi
  if [[ -x "$JDK_DIR/bin/java" && "$(java_major "$JDK_DIR/bin/java")" -ge 25 ]] 2>/dev/null; then
    export JAVA_HOME="$JDK_DIR"; log "using staged JDK $JDK_DIR"; return
  fi
  offline && die "no JDK 25 staged in $JDK_DIR and NUMA_OFFLINE=1"
  local arch; case "$(uname -m)" in x86_64|amd64) arch=x64 ;; aarch64|arm64) arch=aarch64 ;; *) die "unsupported CPU $(uname -m)" ;; esac
  local base="https://api.adoptium.net/v3"
  local tmp; tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' RETURN
  log "downloading Temurin JDK 25 ($arch) from Adoptium"
  fetch "$base/assets/latest/25/hotspot?architecture=$arch&image_type=jdk&os=linux&vendor=eclipse" "$tmp/assets.json"
  local sum; sum="$(grep -o '"checksum"[[:space:]]*:[[:space:]]*"[0-9a-f]\{64\}"' "$tmp/assets.json" | head -1 | grep -o '[0-9a-f]\{64\}')"
  [[ -n "$sum" ]] || die "could not read the JDK checksum from Adoptium's metadata"
  fetch "$base/binary/latest/25/ga/linux/$arch/jdk/hotspot/normal/eclipse" "$tmp/jdk.tar.gz"
  [[ "$(sha256sum "$tmp/jdk.tar.gz" | cut -d' ' -f1)" == "$sum" ]] || die "JDK checksum mismatch (expected $sum)"
  rm -rf "$JDK_DIR"; mkdir -p "$JDK_DIR"
  tar -xzf "$tmp/jdk.tar.gz" -C "$JDK_DIR" --strip-components=1
  export JAVA_HOME="$JDK_DIR"
  log "installed $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
}

git_auth() { # prints git options for the token, if any (kept off the command line of other users: it is an env-expanded arg)
  if [[ -n "${GH_TOKEN:-}" ]]; then
    printf '%s\n' "-c" "http.extraHeader=Authorization: Basic $(printf 'x-access-token:%s' "$GH_TOKEN" | base64 | tr -d '\n')"
  fi
}

ensure_repo() {
  command -v git >/dev/null 2>&1 || die "git not found (try: module load git)"
  if offline; then [[ -d "$SRC_DIR/.git" ]] || die "no staged repo in $SRC_DIR and NUMA_OFFLINE=1"; return; fi
  mapfile -t auth < <(git_auth)
  if [[ -d "$SRC_DIR/.git" ]]; then
    log "updating $SRC_DIR to $REF"
    git -C "$SRC_DIR" remote set-url origin "$REPO_URL"
    git -C "$SRC_DIR" ${auth[@]+"${auth[@]}"} fetch --quiet origin "$REF"
  else
    log "cloning $REPO_URL"
    mkdir -p "$SRC_DIR"; git -C "$SRC_DIR" init --quiet
    git -C "$SRC_DIR" remote add origin "$REPO_URL"
    git -C "$SRC_DIR" ${auth[@]+"${auth[@]}"} fetch --quiet origin "$REF" \
      || die "git fetch failed. If the repository is private set GH_TOKEN (a token with read access); if there is no internet on this node run '$0 stage' on the login node."
  fi
  git -C "$SRC_DIR" checkout --quiet -f FETCH_HEAD
  log "source at $(git -C "$SRC_DIR" rev-parse --short=12 HEAD)"
}

ensure_build() {
  local commit; commit="$(git -C "$SRC_DIR" rev-parse --short=12 HEAD)"
  local tarball; tarball="$(ls "$SRC_DIR"/dist/numa-disruptor-"$commit".tar.gz 2>/dev/null | head -1 || true)"
  if [[ -n "$tarball" ]]; then log "build for $commit already present"; return; fi
  offline && die "no build for $commit staged and NUMA_OFFLINE=1"
  log "building (Gradle downloads its distribution and dependencies once into $GRADLE_USER_HOME; a few minutes)"
  ( cd "$SRC_DIR" && JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:$PATH" bash scripts/hpc/build-offline.sh --skip-tests )
}

stage() {
  mkdir -p "$NUMA_HOME" "$WORK_DIR"
  ensure_jdk
  ensure_repo
  ensure_build
  log "staged. JDK: $JAVA_HOME   source: $SRC_DIR   work/results: $WORK_DIR"
}

run() {
  if [[ -z "${SLURM_JOB_ID:-}" ]]; then
    die "not inside a Slurm allocation. Benchmarks must not run on a login node. Use:  salloc -N1 -c64 -t 1:00:00 -p <partition> -A <account>  then rerun this, or:  $0 submit"
  fi
  stage
  local commit tarball
  commit="$(git -C "$SRC_DIR" rev-parse --short=12 HEAD)"
  tarball="$SRC_DIR/dist/numa-disruptor-$commit.tar.gz"
  [[ -f "$tarball" ]] || die "expected $tarball after staging"
  export JAVA_HOME
  export DIST_TARBALL="$tarball"
  export SLURM_SUBMIT_DIR="$WORK_DIR"   # results/<matrix>/ accumulates here, outside the (disposable) source tree
  export MATRIX="${MATRIX:-all}"
  log "starting benchmark job logic: matrix=$MATRIX, commit $commit"
  exec bash "$SRC_DIR/scripts/hpc/benchmark.slurm"
}

submit() {
  command -v sbatch >/dev/null 2>&1 || die "sbatch not found: run this on the cluster's login node"
  mkdir -p "$WORK_DIR"
  local self; self="$(readlink -f "$0")"
  # shellcheck disable=SC2086
  sbatch --job-name=numa-epyc --nodes=1 --ntasks=1 --cpus-per-task="${SBATCH_CPUS:-64}" --time=01:00:00 \
    --output="$WORK_DIR/slurm-%j.out" ${SBATCH_ARGS:-} --export=ALL "$self" run
}

case "${1:-auto}" in
  stage) stage ;;
  run) run ;;
  submit) submit ;;
  auto) if [[ -n "${SLURM_JOB_ID:-}" ]]; then run; else die "usage: $0 stage|run|submit  (run needs a Slurm allocation; see the header)"; fi ;;
  *) die "usage: $0 stage|run|submit" ;;
esac
