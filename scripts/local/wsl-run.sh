#!/usr/bin/env bash
# Run a command inside the WSL copy of the repo made by wsl-gradle.sh, with JAVA_HOME pointing at the WSL JDK 25.
# Usage: scripts/local/wsl-run.sh benchmarks/build/install/benchmarks/bin/benchmarks verify --events 200000
set -euo pipefail
distro="${WSL_DISTRO:-Ubuntu-22.04}"
home="$(wsl -d "$distro" -- bash -lc 'echo $HOME' | tr -d '\r')"
args=""
for a in "$@"; do args="$args '$a'"; done
wsl -d "$distro" -- bash -c "cd '$home/numa-disruptor-wsl' && JAVA_HOME='$home/tools/jdk25' $args"
