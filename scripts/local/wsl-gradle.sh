#!/usr/bin/env bash
# Run a Gradle command on a native Linux filesystem copy of the repo inside WSL (Windows dev machines).
# Linux-only code (sched_setaffinity, sysfs topology, libnuma) can then be tested without the HPC node.
# Usage: scripts/local/wsl-gradle.sh [gradle args...]   e.g.  scripts/local/wsl-gradle.sh test -PhardwareTests
# Requires: WSL distro with rsync and a JDK 25 at $WSL_JAVA_HOME (default ~/tools/jdk25).
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
distro="${WSL_DISTRO:-Ubuntu-22.04}"
wsl_repo="$(wsl -d "$distro" -- bash -lc 'echo $HOME')/numa-disruptor-wsl"
drive="${repo:1:1}"   # git-bash path /c/Users/... -> WSL path /mnt/c/Users/...
src="/mnt/${drive,,}${repo:2}"
wsl -d "$distro" -- bash -lc "
  set -e
  mkdir -p '$wsl_repo'
  rsync -a --delete --exclude .git --exclude .gradle --exclude build --exclude .tools --exclude results '$src/' '$wsl_repo/'
  chmod +x '$wsl_repo/gradlew'
  cd '$wsl_repo'
  export JAVA_HOME=\"\${WSL_JAVA_HOME:-\$HOME/tools/jdk25}\"
  export PATH=\"\$JAVA_HOME/bin:\$PATH\"
  ./gradlew --console=plain $*
"
