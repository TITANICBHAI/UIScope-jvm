# UIScope: hand-off for the next implementation agent

Read this first, then `plan.md` (the authority), then `docs/PROGRESS.md` (what was done, how, and every deviation).
Everything here is true as of the last commit in this repository (`git log` has one commit per task).

## 1. Situation in one paragraph

UIScope ("UIScope: Desktop & Android UI Inspector", publisher TBTechs, developer TITANICBHAI) is being rebuilt from an unreliable 1.0.0 into a
trustworthy v1.0 that inspects Android phones (adb) and Windows application windows and answers "what selector should I use?".
`plan.md` is the full plan (62 tasks in 10 phases). About a third is done: Phase 0, Phase 1 (all of it), all of Phase 2 (engine and state holder), plus the pure/engine halves of P5-1, P5-3, P7-5 and all of P3-9 (Rounds 1–5). **The previous author worked in a sandbox with no access to Maven Central or the Gradle distribution, so `./gradlew` has never run
on any of this code.** What was verified, and how, is in section 4. The owner is a solo developer who **cannot run verification
themselves**: you must verify everything you can yourself and report honestly what you could not.

## 2. Ground rules (from the plan, plus lessons from the work so far)

Plan §0 (read it in full; these are the headlines):
1. Order is the dependency map in §5.0. Tests first for every `[REGRESSION]` item, inside the task that owns the fix: write the test, watch it fail for the stated reason, then fix.
2. **Truth over marketing.** No README, UI, changelog or Store text for anything not implemented *and verified*.
3. Scope discipline: ideas go to `docs/proposals.md`, not into code. Decisions in plan §7.1 are not reopened.
4. **No silent failures.** No empty `catch`, no `catch (_: Exception) {}`, no `runCatching{}.getOrNull()` that hides errors. detekt enforces `EmptyCatchBlock` and `SwallowedException` (config in `config/detekt/detekt.yml`): a caught exception must be used (logged with context, passed on, or turned into a typed `AppError`).
5. No work on the UI thread except state updates. Only `engine/.../process` may call `ProcessBuilder`.
6. Keep `docs/PROGRESS.md` (one line per task: what changed, how verified), `docs/MANUAL_QA.md` (steps only a human can do) and `docs/proposals.md`.
7. Hardware you do not have: implement against fakes with unit tests and add manual steps to `docs/MANUAL_QA.md`.

Practices that worked here and should continue:
- **Red first, for real.** For a regression, write the test, then show it fails (or re-inject the old bug into the new code, run, restore) and note the result in PROGRESS. One mutation was found *not* detectable (see PROGRESS Round 4, item 6); say so rather than claim coverage.
- **Never log or put in error details**: element text, window titles, tree contents, full device serials (last 4 characters only, `LogSafe.serial`), command arguments. `ProcessRunner` logs only the executable name, exit code and duration.
- **Verify templates against the framework's own documentation** before shipping them (done for all ten code targets; see PROGRESS Round 2 for which calls were verified how).
- **Review golden files by eye** when you generate them (`UPDATE_GOLDEN=1`); a golden only freezes whatever the code printed.
- Run test suites repeatedly: a flaky test was found that way (a cancel test that cancelled before the port call began).
- Keep one commit per task, and say in PROGRESS what you could *not* verify.

## 3. Repository map

| Path | What |
|---|---|
| `plan.md` | The plan (copy of the authority). |
| `core/` | Pure Kotlin, no UI/process/network: `json` (strict JSON reader/writer, shared), `model` (Capture, ElementNode, ElementIndex, Bounds, CaptureViewport, HitTest), `export` (XML, outline, redaction, `CaptureJson` schema v1), `snapshot` (`SnapshotStore` port, `SnapshotMeta`, `RetentionPolicy/Planner`, `RetainingSnapshotStore`, `BundleCodec` for `.uiscope`), `SemVer`, `selector` (engine per Appendix C), `codegen` (escapers, XPath, ten templates), `settings`, `ports` (`AdbPort`), `state` (`AndroidInspectState`), `error` (`AppError`, `Outcome`, `ErrorCodes`). `src/testFixtures` = shared test harness. |
| `engine/` | The outside world: `process` (ProcessRunner, ManagedProcess, NetworkGateway), `adb` (locator, client, parser, tracker, wireless, downloader, `EngineAdbPort`), `storage` (DataPaths, settings file, `SnapshotDatabase` + `SnapshotStoreImpl` on SQLite via plain JDBC, `SnapshotBundles`, `LegacyData`), `update` (`UpdateChecker`), `logging`. Tests also hold the guardrail scanner (`guardrails/`). |
| `ui/` | Empty until Phase 4. |
| `app/` | `Main.kt` (wiring) and `DevHarness.kt` (temporary developer window), `app.properties`, MSIX manifest, jpackage config. |
| `legacy/` | The old code, read-only, not built, never imported. Deleted in P8-1. |
| `scripts/` | `capture-fixtures.sh/.ps1` (record real adb dumps), `update-platform-tools.sh/.ps1` (pin the platform-tools download), `local-check.sh` (see §5). |
| `docs/` | `PROGRESS.md`, `MANUAL_QA.md`, `proposals.md`, `guardrails.md`, this file, `AGENT_PROMPT.md`. |
| `config/detekt/` | The two detekt rules. |

