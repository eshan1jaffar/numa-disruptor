# Correctness

## Semantics

* **Per-partition order.** Within one partition, every consumer sees events in ring order; with one producer that is publish
  order. With several producers, ring order is the single claim order (all consumers agree on it) and each producer's own events
  appear in the order it published them. The runtime never merges partitions: there is **no ordering across partitions**, and a
  workload that needs one global total order must use one partition. Nothing in the benchmarks changes a workload's partition count
  between variants.
* **Multicast** (`ConsumptionMode.MULTICAST`): every consumer receives every event. **Work distribution**
  (`WORK_DISTRIBUTION`): event with ring sequence `s` goes to worker `s mod N` only, so each event is processed exactly once;
  workers keep ring order among their own events, and there is no order between workers. The two are never mixed in one stage.
* **Forwarding stages are 1:1** (`StageFunction`: one output event per input event), so counts and order survive a stage.
* **Backpressure, not loss.** A full ring blocks (or reports) the producer; slots are never overwritten before every consumer has
  passed them (the Disruptor's gating sequences). `stop()` drains.

## What the tests prove (and where)

| hazard | detection | test |
|---|---|---|
| lost events | `OrderingVerifier` gap in per-producer `producerSequence`; `ExactlyOnceTracker.missing()` | every `RuntimeCorrectnessTest` run, `verifierDetectsEveryKindOfViolation` |
| duplicate processing | `ExactlyOnceTracker` bitmap (work distribution), repeated/older sequence | `workDistributionProcessesEachEventExactlyOnce`, `workDistributionAfterAForwardingStage` |
| out-of-order | `duplicateOrReordered` | all pipeline tests; synthetic backwards event in the verifier test |
| partially initialised / stale slot | every field derives from `(producer, sequence)`; consumer recomputes `value`, `eventId` and every payload word | `verifierDetectsEveryKindOfViolation` (torn payload, never-initialised slot) |
| ring overwrite | ring of 8 slots wrapped 12,500 times with contiguity + payload checks; tiny rings in the randomized test | `ringWrapsManyTimesWithoutOverwritingUnconsumedEvents`, `randomizedStressAcrossShapesAndModes` |
| claimed-but-unpublished sequence | the bridge publishes a slot even if the stage function throws, and before waiting for space | `stageFunctionExceptionDoesNotDeadlockDownstream`, small-ring multi-stage tests |
| deadlock | every test has an explicit timeout; stuck-consumer and failure paths | `stuckConsumerCannotHangShutdownOrProducers` |
| unbounded producer blocking | `claimBlocking()` returns -1 once the processor leaves RUNNING | `stuckConsumerCannotHang…`, `handlerExceptionStopsProducersAndIsReported` |
| incorrect shutdown | drain order, idempotent `stop`, timeouts fail loudly, failed start leaves nothing running | `lifecycleMisuseIsRejected`, `unpinnablePlanFailsStartCleanly`, all count assertions after `stop()` |
| routing | modulo router: every key lands in its partition, each partition's consumer checks `partitionId` | `externalPublishersAreRoutedToPartitionsAndStayOrdered` |
| pin placement | the thread's affinity mask equals the plan's CPU for every worker (simulated; and real on Linux) | `pinnedWorkersRunOnTheCpusInThePlan`, `RealAffinityRuntimeTest`, `AffinityTest.linuxPinsOnlyTheWorkerThread` |
| native use-after-free | access to a closed region's segment throws; double close safe; 2000 allocate/free cycles do not grow virtual size | `NumaMemoryTest.useAfterCloseFailsFast…`, `manyAllocateCloseCyclesDoNotLeak` |
| equivalence of variants | all variants' order checksum equals an independent single-threaded reference | `HarnessTest.everyVariantMatchesTheIndependentReference`, `benchmarks verify` |

Randomized stress tests use fixed seeds (reproducible failures): 12 pipeline shapes (1-3 partitions, producers, stages, consumers;
both modes; ring sizes 8/64/512; two wait strategies) and 300 random optimizer instances checked against brute force.

## Known gaps

* No model-checking or `jcstress`-style litmus tests of the Disruptor itself; the project relies on the Disruptor's own test suite
  for sequencing, and tests *usage* invariants.
* The stress tests run for seconds on a developer machine; they would not find a one-in-10^9 race. The hardware runs repeat the
  correctness checks inside every benchmark trial (conservation, order, payload integrity), which adds large volumes of events.
* A `WORK_DISTRIBUTION` worker's `endOfBatch` flag is true only if its own last event is also the batch's last (documented).
