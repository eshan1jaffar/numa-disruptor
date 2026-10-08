#!/usr/bin/env bash
# Build a self-contained distribution on your development machine, so the HPC node needs only a JDK 25
# (no Maven Central, no Gradle, no network at benchmark time).
#
#   scripts/hpc/build-offline.sh            build + test + package -> dist/numa-disruptor-<commit>.tar.gz
#   scripts/hpc/build-offline.sh --offline  same, but Gradle may not touch the network (proves the local cache suffices)
#   scripts/hpc/build-offline.sh --skip-tests
#
# The tarball contains benchmarks/ and examples/ (bin/ start scripts + lib/ with every dependency jar).
set -euo pipefail
cd "$(dirname "$0")/../.."

gradle_flags=()
run_tests=1
for a in "$@"; do
  case "$a" in
    --offline) gradle_flags+=(--offline) ;;
    --skip-tests) run_tests=0 ;;
    *) echo "unknown argument: $a" >&2; exit 2 ;;
  esac
done

commit="$(git rev-parse --short=12 HEAD 2>/dev/null || echo nogit)"
dirty=""
if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then dirty="-dirty"; echo "WARNING: working tree has uncommitted changes; the distribution is stamped ${commit}${dirty}" >&2; fi

if [[ $run_tests -eq 1 ]]; then
  ./gradlew "${gradle_flags[@]}" build
fi
./gradlew "${gradle_flags[@]}" :benchmarks:installDist :examples:installDist

stage="dist/numa-disruptor"
rm -rf "$stage"; mkdir -p "$stage"
cp -r benchmarks/build/install/benchmarks "$stage/benchmarks"
cp -r examples/build/install/examples "$stage/examples"
cp -r scripts/hpc "$stage/scripts-hpc"
printf 'commit=%s%s\nbuilt=%s\njdk=%s\n' "$commit" "$dirty" "$(date -Is)" "$(java -version 2>&1 | head -1)" > "$stage/BUILD-INFO.txt"

tarball="dist/numa-disruptor-${commit}${dirty}.tar.gz"
tar -C dist -czf "$tarball" numa-disruptor
( cd dist && sha256sum "$(basename "$tarball")" > "$(basename "$tarball").sha256" )
echo "wrote $tarball"
cat "${tarball}.sha256"
echo "copy to the cluster, e.g.:  scp $tarball ${tarball}.sha256 <login-node>:<project-dir>/"
