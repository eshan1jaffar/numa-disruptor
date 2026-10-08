#!/usr/bin/env bash
# Sum JUnit XML results per module after a Gradle test run: tests, skipped, failures, errors.
cd "${1:-.}"
for m in topology affinity memory optimizer runtime benchmarks; do
  printf '%-11s' "$m"
  cat "$m"/build/test-results/test/*.xml 2>/dev/null | grep -o '<testsuite [^>]*' \
    | sed -E 's/.*tests="([0-9]+)" skipped="([0-9]+)" failures="([0-9]+)" errors="([0-9]+)".*/\1 \2 \3 \4/' \
    | awk '{t+=$1; s+=$2; f+=$3; e+=$4} END {printf "tests=%d skipped=%d failures=%d errors=%d\n", t, s, f, e}'
done
