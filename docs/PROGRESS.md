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

## Round 2 — P1-3, P1-4, P1-5

| Task | Status | What changed | How verified |
|---|---|---|---|
| P1-3 Exporters | code-complete | `XmlExporter` (XML 1.0 safe: illegal characters and lone surrogates dropped, `& < > " '` and tab/LF/CR escaped; Android output uses UIAutomator's real attribute set under `<hierarchy rotation>`, Windows output uses UI Automation names), `OutlineExporter` (`indent Class "label" #id [x,y,w,h]`), `Capture.redacted()` | G2-XML: output parsed with a hardened factory, awkward text round-trips; sentinel test: no sentinel string survives redacted XML or outline; **Red:** with stripping and property redaction disabled, 3 tests fail |
| P1-4 SelectorEngine | code-complete | Neutral `Selector` model (Attr/Index/Anchor/Path/Coordinates), `SelectorEvaluator`, `HitTest`, `TextHeuristics`, candidate generation, tiers, ranking, warnings and the verdict sentence per Appendix C, for Android and Windows | G3: unique id → Reliable and not fragile; duplicates → Needs index with the correct N; short ids (`ok`, `fab`, `nav`, `btn1`, `tab2`) raise no warning; obfuscation only at ≥ 10 distinct ids and ≥ 60 %; for every element of a rich tree every candidate's `matches` equals an independently written evaluation; `suggest` on 10 000 nodes < 50 ms; **Red:** re-injecting M1 (any short id flagged, unique ids not Reliable) fails 4 tests |
| P1-5 Codegen v2 | code-complete | `Escapers` for Python, Kotlin, Java, C#, PowerShell, AutoHotkey, YAML, XPath and regex; XPath renderer + a small evaluator for the subset; ten templates (uiautomator2, UI Automator Kotlin, Appium Java, Appium Python, Maestro, XPath, pywinauto, FlaUI, AutoHotkey v2, PowerShell); verdict comment below Good; no serial in output | G4: every template × 18 hostile strings (quotes, backslash, `$`, backtick, newline, tab, CR, markup, CJK, emoji, `%`, regex metacharacters, smart quotes, control characters, a lone surrogate) in 20 reviewed golden files; Python literals **executed** and every Python output passes `py_compile`; Maestro output parsed with SnakeYAML and its regular expressions matched against the original text; XPath evaluated three ways (selector evaluator, our subset evaluator, `javax.xml.xpath` on the exported XML) with identical results; the Kotlin escaper checked by **compiling and running** a generated file with the real Kotlin compiler; **Red:** with naive quoting at least 5 tests fail (the output was cut off by control characters before JUnit's summary) |

Not done from Phase 1 (needs libraries that could not be compiled where this was written): **P1-2** JSON codec (kotlinx.serialization), **P1-6**
settings, **P1-7** storage (SQLDelight). The JSON half of G2 belongs to P1-2.

### How each code template was checked against documentation (plan: "drop what you cannot verify")

No generated snippet could be run against the real frameworks here. Names and parameters were checked against documentation retrieved
through web search; the strength differs:

| Target | Checked against | Not seen in the pages retrieved (written from knowledge) |
|---|---|---|
| uiautomator2 | official README (fetched in full): `u2.connect()`, `d(text=…)`, keys `text/className/description/resourceId/instance`, `.click()`, `d.click(x, y)`, `d.app_start`, `d.xpath` | – |
| UI Automator (Kotlin) | developer.android.com guide and `UiDevice` reference: `UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())`, `findObject/findObjects`, `By.text/desc/res/clazz`, `click(x, y)` | single-argument `By.res("pkg:id/name")`, chaining `BySelector` methods, `executeShellCommand`, the `androidx.test.platform.app.InstrumentationRegistry` import |
| Appium Java | official java-client README: `UiAutomator2Options`, `AndroidDriver`, `AppiumBy.xpath/accessibilityId/className`, default server address | `AppiumBy.id` and `activateApp` (seen in third-party guides only) |
| Appium Python | official python-client README: imports, `webdriver.Remote(…, options=…)`, `AppiumBy.ACCESSIBILITY_ID` | `AppiumBy.ID/XPATH/CLASS_NAME`, `activate_app` (other sources) |
| Maestro | docs.maestro.dev core selectors: `text` and `id` are regular expressions, `text` also matches accessibility text, `index` is 0-based, `point`, `launchApp`. **There is no `description:` key**, so the plan's unverified `description:` was dropped and content-desc maps to `text:` | whether `id` must be the full `pkg:id/name` or only the part after the slash (the template uses the full id) |
| pywinauto | getting-started docs and guides: `connect(process=/path=/title=)`, `window(title=)`, `child_window(auto_id, control_type, title, class_name, found_index)`, `click_input` | `children()`, `mouse.click(coords=…)` |
| FlaUI | sources quoting its wiki: `Application.Attach`, `UIA3Automation`, `GetMainWindow`, `FindFirstDescendant`, `ByAutomationId`, `ByName`, `Click` (the cited URL is the wiki root) | `ByControlType`, `ByClassName`, `.And`, `FindAllDescendants`, `FindAllChildren`, `Mouse.Click` |
| AutoHotkey v2 | Descolada/UIA-v2 wiki: `UIA.ElementFromHandle`, `FindElement({AutomationId:…})`, `Name`, `Type`, the index parameter (1-based), `.Click()`, child indexing | the `ClassName` condition key, `ahk_pid` |
| PowerShell | learn.microsoft.com `AutomationElement` and sample scripts: `Add-Type -AssemblyName UIAutomationClient`, `RootElement`, `PropertyCondition`, `FindFirst`, `InvokePattern` | `AndCondition`, `AutomationElementCollection.Item(n)` |
| XPath | W3C XPath 1.0; evaluated with `javax.xml.xpath` | – |

Things a target cannot express are reported as "not available" with a reason instead of emitting code that cannot work: path/anchor
selectors in UI Automator and Maestro, class selectors in Maestro, coordinates in Appium, XPath and PowerShell, path/anchor/coordinates
in PowerShell.

### Deviations and decisions in Round 2

1. **Tie-breaks in ranking.** Appendix C says "prefer the shorter expression". Neutral spellings make length depend on key names
   (`class` is shorter than `controlType`), so Appendix C's own table order decides first and expression length second. Among fragile
   candidates path-based ones (anchor, absolute path) come before dynamic-text or class-only ones. Coordinates are always last and never best.
2. **Dynamic text that is unique is Fragile**, with the `TEXT_DYNAMIC` warning (the plan lists the warning but not the tier).
3. **Obfuscation counts distinct identifiers** (the plan says "identifiers").
4. **`HitTest` was implemented in P1-4** because the coordinates candidate needs it. Its test (G11, dialog over activity) still belongs to P4-5.
5. **The verdict sentence is built in `core` in English** (`SelectorVerdict.summary`); P4-1 may move the wording into the strings table.
6. **`rotation` in the XML export** was first derived from the screen shape; since Round 3 `ScreenInfo.rotation` stores the dump's real value.
7. **Outline format:** `Class` is the simple class name; the label is the text, else Windows Name, else content description.
8. **A lone surrogate cannot be written into a UTF-8 script**, so PowerShell and AutoHotkey emit it as `[char]55296` / `Chr(55296)`.
   (The golden-file writer caught this; the first version passed the raw character through.)
9. Local test runs now also include SnakeYAML 1.33; the project declares 2.2. Only `Yaml().load/loadAll` are used.

## Round 3 — Android engine (P2-1, P2-2, P2-3, P2-4, P2-6)

| Task | Status | What changed | How verified |
|---|---|---|---|
| P2-1 AdbLocator | code-complete | Finds adb in the order saved path → `ANDROID_SDK_ROOT`/`ANDROID_HOME` → default SDK folder → `PATH` → the app's own copy; a candidate counts only if it is a file and `adb version` prints adb's banner; `select()` validates and remembers a chosen file; `FileAdbPathStore` | 7 tests with fake folders and a fake process runner: order, skipping broken candidates, rejecting non-adb files, a chosen path found again by a brand-new locator ("restart"), Windows `adb.exe` |
| P2-2 Devices | code-complete | `AdbParsers` (strict `devices -l` parser, version banner), `AdbErrors` (adb's messages → Appendix A codes), `AdbClient` (devices, version, start/restart server, device info cached per device), `DeviceTracker` (adb's `host:track-devices` socket protocol with a 3 s polling fallback and back-off) | G9 with the exact output from the plan: 6 devices, correct states/models/connections, the version-mismatch line is a signal not a device, `no permissions`, mDNS and `ip:port` serials; tracker against a fake adb server: a change reaches the collector in < 1 s, cancelling closes the socket, polling waits go 3, 3, 3, 6, 12, 24, 30 s |
| P2-3 Capture | code-complete | `UiAutomatorParser` (slices the XML from the first declaration to the last `</hierarchy>`, tolerates the `UI hierchary dumped to:` suffix, CRLF, BOM, `ERROR:` lines and illegal characters, hardened XML parser, typed errors, never an empty list as failure, keeps all attributes); capture pipeline in `AdbClient.capture`: three dump strategies with the winner remembered per device, two retries on null-root/idle, tree and screenshot fetched at the same time, a screenshot problem is only a warning, single-colour screenshot or system-UI-only tree gives `SECURE_WINDOW`, device file removed even on cancel | G1: clean/suffix/CRLF/`ERROR:` fixtures all give 2 nodes; malformed, truncated, DOCTYPE/entity and 1000-level nesting give typed errors without echoing screen text; 3000 nodes parse in < 200 ms; **all six P0-7 fixtures** go parse → committed outline/XML golden → export → parse round trip → selector engine (every element gets a unique best selector); pipeline tests for fallback order and caching, retries, error mapping, timeouts of 20 s / 10 s; **Red:** re-injecting the old slicing, an unguarded zip extractor and the blind device parser fails 19 of 102 engine tests |
| P2-4 Download | code-complete, **manifest not pinned yet** | `AdbDownloader` (consent text, pinned versioned URL, SHA-256 check, safe extraction, "adb inside?" check, atomic replace of the install folder, cleanup on failure and cancel), `ZipSafe` (rejects `..`, absolute paths, drive letters, backslash tricks, too many entries, zip bombs), `scripts/update-platform-tools.sh` / `.ps1` that pin the manifest | against a local fake server: good archive installed and found as managed adb; wrong checksum; 8 zip-slip names; no adb inside; entry and size limits; not a zip; existing install replaced without leftovers; cancel mid-download leaves nothing; empty manifest refuses without touching the network. The `.sh` script was run against a local server (3 platforms pinned, bad argument refused, a 404 leaves the file untouched) and the Kotlin it generates compiles; the `.ps1` was not run |
| P2-6 Wireless | code-complete | `WirelessParsers` (pair, connect, already connected, mDNS services), `AdbWifi` (validated addresses and 6-digit codes, 15 s / 5 s timeouts); failures carry adb's own message verbatim plus hints | parser tests on sample outputs; bad input never reaches adb |

Not done: **P2-5** (the Android state holder; it needs the settings types of P1-6), and still **P1-2, P1-6, P1-7**.

### What I could not verify in Round 3 (please read)

- **No real adb or phone was involved.** The fake adb server, the fake process runner and the fixtures encode my understanding of adb's behaviour:
  the `host:track-devices` socket protocol (length-prefixed messages, `OKAY`, an initial device list), the exact wording of `adb pair`/`connect`
  results, the `adb mdns services` layout, the shape of `exec-out uiautomator dump` / `screencap -p`. Round 1 on your phone is where they get confirmed.
- **The platform-tools manifest is empty on purpose.** `dl.google.com` is not reachable from where this was written, so no checksum could be computed.
  Until you run `scripts/update-platform-tools.sh <revision>` (or the `.ps1`) the in-app download reports `ADB_DOWNLOAD_UNAVAILABLE`. The archive
  name pattern in the script (`platform-tools_r<revision>-<windows|darwin|linux>.zip`) is from my memory of Google's download URLs and was not
  verified; if the first run reports 404, tell me the real pattern. Find revisions at https://developer.android.com/tools/releases/platform-tools.
- Image decoding for the "single colour screenshot" check uses `javax.imageio`, which the packaged runtime must include (P8-1: `java.desktop`).

### Decisions and deviations in Round 3

1. **The tracker speaks adb's socket protocol directly** instead of parsing the output of an `adb track-devices` child process, so it needs no
   `ManagedProcess` and can be tested against a fake server.
2. `ScreenInfo` gained `rotation`; `XmlExporter` writes the stored rotation and, when present, `hint`, `drawing-order` and `display-id`.
3. `checked` is `null` unless the element is checkable (a `checked="true"` on a non-checkable element is kept as `true`).
4. `foregroundPackage` is the package of the first root window that is not system UI.
5. The remembered adb path is a one-line file for now (`FileAdbPathStore`); when settings (P1-6) exist it should become a settings field.
6. Screenshots are written to `<data>/tmp/<capture id>.png`; saving a snapshot (P1-7/P5) copies them into the snapshot folder.
7. Extraction limits: 5000 entries, 500 MB. Download timeout: 10 minutes.
8. New error codes beyond Appendix A: `ADB_DOWNLOAD_UNAVAILABLE`, `ADB_DOWNLOAD_CHECKSUM`, `ADB_DOWNLOAD_INVALID_ARCHIVE`.

### Test totals (local run: kotlinc + JUnit 4 against the compiler's bundled coroutines 1.5)

After Round 1: core 54 + engine 38 = 92, three complete from-scratch runs all green.
After Round 2: core 105 + engine 38 = 143.
After Round 3: core **105** + engine **103** = **208** tests, all passing in the last complete from-scratch run.

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

## Notes added in Round 2

- **P1-2:** the capture JSON must encode `Target`, flags, properties and warnings; `redacted()` already exists and the exporters take the
  same `Capture`, so the JSON exporter should be tested with the same sentinel approach.
- **P4:** `Candidate.reasons` and `SelectorWarning.message` are the texts for the Selector panel chips; `CodeResult.NotAvailable.reason` is the text for an unavailable target.
- **P4-6 (Code panel):** the Copy-selector button copies `CodeResult.Code.selectorText`.

## Next for the owner

1. Run `./gradlew check` on your machine and paste the full output of anything that fails. Most likely trouble spots, in order: detekt with
   Kotlin 2.0 (workaround is in the root `build.gradle.kts`), the `generateBuildInfo` task, `java-test-fixtures` with the Kotlin plugin.
2. Run `./gradlew :app:run` once: a window titled "UIScope" saying "rebuild in progress" should open.
3. Round 0: run `scripts/capture-fixtures.sh` / `.ps1` with your phone attached (see `docs/MANUAL_QA.md`).
4. Optional but valuable: take one generated snippet for each framework you actually use and run it once. Maestro's `id:` form is the
   one I am least sure of (see the table above).
