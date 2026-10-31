# Performance (plan P7-4)

## What is enforced in CI

`core/.../perf/PerfSmokeTest` builds an ~11,000-node capture and runs the three hot pipelines over it —
`ElementIndex` construction, the JSON export, the XML export — logging the per-run timings to the test log and
failing only on a pathological regression (30 s per stage; the real numbers are two orders of magnitude below that).
Its purpose is the regression net, not benchmarking.

The structural requirements of P7-4 are in place and covered by the existing tests:

- **Image decoding is off the UI thread.** `CanvasPane` decodes the screenshot in a `produceState` producer on
  `Dispatchers.IO` (since P4-5); a broken PNG degrades to the wireframe, never a freeze.
- **Flat lists and indexes are computed once per capture.** Both state holders build `ElementIndex` exactly once
  per capture on a background dispatcher (`withContext(dispatchers.default)`); the offline `SnapshotInspector`
  does the same via `produceState` — never during composition.
- **Property sections render lazily.** The Properties tab is a `LazyColumn` with keyed sections and rows; rows are
  computed per selected node only.

## Indicative numbers

The honest place for numbers is the CI test log of `PerfSmokeTest` (its `perf smoke:` line carries nodes, index,
JSON and XML timings for the machine that ran it — a GitHub-hosted runner, not reference hardware). Read the latest
green run's log for the current values.

## Reference-hardware budgets (Appendix F) — parked

The budgets in plan Appendix F (3k/10k-node trees, cold start, pick-hover p95 ≤ 100 ms, capture latency) are
display-dependent and need the owner's desktop. They are tracked in `docs/MANUAL_QA.md` and are the remaining,
unverifiable-here half of P7-4.