## 4. What is done and what was verified

Done (code written, tests written, run locally with the method in §5): P0-1…P0-7, P1-1…P1-7, P2-1…P2-6, P3-9, P5-3 and P7-5 (logic), and the non-UI halves of P5-1 (bundle codec + file service).
Last local result: **core 180 + engine 171 tests, all passing** (`scripts/local-check.sh`, from scratch), compiled with zero warnings. Round 5 also ran against a **real SQLite** and re-injected bugs into a copy of the repo to confirm the tests catch them (PROGRESS, Round 5).

**Not verified by anyone, in rough order of risk (start here):**
1. **Gradle itself.** Root `build.gradle.kts` (shared test config; detekt applied to every Kotlin module with the Kotlin-version workaround for detekt 1.23.x; the settings accessors), `core/build.gradle.kts` (`generateBuildInfo` task, `java-test-fixtures`, SQLDelight), foojay toolchain resolver, version catalog. The build scripts only passed a syntax check.
2. **detekt 1.23.8 with Kotlin 2.0.0** and the two-rule config (`buildUponDefaultConfig = false`). New code was reviewed by hand against `SwallowedException`; nothing ran.
3. **JUnit 5 vs JUnit 4.** Tests were run on JUnit 4 (kotlin.test); Gradle uses JUnit 5 via `kotlin-test`. Tests use only `kotlin.test` APIs, but lifecycle differences may appear (per-method instances are the same in both; check anything stateful).
4. **kotlinx.coroutines 1.8.1** (catalog) vs the 1.5 jar used locally. Code avoids version-specific APIs (`MutableStateFlow.update`, `collect {}` on Flow, `receiveAsFlow`). `runInterruptible` carries an `@OptIn(ExperimentalCoroutinesApi::class)`.
5. **Compose code**: `app/.../Main.kt` and `DevHarness.kt` were type-checked only against hand-written stand-ins of the Compose functions used.
6. **The SQLite dependency:** SQLDelight was removed in Round 5 (plain JDBC instead). The Gradle edits (plugin and block removed, `runtimeOnly(libs.sqlite.jdbc)` in `engine`, catalog entry `org.xerial:sqlite-jdbc:3.46.1.3`) had only a syntax check; the version number was never resolved, and the tests used Ubuntu's driver 3.44.1. Also confirm `jpackage` keeps the `java.sql` module (P8-1) and that the driver's native library unpacks on Windows (`docs/MANUAL_QA.md`, Round 5 additions).
7. **CI** (`.github/workflows/ci.yml`), the `.ps1` scripts, and anything touching Windows.
8. **Behaviour against real adb and a real phone.** All Android code is tested against fakes and synthetic fixtures (`core/src/test/resources/fixtures`). The adb socket protocol, the wording of `adb pair/connect` output, `mdns services` layout and the dump/screencap strategies encode the previous author's understanding and need confirming on hardware (`docs/MANUAL_QA.md`, early check 1a).
9. **`GuardrailsTest` under Gradle:** it finds the repository root by walking up from the working directory to `settings.gradle.kts`; Gradle runs tests with the module folder as working directory, so this should work, but it was only run from `engine/` by the local script.
10. **Platform-tools download**: `PlatformToolsManifestData` is intentionally empty (no network where it was written). Run `scripts/update-platform-tools.sh <revision>` (or the `.ps1`) with internet access; the archive URL pattern in the script is from memory.

## 5. How to verify

**If you have normal tooling:** run `./gradlew check` first and fix whatever it reports before writing new code; then `./gradlew :app:run`. Record the result in PROGRESS (what failed, what you changed). Expect small fixes in the items above.

**If Gradle cannot download dependencies (as in the original sandbox):** `bash scripts/local-check.sh` compiles `core` and `engine` with `kotlinc` and runs all tests on JUnit 4. Setup notes are at the top of the script. Limits: no detekt, no Compose, no SQLDelight, no `app`/`ui`. It is a fast safety net, not a substitute.

Test helpers you will use: `blocking {}`, `withTempDir {}`, `awaitTrue {}`, `assertSuccess()/assertFailure(code)` (all in `core/src/testFixtures/.../TestSupport.kt`), `TestTrees` (tree DSL, `balanced(n)`), `Fixtures`, `GoldenFiles` (`UPDATE_GOLDEN=1`), `HostileStrings`, `FakeProcess` (JVM-based fake executable), `FakeHttpServer`, `LogCapture`; in `engine` tests: `FakeProcessRunner`, `FakeAdbServer`.

## 6. Known pitfalls already hit

