#!/usr/bin/env bash
# Test shim standing in for `srun` when exercising scripts/hpc/benchmark.slurm on a machine without Slurm:
# drops srun's own options and runs the command. Put a directory containing a copy named `srun` first on PATH.
# It does NOT emulate Slurm's CPU/memory binding; it only lets the script's control flow be tested.
while [[ "${1:-}" == --* ]]; do shift; done
exec "$@"
