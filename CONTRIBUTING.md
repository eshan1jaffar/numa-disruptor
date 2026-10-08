# Contributing

* JDK 25. `./gradlew build` must pass: it compiles with `-Xlint:all -Werror` and runs every portable test.
* Tests that need real NUMA/Linux facilities are tagged `@Tag("hardware")` and run only with `-PhardwareTests`; they must `assume…`
  their preconditions and skip, never fail, where the facility is absent. Portable tests must not depend on the host topology
  (use `NumaTopology.synthetic`/`SimulatedCpuAffinity`).
* Do not weaken a failing test to make it pass; fix the cause or document a known gap in `docs/correctness.md`.
* Concurrency code: state the invariant in a comment (who writes this field, what publishes it). No logging, allocation or
  blocking on the event hot path.
* Any benchmark number in a doc must come from a committed `results.jsonl` and be regenerable with
  `analysis/plot_results.py`; no hand-edited figures.
* Windows developers: Linux-only code can be tested in WSL: `scripts/local/wsl-gradle.sh test -PhardwareTests`. Line endings are
  LF (`.gitattributes`).
* Commit messages: imperative summary, then the *why*. Do not push or publish without the maintainers' go-ahead.