- **Kotlin comments nest**: `/*` inside a KDoc (for example in an XPath example) opens a nested comment and breaks compilation.
- `return` is not allowed inside non-inline lambdas such as `joinToString { }`; build the list with `map { }` first.
- `Files.writeString` rejects a lone surrogate: any generated script text must encode such characters numerically (done in the PowerShell and AutoHotkey escapers).
- In PowerShell single-quoted strings the Unicode quote look-alikes U+2018, U+2019, U+201A, U+201B also delimit the string and must be doubled.
- Java translates `\uXXXX` before lexing, so generated Java string literals use octal escapes for control characters.
- Maestro's `text` and `id` are regular expressions: values are regex-escaped, then YAML-escaped. Maestro has no `description:` key.
- `DataPaths.ensureDirectories()` creates an empty `tools/platform-tools`; "nothing installed" means "no adb inside".
- A blocked socket or pipe read cannot be cancelled by a coroutine: close the socket/stream from a watcher coroutine (see `DeviceTracker`, `SystemManagedProcess`).
- When a test waits on a state flag ("Capturing"), make sure the thing under test has actually started before acting on it.
- A fuzz test is worth running against every new reader: the one for `.uiscope` found a JDK `IllegalArgumentException` (bad file names in a zip) that a plain `catch (IOException)` missed.
- Test methods that use `= withTempDir { ... }` as an expression body return a value and JUnit 4 refuses them ("should be void"); use a block body.
- Do not kill background jobs with `pkill -f <pattern>` when the pattern also appears in your own command line.
- If your shell is `dash`, `$'\r'` and `${PIPESTATUS[0]}` do not work; use `bash`.

## 7. Open questions for the owner (batch them, use the stated default meanwhile)

- `Settings.checkForUpdates` defaults to **on** (assumption); flip in `Settings.kt` if it should be opt-in.
- Platform-tools revision to pin, and confirmation of the archive URL pattern (see item 9 above).
- Everything in plan §7.2 (publisher identity for the Store, support contact, signing).
- Maestro's `id:` form: the template uses the full `package:id/name`; confirm on a device.
- **Round 5 decisions to confirm** (PROGRESS, Round 5): SQLDelight replaced by plain JDBC (default: keep JDBC); no kotlinx.serialization (default: keep our JSON); pinned snapshots fully exempt from the retention limits (default: yes); pre-releases never offered to people on a stable version; guardrails scan `src/main` only and allow generated PowerShell text in `core/.../codegen`.
- Real fixtures: `scripts/capture-fixtures.*` on the owner's phone (Round 0). Not blocking.

## 8. Recommended next steps, in order

1. Verify what exists (section 5). Fix. Commit. Update PROGRESS. Expect small Gradle fixes (detekt on the new code: `SwallowedException` was checked by hand only; `catch (e: X) { null }` style handlers in `BundleCodec`/`SnapshotStoreImpl` are the first place to look).
2. **Wire Round 5 into the shell** (`Main.kt`/`DevHarness.kt`, needs Compose to compile): at start-up on an I/O thread create `SnapshotDatabase(paths.database.resolve("snapshots.db"), sqliteTmpDir = paths.tmp)`, `SnapshotStoreImpl(...).open()`, wrap in `RetainingSnapshotStore(store, { settings.value.retentionPolicy() }, ::logRetentionError)`, call `enforceNow()`, then `LegacyData(DataPaths.legacyRoot(home), paths).movePlatformTools()`; build `SnapshotBundles` and, if `checkForUpdates`, an `UpdateChecker`. Call `store.close()` on exit. Make the dev harness save the current capture as a snapshot (this is the early manual check for storage).
3. **Phase 3 (Windows)**: P3-0 is a spike that must be measured on a real Windows PC; do not guess its numbers. The Kotlin side of P3-2 (protocol client against a fake helper using `FakeProcess`), P3-3 mapping to `Capture`, P3-5 hit-test mapping and P3-8 `WindowsInspectState` (modelled on `AndroidInspectState`) can be written and unit-tested without Windows; the helper itself (C#) cannot be built or its UI Automation behaviour verified without Windows and .NET.
4. **Phase 4 (UI)**: Compose; the state holders and ports it needs exist (`AndroidInspectState`, `SettingsStore`, `SnapshotStore`, `SnapshotBundles`, `CodeGenerator`, `SelectorEngine`, exporters, `UpdateChecker`). `SelectorVerdict.summary` is English text built in `core`; move it into the Strings table if you prefer. P4-10's "every code has copy" test must cover all codes added since Appendix A (PROGRESS lists them).
5. **P6-1 matching engine** (pure `core`, depends only on P1-1) can be built and tested at any time, even before the UI; do it when the critical path is blocked on hardware. Keep `FeatureFlags.diff` off until P6-2.
6. P5-1/P5-2 screens, P7-1…P7-4, P7-6, Phases 8 and 9 as in the plan. P7-5's UI part: show the "What's new" link from `UpdateCheck.Available`, show nothing for `Failed`/`Disabled`. Call `JdkNetworkGateway.enableSystemProxies()` at start-up (already done in `Main.kt`).

## 9. Deliverable format the owner expects

A zip of the whole repository **including `.git`** (one commit per task), with `docs/PROGRESS.md` updated, plus a short plain-language message: what was done, what was verified and how, what was **not** verified, and what you need from the owner. Never claim a check ran if it did not.
