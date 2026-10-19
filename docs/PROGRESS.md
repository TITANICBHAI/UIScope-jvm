# Progress

One line per task: **status · what changed · how it was verified**.

**Status words.** *code-complete* = code and tests written, every automated check that could be run was run. Because Gradle could not
run where this code was written (Maven Central, the Gradle distribution and Google's Maven are unreachable there), **no task is
Gradle-verified yet**. Run `./gradlew check` and treat anything it reports as the first thing to fix (see "Next for the owner").

## Round 1 — Phase 0 and P1-1

| Task | Status | What changed | How verified |
|---|---|---|---|
| P0-1 Build, toolchain, CI | code-complete, **Gradle-unverified** | foojay JDK resolver, auto-provisioning on, catalog gets detekt/kotlin-test/coroutines-test/SnakeYAML, shared test + detekt config in root `build.gradle.kts`, `.github/workflows/ci.yml` (Windows + Ubuntu, reports uploaded), `.gitattributes`, `.editorconfig`, `gradlew` made executable, README/script say JDK 21 | YAML files parse; all six `.kts` files pass a Kotlin syntax check; **not run through Gradle** |
| P0-2 Scope cut, legacy, flags | code-complete, **Gradle-unverified** | iOS/PC inspectors/hotkey/Watch/Diff/Launcher/bookmarks/auto-pin/watch rules deleted (also their two `.sq` tables); everything else moved to `legacy/` (not in build, never imported); module graph is `app→ui,engine,core · ui→core · engine→core`; `jnativehook` removed; `FeatureFlags` + `HostOs`; placeholder window | grep: no import of `legacy`, no cut-feature words outside `legacy/` and the plan; tests for `HostOs`/`FeatureFlags` pass; window not opened |
| P0-3 Truth reset | done | README, CHANGELOG, DISTRIBUTION rewritten to describe only what exists today; `docs/MANUAL_QA.md` seeded from Appendix K; `docs/proposals.md`; `plan.md` added | read against the code; platform claim corrected to match plan §1.4 |
| P0-4 AppInfo + versioning | code-complete, **Gradle-unverified** | `version.txt` is the single version source (app build no longer takes an env override); `generateBuildInfo` task writes `BuildInfo`; `AppInfo`/`BuildMeta` in core; `app.properties` + `AppInfoLoader` in app; vendor `TBTechs`; Store-build detection | 5 `AppInfoTest` cases pass against a generated `BuildInfo` that mimics the Gradle task's output; the Gradle task itself is unrun |
| P0-5 Infrastructure | code-complete | `AppError`/`Outcome`, `DispatcherProvider`, `ProcessRunner` (concurrent drain, whole-tree kill on timeout and cancel), `ManagedProcess` + launcher, `NetworkGateway` (timeouts, redirects, proxy, partial-file cleanup, coded errors), `DataPaths`, rotating logs + `LogSafe` | **Red first:** with stub implementations 34 of 38 engine tests failed on assertions. Now all 38 pass, run 3× for flakiness (see below) |
| P0-6 Test harness | code-complete | testFixtures in `core`: `FakeProcess`(+`FakeProcessMain`), `FakeHttpServer`, `FakeClock`, `LogCapture`, `GoldenFiles`, `Fixtures`, `TestTrees`, helpers; engine/ui reuse them; CI publishes reports | harness has its own tests (golden mismatch message, missing golden fails, fixture bytes exact, clock); used by ≥ 20 real tests |
| P0-7 Fixtures | code-complete; **Round 0 pending (owner)** | 7 synthetic raw dumps (G1 clean, G1 + suffix — byte-identical to the plan's G1 line —, CRLF, ERROR-prefixed, null root, duplicate-id list, dialog over activity) + README; `scripts/capture-fixtures.sh` and `.ps1` | sanity test per fixture with a plain JDK XML parser; the `.sh` script was run end to end against a fake `adb` (skip handling, PNG bytes intact, README, error paths); the `.ps1` script has **not** been run (no PowerShell available) |
| P1-1 Model v2 | code-complete | `Bounds`, `ElementPath` (structure-only ids), `ElementNode`/`ElementFlags`, `Capture`/`Target`/`ScreenInfo`, `ElementIndex` (paint order, `byPath`, parents/ancestors, counts, identity + relocate), pure `CaptureViewport` | G5 and G6 pass; 10k-node index builds well under 100 ms (best of 5 after warm-up); **Red:** with the three original bugs re-injected into the new code (ids from bounds, no origin subtraction / unanchored zoom, no identity fallback) 10 of 54 core tests fail, all pass again after restoring |

### Test totals (local run: kotlinc + JUnit 4 against the compiler's bundled coroutines 1.5)

core **54** tests, engine **38** tests (92 total). Three complete from-scratch runs (fresh compile each time): 92 of 92 passed every time, including the
process-tree-kill, 50 MiB-output and download-timeout tests. Production code compiles with zero compiler warnings.

## Deviations from the plan, and why

1. **No git history came with the zip**, so a baseline commit of the unchanged upload was made first and each task is its own commit.
   The plan relies on git to keep deleted code; `git log` now does.
2. **P0-5 needed `FakeProcess`, `FakeHttpServer` and `LogCapture` before P0-6 existed** (P0-6 depends on P0-5). They were created in P0-5;
   P0-6 added the rest.
3. **P1-1 red run was done by re-injecting the old bugs into the finished code** rather than by stubbing first. Same evidence (the tests
   catch the bugs), different order. The round-trip test alone does not catch B3 (the bug is symmetric); the explicit numeric
   assertions do.
4. **detekt:** the plan asks for a baseline so old code passes. The old code is in `legacy/`, outside the build, so no baseline exists
   and the two rules are strict for everything. `TimeoutCancellationException` is exempt from `SwallowedException` (it is a coroutine
   control-flow signal, not an error).
5. **`.sq` files:** the two cut-feature tables were deleted; `Sessions.sq` and `AppSettings.sq` stay until P1-7 redefines the schema.
6. **Extra error codes** (`PROCESS_*`, `NETWORK_*`, `DATA_DIR_UNAVAILABLE`, `DISK_FULL`) are not in Appendix A; P4-10's "every code has copy"
   test must include them.
7. `ElementIndex` also counts `NAME` (Windows UI Automation Name) beyond the plan's list, because Windows selectors need it.
8. **Local tests ran on JUnit 4 and coroutines 1.5; the project uses JUnit 5 (via kotlin-test) and coroutines 1.8.1.** Tests only use
   `kotlin.test` APIs and `runBlocking`, and the flow code avoids version-specific operators, to keep both working.

## Notes for later tasks

- **P1-2:** `ElementIndex` throws on duplicate paths. The decoder must validate (or rebuild) paths before an index is built from a loaded file.
- **P3-9:** the "no `ProcessBuilder` outside `engine/process`" guardrail must exempt `core/src/testFixtures/.../FakeProcessMain.kt`.
- **P2-4:** call `JdkNetworkGateway.enableSystemProxies()` once at startup.
- **P4:** add `junit-vintage-engine` (or write UI tests on JUnit 4) when the first Compose UI test appears; `uiTestJUnit4` is declared already.
- **P8-1:** the `description`/JVM args in `app/build.gradle.kts` are still the old ones apart from vendor/description; hardening is that task.

## Next for the owner

1. Run `./gradlew check` on your machine and paste the full output of anything that fails. Most likely trouble spots, in order: detekt with
   Kotlin 2.0 (workaround is in the root `build.gradle.kts`), the `generateBuildInfo` task, `java-test-fixtures` with the Kotlin plugin.
2. Run `./gradlew :app:run` once: a window titled "UIScope" saying "rebuild in progress" should open.
3. Round 0: run `scripts/capture-fixtures.sh` / `.ps1` with your phone attached (see `docs/MANUAL_QA.md`).
