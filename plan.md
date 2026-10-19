# UIScope — Product, UX & Implementation Plan (path to a v1.0 market launch)

**Audience:** the implementation agent (and the owner skimming the first two sections).
**Input reviewed:** `uiscope.zip` — `version.txt` = 1.0.0, ~9.0k lines of Kotlin in 4 Gradle modules (`core`, `engine`, `ui`, `app`).
**How this was produced:** every module was read; the project's own pure-logic code was compiled and run (Kotlin 2.0.0, JDK 21), a PowerShell 7 probe was run, and one algorithm was ported to Python to reproduce defects.

Evidence tags used throughout:

| Tag | Meaning |
|---|---|
| **[RAN]** | Reproduced by executing code (repro in Appendix G). |
| **[READ]** | Established by reading the code; deterministic, no hardware needed to confirm. |
| **[LIKELY]** | Follows from the code but depends on OS/hardware behaviour. **Confirm on real hardware before relying on it.** |

---

## Contents

0. How to work with this plan (rules for the agent)
1. Product definition (what UIScope is, who it is for, v1 scope)
2. Audit: what is wrong today
3. Engineering rules & target architecture
4. Behaviour & UX specification
5. Work plan (§5.0 dependency map, phases 0–9, tasks, acceptance criteria)
6. Launch readiness — Definition of Done for v1.0
7. Decisions already made / what only the owner can supply
8. Appendices (A error catalogue · B Windows properties · C selector rules · D JSON schema · E QA matrix · F performance budgets · G regression tests & repros · H source map · I Store listing draft · J helper protocol · K owner's manual checks)

---

## Executive summary (30-second read)

UIScope's idea is good and well aimed: **one desktop tool that shows the UI element tree of an Android device or a Windows app and turns any element into a selector you can trust plus ready-to-paste automation code.** Existing tools split by platform or need extra setup: Accessibility Insights for Windows, FlaUInspect and Inspect.exe are Windows-only, uiautomator viewers are Android-only, and Appium Inspector needs a running Appium server and session. One tool for both, with *verified* selectors, is a real gap.

The implementation is not launchable. The things that matter most:

1. **Windows inspection does not work as claimed.** It shells out to `powershell.exe` per call (docs say "JNA, no scripts"), and the script's walker function takes a parameter named `$pid`, which PowerShell treats as read-only, so the real UI Automation path very likely always returns an empty tree and silently falls back to a shallow Win32 window list. Highlights are also misaligned (screen vs window coordinates, DPI).
2. **Android capture returns an empty tree on devices that print uiautomator's trailing "UI hierchary dumped to:" line**, and failures are swallowed (66 `catch (_ …)` blocks discard the exception, 3 logging statements, no tests).
3. **The core promise is mis-implemented.** The "fragile selector" scoring can never reach "Strong", flags a unique stable resource-id as fragile, and generated code breaks on any apostrophe or quote. Exported JSON is invalid when any text contains a newline.
4. **Diff Mode sees zero nodes**, Watch Mode only runs while its screen is open and only writes to an in-app log, History cannot re-open a session, and half of the Settings screen is wired to nothing.
5. **Trust and store risk:** a global keyboard hook makes every press of the letter R, in any app, trigger a refresh; the README/Settings say "no network" while the app calls GitHub at every launch; four documents contradict each other about what exists.
6. **Disk and UI-thread problems:** with Auto-refresh on, every capture saves a screenshot + full tree, forever; parsing, JSON and SQLite run on the UI thread.

**The plan:** narrow v1 to the core loop (*pick/capture → select → selector verdict → copy code*), rebuild the Windows engine on a persistent native helper, fix Android capture, rewrite the selector/codegen core with self-verification, collapse the UI into one coherent workspace, then package for the Microsoft Store (identity already reserved in `AppxManifest.xml`; Microsoft signs the package, so no certificate is needed) with optional GitHub downloads. iOS, Watch, multi-window, tray, bookmarks and macOS/Linux PC inspection are **cut from v1**; Diff returns in 1.1 after a rebuild.

Rough size (agent working time, sequential): Phase 0 ≈ 3 d · 1 ≈ 4 d · 2 ≈ 4 d · 3 ≈ 8 d · 4 ≈ 6 d · 5 ≈ 3 d · 6 optional ≈ 4 d · 7 ≈ 3 d · 8 ≈ 5 d · 9 ≈ 5 d + beta time. These are estimates; the Windows engine (Phase 3) is the schedule risk.

---

## 0. How to work with this plan (rules for the implementation agent)

1. **Order.** The dependency map in §5.0 is authoritative: a task starts when everything it *needs* is done; tasks with no path between them may run in parallel. A phase is *code-complete* when every automated acceptance box is ticked and `./gradlew check` is green. Boxes tagged `needs manual verification` are closed in the next owner round (Appendix K): they do **not** block starting dependent tasks, but they **do** block the release (P9-3).
2. **Tests first for every item tagged `[REGRESSION]`** — *inside the task that owns the fix* (the "Fixed by" column of Appendix G). Write the Appendix G test, watch it fail for the stated reason, then fix. For a new API add a minimal stub first, so the test fails on its assertion and not on a compile error.
3. **Truth over marketing.** Never write README, UI, changelog or Store text for a capability that is not implemented *and verified*. When you cut or defer something, remove it from every surface (UI, docs, Store copy, changelog).
4. **Scope discipline.** Do not add features that are not in this plan. Put ideas in `docs/proposals.md`.
5. **No silent failures.** Empty `catch`, `catch (_: Exception) {}` and `runCatching { … }.getOrNull()` that hide errors are banned. A failure either becomes a typed `AppError` shown to the user, or is logged with context and is genuinely ignorable (add a one-line comment saying why).
6. **No work on the UI thread except state updates.** Composables must not spawn processes, call JNA, touch SQLite or the file system, or parse/serialize trees.
7. **Keep `docs/PROGRESS.md`:** one line per finished task — `P2-3 done · what changed · how verified`.
8. **Hardware you do not have** (a physical phone, a Windows PC at 125–150 % display scaling, a second monitor): implement against fakes with unit tests, add the manual steps to `docs/MANUAL_QA.md` (seed it from **Appendix K**), and leave the acceptance box unticked with the note `needs manual verification`. The owner runs those checks on their own Windows PC and Android phone and reports pass/fail.
9. **Decisions are in §7.1** — do not reopen them. Questions for the human are only those in §7.2; batch them, do not block on them (use the stated default).
10. **Commands.** Build `./gradlew build` · tests `./gradlew check` · run `./gradlew :app:run` · Windows installers `./gradlew :app:packageMsi :app:packageExe`. **JDK 21** is required (every module sets `jvmToolchain(21)`; the README's "JDK 17+" is wrong).

---

## 1. Product definition

### 1.1 One-line promise

> **UIScope shows you every element on an Android screen or a Windows app window, tells you which selector you can trust, and gives you the code for your automation framework.**

### 1.2 Who it is for, and the jobs

| User | Job to be done |
|---|---|
| QA / test-automation engineer (Appium, uiautomator2, Maestro, pywinauto, FlaUI) | **J1 — "Give me a locator for that button I can trust."** *(primary)* |
| Android developer | J2 — "Why can't my test / TalkBack find this view?" (see ids, descriptions, duplicates) |
| RPA / AutoHotkey / Python scripter on Windows | J1 for desktop apps |
| Anyone filing a UI bug | J3 — "Capture and share exactly what the UI tree looks like." |
| QA comparing builds (later) | J4 — "What changed between two screens?" *(v1.1)* |

### 1.3 The core loop (everything else supports this)

**Pick or capture → select an element → read the verdict (is its best selector unique and stable?) → copy selector / code.**

Success targets for v1 (measured by observed beta sessions, since there is no telemetry): first selector copied **≤ 30 s after launch on Windows**, **≤ 2 min on a fresh machine for Android** (includes ADB setup), **≤ 15 s** for every later element.

### 1.4 v1 scope decisions

| # | Capability today | v1 decision | Reason |
|---|---|---|---|
| 1 | Windows app inspection | **SHIP — rebuild engine** | Flagship; Store target; currently broken |
| 2 | Android inspection (ADB) | **SHIP — fix** | Works on every OS |
| 3 | Selector verdict + code generation | **SHIP — rewrite with verification** | The differentiator; currently wrong |
| 4 | Tree / canvas / properties / breadcrumb | **SHIP — fix & restructure** | Core workspace |
| 5 | History | **SHIP as "Snapshots" — rebuild** | Re-open offline, share, retention |
| 6 | Export JSON / XML / Outline | **SHIP — fix**, add PNG + clipboard | Bug-report workflow |
| 7 | Bookmarks | **CUT** | No screen to use them; ids unstable |
| 8 | Auto-pin rules (`AutoPinRule`) | **CUT** | Never wired to anything |
| 9 | Diff | **HIDE — code removed now, rebuilt in Phase 6 (v1.1)** | Sees zero nodes today |
| 10 | Watch | **CUT from v1** | Runs only while its screen is open; log-only alerts; N rules = N concurrent dumps |
| 11 | iOS | **CUT** | Non-functional (flat AppleScript/idb scrape); undocumented external tools |
| 12 | macOS / Linux **PC** inspection | **NOT in v1** | AppleScript depth-3 / python+xdotool X11-only; untested |
| 13 | macOS / Linux installers | **v1.1, Android-only** | Needs notarization/signing and a QA pass |
| 14 | Multi-window (File → New Window) | **CUT** | Second window lacks the saved ADB path; low value |
| 15 | Tray / minimize-to-tray / "launch at startup" | **CUT** | Surprising default; startup toggle does nothing |
| 16 | Global hotkeys | **KEEP one: "Pick" (Windows)**, via `RegisterHotKey` | Remove the keystroke hook entirely |
| 17 | Update check | **KEEP, opt-out setting; disabled in Store build** | Privacy honesty |
| 18 | ADB auto-download | **KEEP, user-initiated, consent + checksum** | Lowest-friction setup |

### 1.5 Non-goals

Not a test runner or recorder · no on-device agent/app install · no cloud, accounts or telemetry · not a design/Figma tool · not a general screenshot tool · no iOS in v1.

### 1.6 Principles

1. **Answer "what selector should I use?" in ≤ 15 s.**
2. **Never show a number you can't explain** — every selector shows how many elements it matches *in the current capture*, computed, not guessed.
3. **Say what you can't see** — limited accessibility, secure windows, elevated windows, truncated trees are surfaced, not silent.
4. **Local-first and private by default** — nothing leaves the machine except the two explicit, user-controlled network actions (update check, ADB download).
5. **Boring, fast, keyboard-friendly** — no process spawn on the UI thread, no single-letter global shortcuts.
6. **Every error has a next step** (message, likely cause, action button, copyable details).

### 1.7 Distribution and identity

- **Publisher:** TBTechs (a one-person company) · **Developer:** TITANICBHAI · **Repository:** https://github.com/TITANICBHAI/UIScope-jvm
- **Primary channel: Microsoft Store.** The identity `TBTechs.UIScopeDesktopAndroidUIInspector` is already reserved. Microsoft signs the MSIX on publication — **no code-signing certificate is bought or configured for 1.0.**
- **Secondary channel (optional): GitHub Releases** — source plus unsigned MSI/EXE installers; the README tells users that Windows SmartScreen may warn on first run.
- **macOS / Linux:** GitHub Releases only, in 1.1, Android inspection only.
- The internal package namespace `com.titanicbhai.uiscope` stays as is.

---

## 2. Audit: what is wrong today

Kept factual and short on purpose. IDs (B = blocker, M = major, m = minor) are referenced from the work plan.

### 2.1 Claims vs. reality

| # | Where it is claimed | Claim | Reality | Action |
|---|---|---|---|---|
| 1 | README / CHANGELOG / DISTRIBUTION / Store copy | What exists | README: only Phase 1, later phases "Planned". CHANGELOG 1.0.0: all four modes shipped. DISTRIBUTION: 1.0.0 is a skeleton, 1.1.0 adds PC; Store copy says PC mode "coming soon". The four documents contradict each other. | Rewrite all after the scope cut (P0-3, P8-7) |
| 2 | CHANGELOG | Windows UI Automation "via JNA — no C++, no scripts" | `WindowsUiAutomation.kt` writes a temp `.ps1` and runs `powershell.exe -ExecutionPolicy Bypass -File …` for every tree build and every pick **[READ]** | Rebuild (Phase 3) |
| 3 | CHANGELOG | macOS Accessibility API via JNA | AppleScript through `osascript`/System Events; frontmost app only; 3 levels deep **[READ]** | Cut from v1 |
| 4 | CHANGELOG | Linux AT-SPI2 via JNA | `python3 -c` + `pyatspi`, `xdotool`, ImageMagick `import`; X11-only; the setup text says to install `at-spi2-core`, which does not provide `pyatspi` **[READ]** | Cut from v1 |
| 5 | CHANGELOG | "Hover highlight overlay drawn on the desktop" | The pick overlay is a fullscreen 35 % black window that shows only cursor coordinates; no element is highlighted **[READ]** | Build properly (P3-4) |
| 6 | CHANGELOG | Dump via `exec-out … uiautomator dump /dev/tty` | Code runs `adb shell uiautomator dump /dev/stdout` **[READ]** | Fix (P2-3) |
| 7 | README | Devices auto-scanned every 5 s | Scanned only when entering the mode or on manual rescan **[READ]** | Implement (P2-2) |
| 8 | README | Live refresh 1–30 s, configurable | Fixed 2 s constant; the setting is never read; auto-refresh defaults off **[READ]** | Implement (P2-5) |
| 9 | README / CHANGELOG | Bookmarks persisted to SQLite | Write-only star: no screen lists them, and ids embed bounds/depth/index so they stop matching as soon as anything moves **[READ]** | Cut |
| 10 | CHANGELOG | History: browse, search, re-open sessions | Shows a 2,000-character JSON text preview; nothing can be re-opened **[READ]** | Rebuild as Snapshots (P5-1) |
| 11 | CHANGELOG | Diff: property-level diff | Sees zero nodes in a normal tree **[RAN]** | Hide; rebuild (Phase 6) |
| 12 | CHANGELOG | Watch: toast + log when a rule fires | No toast; log only; all rules stop when you leave the Watch screen **[READ]** | Cut |
| 13 | Settings screen | Screenshot quality, auto-save, history limit, launch at startup, polling interval, default export format, highlight colours | None of these keys is read anywhere (only written). "System" theme behaves as Dark. "Back" reads settings before the async save finishes, so changes don't apply **[READ]** | Rewrite (P1-6, P4-8) |
| 14 | Settings screen | ADB detection order `$ANDROID_HOME` → `$ANDROID_SDK_ROOT` → PATH | Only `adb` on PATH is tried **[READ]** | Implement (P2-1) |
| 15 | README / Settings | "Fully offline. No telemetry. Nothing leaves your machine." | Calls `api.github.com` on every launch (wrong repo, no opt-out); ADB download contacts `dl.google.com` **[READ]** | Reword + opt-out (P7-5, P8-6) |
| 16 | CHANGELOG | Global hotkeys "without OS permission dialogs" | macOS needs Accessibility/Input Monitoring for the hook; Wayland unsupported; bare `R` is registered as a global hotkey **[READ]** | Replace (P3-6) |
| 17 | CHANGELOG | Software rendering forced on Linux | `-Dskiko.renderApi=SOFTWARE` is in `jvmArgs` for every OS **[READ]** | Fix (P8-1) |
| 18 | DISTRIBUTION | EXE is NSIS, silent switch `/S` | jpackage's `.exe` is a WiX-based wrapper around the MSI, not NSIS **[LIKELY — verify the artifact]** | Correct docs (P8-7) |
| 19 | DISTRIBUTION | Single-file AppImage | Compose's `AppImage` target produces an app-image *directory*, not a `.AppImage` file **[LIKELY — verify the artifact]** | v1.1 |
| 20 | DISTRIBUTION / `AppxManifest.xml` | `.github/workflows/release.yml`, `scripts/build-msix.ps1` | Neither exists in the archive **[READ]** | Create (P8-2, P8-3) |
| 21 | README | JDK 17+ | Every module uses `jvmToolchain(21)`; toolchain auto-provisioning is disabled **[READ]** | Fix (P0-1) |
| 22 | CHANGELOG | XML export is UIAutomator-compatible | Missing `checkable`, `focusable`, `long-clickable`, `password`, `selected`; adds non-standard attributes; illegal XML characters not stripped **[READ]** | Fix (P1-3) |
| 23 | DISTRIBUTION vs manifest vs code | Identity | DISTRIBUTION says package identity `TITANICBHAI.UIScope`, publisher TITANICBHAI; the manifest (the real, Partner-Center-reserved identity) says `TBTechs.UIScopeDesktopAndroidUIInspector`, publisher TBTechs. The update checker calls repo `TITANICBHAI/UIScope`; the real repo is `TITANICBHAI/UIScope-jvm` **[READ]** | **Owner confirmed** (§7.2): publisher TBTechs, developer TITANICBHAI, repo `UIScope-jvm`. One `AppInfo` (P0-4); fix the update checker; DISTRIBUTION.md follows the manifest |

### 2.2 Defects

#### Blockers (must be fixed before any public release)

| ID | Tag | Where | What happens | Fix |
|---|---|---|---|---|
| **B1** | RAN | `UiAutomatorParser.parse`, `AdbManager.dumpUiHierarchy` | When the dump output ends with the standard `UI hierchary dumped to: …` line, the XML parser throws ("Content is not allowed in trailing section"), the exception is swallowed, and the result is **0 nodes** → "Empty element tree". | P2-3 `[REGRESSION]` |
| **B2** | RAN (PowerShell 7.6.7; confirm on Windows PowerShell 5.1) | `WindowsUiAutomation.psScriptContent` | `function Walk($el, $pid, $d)` — `$pid` is the read-only automatic variable `$PID`; calling it throws "Cannot overwrite variable pid because it is read-only or constant". The outer `try/catch` prints `[]`, Kotlin parses an empty list and silently falls back to a shallow Win32 HWND tree. The real UIA path very likely **never works**. | Rebuild — Phase 3 |
| **B3** | READ (DPI part LIKELY) | `VisualCanvas` + PC engines | PC node bounds are screen-absolute; the screenshot is a crop of the window; the canvas draws `bounds.x * scale` with **no origin subtraction**, so highlights are offset by the window's screen position. Separately, the pick overlay reads the mouse via AWT (user-space units) and passes it to UIA/Win32 (physical pixels) → further offset at 125/150/200 % scaling. | P3-3, P3-5, P4-5 `[REGRESSION]` |
| **B4** | RAN | `TreeExporter.jsonStr` | Only `\` and `"` are escaped. Any newline/tab/control character (common in TextViews) yields **invalid JSON**. XML export doesn't strip illegal XML 1.0 characters. | P1-2, P1-3 `[REGRESSION]` |
| **B5** | RAN (algorithm ported to Python) | `DiffScreen.extractNodes` | Scans only outermost `{…}` pairs (i.e. the root node as one blob), keys by `resourceId`, skips nodes without one. A normal single-root tree yields **0 nodes**; PC nodes have no `resourceId` at all. | Delete now (P0-2); rebuild P6 |
| **B6** | RAN | `AndroidCodegen`, `PcCodegen` | No string escaping: `d(text='Don't allow')`, `//…[@text='Don't allow' …]`, `By.text("Say "hi"")` are all syntactically invalid. Also `u2.connect('<package>')` passes a package name as a device serial, and `am start -n <pkg>/.MainActivity` is a guess. | P1-5 `[REGRESSION]` |
| **B7** | READ | `InspectorScreen.performAndroidRefresh`, `SessionRepository` | Every refresh inserts a session with a PNG and the full tree JSON. With Auto-refresh on (2 s delay after each capture) that is on the order of 1,000 sessions per hour — several GB. `pruneToLimit` is never called; deleting a session removes the DB row but not its PNG. | P1-7, P5-3 `[REGRESSION]` |
| **B8** | READ | `GlobalHotkeyManager` | A jnativehook global *keyboard* hook registers bare `R` → refresh and `Ctrl+F` → search focus **system-wide**: pressing R in any application triggers a device refresh. To antivirus, reviewers and users this is indistinguishable from a keylogger. | P3-6 |
| **B9** | READ | `InspectorScreen`, `Main.kt` | UI-thread work: `parser.parse`, `TreeExporter.toJson`, `File.writeBytes`, `sessionRepo.insert` inside `performAndroidRefresh`; `adbManager.isAdbAvailable()` (spawns a process) inside `remember {}` during composition; `pcInspector.getWindowInfo()` called in composition on every recomposition (spawns `osascript`/`xdotool` on macOS/Linux). | P2-5, P3-8 (state holders) · P4-3 (UI) |
| **B10** | READ | `AdbManager`, all PC engines | No timeout and no kill-on-cancel on any external process. A hung `adb`/PowerShell = infinite spinner and a leaked thread. | P0-5 (runner) · P2-2, P2-3, P2-4 (adopt it) · P3-9 (guardrail) `[REGRESSION]` |
| **B11** | READ | ADB-not-found flow in `InspectorScreen` | A browsed or downloaded ADB path is applied with `setAdbPath()` in memory only. After restart: "ADB not found" again. | P2-1 `[REGRESSION]` |
| **B12** | READ | `SettingsScreen`, `Main.kt` | Dead settings (see §2.1 #13); "System" theme = Dark; Back launches an async save then immediately reads settings. | P1-6, P4-8 |

#### Major

| ID | Tag | Where | What happens | Fix |
|---|---|---|---|---|
| M1 | RAN | `RuleAnalyzer`, `AndroidCodegen.isFragile` | Highest reachable score is 60 but "Strong" needs 80 → **Strong is unreachable**. A TextView with a unique stable resource-id scores 52 → WEAK → "fragile". Obfuscation regex flags `ok`, `fab`, `nav`, `btn1`, `tab2`. Two independent notions of fragility. | P1-4 `[REGRESSION]` |
| M2 | READ | `UiAutomatorParser`, `TreePanel` | Node ids embed bounds/depth/sibling index → unstable across captures; selection goes stale after refresh; `collapsedIds` is `remember(rootNodes)` so the tree re-collapses on every capture. | P1-1, P4-4 |
| M3 | READ | `AdbManager.parseDeviceList` | `drop(1)` removes one line blindly (a version-mismatch warning becomes a bogus device); states other than device/offline/unauthorized become UNKNOWN (including Linux "no permissions"); pair/connect failures show no adb output. | P2-2, P2-6 `[REGRESSION]` |
| M4 | READ | `AdbManager.downloadAndInstallAdb` | `latest` URL, no checksum, ZIP-slip (`File(extractDir, entry.name)` unchecked), no consent text, generic failure message, partial zip left behind. | P2-4 |
| M5 | READ | `WatchScreen` | N rules → N concurrent `uiautomator dump` loops; cancelled when the screen is left; alerts only append to an in-app log. | Cut |
| M6 | READ / LIKELY | Pick overlay | Fullscreen on one monitor; no hover highlight; macOS "Fullscreen" creates a Space; the probe right after the click can race overlay disposal and hit UIScope's own window; `Alt+Shift+P` can trigger the Windows input-language switch for multi-layout users. | P3-4, P3-6 |
| M7 | READ | `MacOsAccessibility`, `LinuxAtSpi` | See §2.1 #3–4; permission checks are wrong (macOS needs Accessibility + Automation + Screen Recording). | Cut (P0-2) |
| M8 | READ | `IosManager` | Flat tree, regex parsing of idb text, AppleScript simulator scrape; tools unbundled and undocumented. | Cut |
| M9 | READ | `app/build.gradle.kts` | `includeAllModules = true`; software renderer forced everywhere; `-Xmx512m`; store artwork bundled as runtime resources; no macOS entitlements / Info.plist usage strings; AppImage/EXE docs wrong. | P8-1 |
| M10 | READ | `Main.kt`, `InspectorTopBar` | Window fixed at 1280×820 dp (taller than many laptops at 125–150 % scaling), `WindowState` not remembered, no minimum size; the top bar is 11 text buttons in one row with no overflow handling. | P4-2 |
| M11 | READ | whole code base | 66 `catch (_ …)` blocks that discard the exception, 3 logging statements. `ErrorContent` is shown only when the tree is empty, so errors during Live refresh leave a stale tree and no message. | P0-5, P4-10 |
| M12 | READ | `DatabaseFactory`, repositories | No schema versioning or migrations (`Schema.create` every launch); unsafe lazy singleton; `close()` doesn't close; no WAL / `busy_timeout`; `getAll()` loads every `tree_json` for list screens; data in `~/.uiscope`. | P1-7 |
| M13 | READ | `VisualCanvas` | Hit-test = deepest depth, not topmost; drag-pan vs click conflict; double-click-to-reset conflicts with fast clicking; zoom not cursor-anchored. | P4-5 |
| M14 | READ | `PcCodegen`, `AndroidCodegen` | Windows-only targets shown on every OS; PowerShell cmdlet names (`Get-ChildElement`, `Invoke-Click`, …) could not be verified; pywinauto uses `title_re='.*'` (ambiguous); AutoHotkey library name/URL unverified; Maestro `description:` key unverified. | P1-5 |
| M15 | READ | `Main.kt` | No single-instance guard; second window uses a separate `AdbManager` that never receives the saved ADB path. | P7-1 |
| M16 | READ | `UiAutomatorParser` | DOM parser without secure-processing flags; exceptions → `emptyList()`; `<hierarchy rotation>` never captured. | P2-3, P7-3 |
| M17 | READ | `Main.kt` update check | Hard-coded `APP_VERSION`; wrong repo; `latest != current` offers "downgrades"; regex JSON; no opt-out. | P7-5 |
| M18 | READ | `PcInspector.startEventSubscription` | No-op in every implementation: "live" PC updates do not exist. | Remove (P3) |
| M19 | READ | repo | No tests, no CI, no release scripts; no privacy policy, third-party notices, SECURITY or CONTRIBUTING. | Phase 0 / 8 |

#### Minor

- Emoji/text glyph icons (📋 ⚙ 🖥️ 📱 ←) render inconsistently; colours hard-coded; theme defined twice (inline in `Main.kt` and under `theme/`).
- `NodeUtils` classifies `EditText` as Text (substring order) and anything `ViewGroup` as Group.
- `Key.R` handler on the inspector column probably also fires while typing "r" in the search field **[LIKELY]**; `Ctrl+F` sets `showSearch` but never focuses the field.
- Homebrew hint should be `brew install --cask android-platform-tools`.
- Export uses a Swing `JFileChooser` with a null parent on the UI thread.
- Tray "Quick Pick (PC mode)" only opens the inspector; it doesn't start a pick.
- After `adb pair`, the UI says to use the "Pre-Android 11" tab; Android 11+ needs the *connect* port, which differs from the pairing port.

### 2.3 Structural problems

- `InspectorScreen.kt` is a ~1,550-line composable that mixes state, I/O, persistence and layout. There are no state holders; screens call engines and the DB directly.
- Three hand-written regex JSON parsers (Windows UIA output, `DiffScreen`, `IosManager`) and no serialization library.
- `ElementNode` conflates identity, platform identifier and display. Windows `AutomationId` is not a first-class field (PC nodes have no `resourceId`), so analyzers and diff cannot reason about PC elements.
- Platform engines are shell-outs with no shared error model; every failure turns into `emptyList()`.
- The documents were the spec and nobody kept them true.

### 2.4 What is worth keeping

The four-module split, SQLDelight, Compose Desktop, the tree/canvas/properties layout, the *idea* of a selector-confidence score, the list of codegen targets, the wireless-ADB dialog concept, the onboarding concept, the MSIX manifest and reserved Store identity, the icon set, the MIT licence. Reuse rather than reinvent.

---

## 3. Engineering rules & target architecture

### 3.1 Layout

```
core/        pure Kotlin/JVM (+ coroutines) — no Compose, no processes, no native calls, no UI strings
  model/       Capture, Target, ElementNode, ElementPath, Bounds, CaptureViewport
  selector/    SelectorEngine, Selector, Tier, evaluators (XPath, UiSelector, UIA path)
  codegen/     escapers + one template object per target
  export/      JsonCodec (kotlinx.serialization), XmlExporter, OutlineExporter
  snapshot/    RetentionPolicy (plain value), BundleCodec (.uiscope)
  settings/    Settings (typed data class)
  error/       AppError (code, detail, remedy), Outcome
  ports/       interfaces implemented by engine: AdbPort, WindowsInspectorPort, SnapshotStore, SettingsStore, UpdatePort
  state/       state holders (AndroidInspectState, WindowsInspectState, SnapshotsState …) — plain Kotlin, tested with fake ports
  BuildInfo    generated from version.txt
engine/      platform integrations; implements the core ports; depends on core only
  process/     ProcessRunner, ManagedProcess (long-lived), NetworkGateway
  adb/         AdbLocator, AdbClient, DeviceTracker, AdbDownloader, UiAutomatorParser
  windows/     WindowsHelperClient, protocol DTOs, helper lifecycle
  storage/     DataPaths, JDBC driver wiring for the SQLDelight database, SnapshotStoreImpl, SettingsStoreImpl (settings.json)
ui/          Compose only; depends on core only (ports + state holders), never on engine
  shell/ inspect/ snapshots/ settings/ firstrun/ components/ theme/ Strings
app/         main(), manual wiring (engine implementations -> core ports), AppInfo, packaging
native/windows-helper/    C# helper (built on Windows only; see P3). Not a Gradle dependency of any module:
                          `app` copies the built exe into its resources and passes its path to `engine` at runtime.
```

**Allowed dependencies:** `app → ui, engine, core` · `ui → core` · `engine → core` · `core → nothing in this repo` (set in Gradle by P0-2, so a violation does not compile). Tests in `core` never depend on `engine`; a test that needs both (for example export → parse round-trip) lives in `engine`'s tests.

Screens talk to **state holders** (`class AndroidInspectState(scope, ports)` exposing `StateFlow<…UiState>` and `fun onEvent(...)`), never to engines or the DB. State holders live in `core/state`, are plain Kotlin + coroutines, depend only on core **ports**, and are unit-tested with fake ports.

### 3.2 Threading

`Dispatchers.IO` for processes, files, DB · `Dispatchers.Default` for parsing, serialization, selector computation · Main only for publishing UI state. State holders own a `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`. Captures are **single-flight** per device/window: a new request cancels the running one (and kills its process) or is coalesced.

### 3.3 Errors

`sealed interface AppError { val code: String; val detail: String?; val remedy: Remedy? }` (`Remedy` = an enum of action kinds such as RETRY, RESTART_ADB, DOWNLOAD_ADB, BROWSE_ADB, RESCAN, OPEN_GUIDE, COPY_DETAILS) and `Outcome<T> = Success | Failure(AppError)`. Engines return `Outcome`; **an empty list is never an error signal.** `core` and `engine` carry **codes only — no user-facing text**; the copy for each code (Appendix A) lives in `ui/Strings` and the UI renders it through one message component (§4.10). (If engine code built UI text, `engine` would depend on `ui` and the module graph would become cyclic.)

### 3.4 Process runner

```kotlin
interface ProcessRunner {
    suspend fun run(
        cmd: List<String>, timeout: Duration,
        stdin: ByteArray? = null, env: Map<String, String> = emptyMap()
    ): Outcome<ProcessResult>
}
data class ProcessResult(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray, val durationMs: Long)
```

stdout and stderr are drained concurrently and kept separate (binary-safe) · on timeout **or coroutine cancellation** the whole process tree is destroyed · argument lists only (never `sh -c` with concatenated input) · every call logs basename + exit code + duration · tests use a tiny fake executable.

### 3.5 Data, paths, database

- `DataPaths`: Windows `%LOCALAPPDATA%\UIScope` · macOS `~/Library/Application Support/UIScope` · Linux `${XDG_DATA_HOME:-~/.local/share}/uiscope`; subfolders `db/`, `snapshots/`, `logs/`, `tools/platform-tools/`, `tmp/`. (Under MSIX, AppData writes are redirected to the package's private store and removed on uninstall — acceptable; document it.)
- **Legacy `~/.uiscope`:** do **not** migrate old sessions (unstable ids, unbounded size, no value). On first run, *move* `platform-tools/` to the new tools dir so users don't re-download, and offer a one-time "Delete old data (X GB)?" prompt.
- SQLDelight with real migrations: the **snapshots-only** schema (P1-7) becomes **version 1**; every later change ships a `.sqm`. The generated database API stays in `core` (the SQLDelight plugin stays configured there); `engine` supplies the JDBC driver and file location. Pragmas: `journal_mode=WAL`, `busy_timeout=5000`, `foreign_keys=ON`. One `Database` created in `app` wiring (no global lazy singleton) and closed on exit. Set `org.sqlite.tmpdir` to `<data>/tmp` so the sqlite-jdbc native library doesn't depend on `%TEMP%`.
- The DB stores **metadata only** (snapshot id, title, note, pinned, created_at, target type/label, app label, node count, screenshot file, capture file, bytes). Trees and screenshots are files under `snapshots/<id>/`. **List queries never read capture files.**

### 3.6 Serialization and ids

kotlinx.serialization JSON; `schemaVersion` on every persisted or exported document (Appendix D). `ElementPath` = child-index path (`"0/3/1"`), unique inside a capture. Identity across captures = path + class + identifier (§4.5). **Bounds never appear in ids.** Delete every regex JSON parser.

### 3.7 Settings

One typed `Settings` data class behind `SettingsStore` (`StateFlow<Settings>` + `update { copy(...) }`, write-through, debounced). No raw string keys outside the store. Unknown/legacy keys are ignored. Defaults are defined once. Persisted as `<data>/settings.json` (atomic temp-file + move, with `schemaVersion`) — **not** in the database, so settings and snapshot storage do not depend on each other.

### 3.8 Logging and diagnostics

`java.util.logging` → rotating files in `<data>/logs` (1 MB × 5). INFO: process invocations (basename, exit code, duration), state transitions, errors with codes. **Never** log tree contents, element text, window titles, or full device serials (last 4 characters only). `Copy diagnostics` assembles: version/build, OS + arch, JDK, renderer, adb path + version, helper version, non-sensitive settings, last 300 log lines. A default uncaught-exception handler logs and shows a dialog with *Copy diagnostics* and *Restart*.

### 3.9 Dependencies and build

- **Add:** `kotlinx-serialization-json`, `kotlin-test`, `kotlinx-coroutines-test`, `compose.desktop.uiTestJUnit4` (UI smoke tests), SnakeYAML (test scope only — validates Maestro output), `detekt` with `EmptyCatchBlock` and `SwallowedException` enabled.
- **Remove:** `jnativehook` (LGPL, keystroke hook); JNA/`jna-platform` if nothing uses them after Phase 3.
- **JVM args:** `-Xmx1g`, `-Dfile.encoding=UTF-8`. **Do not force the software renderer**; allow `UISCOPE_RENDERER=software` (and Settings → Advanced) as a fallback. Drop `--add-opens` unless something proves it is needed.
- **Toolchain:** keep `jvmToolchain(21)`; add the foojay toolchain resolver so a clean machine builds without a pre-installed JDK.
- **Version:** `version.txt` is the single source; a Gradle task generates `BuildInfo` (version, build date, commit) used by About, update check, installers and the MSIX manifest.

### 3.10 Compose rules

Hoist state into state holders; composables take immutable UI state + callbacks · `LazyColumn` items keyed by `ElementPath` · never `remember(rootNodes)` for UI state (it resets on every capture) · decode images off the main thread · compute flat lists / hit-test indexes once per capture, not per frame · mark models `@Immutable` · no side effects in composition.

---

## 4. Behaviour & UX specification

### 4.1 App shell and navigation

- **One main window.** Minimum 960×600 dp. Default = min(1280×800 dp, 90 % of the work area). Persist size, position and maximized state; clamp to a visible monitor on start.
- **No launcher screen.** The app opens on **Inspect** with the last-used target (first run → §4.2). A left **navigation rail**: Inspect · Snapshots · Settings; **Help** at the bottom (Welcome, Keyboard shortcuts, Documentation, Report an issue, About).
- **Menu bar** mirrors actions (File: Save snapshot, Export…, Import snapshot…, Exit · View: Fit, Actual size, Show all bounds, Live · Help). Nothing exists *only* in the menu.
- **Status bar** (24 dp): left = target status ("Pixel 7 · Android 14 · connected" / "notepad.exe — Untitled"); middle = "Captured 12:03:41 · 482 elements · 1.8 s"; right = warning chip (opens details) and the Live indicator.
- **Toasts:** copy confirmations, "Snapshot saved — Open", "Selection no longer exists in the new capture". Auto-dismiss after 3 s; errors persist until dismissed.
- **Dialogs:** modal, keyboard-operable (Enter = primary, Esc = cancel).

### 4.2 First run

Shown once; re-openable from Help → Welcome. Three steps, all skippable, none blocking:

1. **What UIScope does** — two sentences and the core loop as three icons.
2. **What do you want to inspect?** — cards *Windows app* (Windows only) / *Android device*; sets the starting target.
3. **Quick checks** — **real results, never a hard-coded tick:**
   - Android: "ADB found: `<path>` (version …)" or "ADB not found" with **[Download platform-tools] [Browse…] [I'll install it myself]** and a *Re-check* button.
   - Windows: "UI Automation helper: ready" (runs a self-test that enumerates top-level windows); "Pick hotkey: Ctrl+Alt+P [Change] [Disable]"; a note that elevated windows need UIScope run as administrator.
   - "Check for updates on startup" (default on; hidden in the Store build).

Finish → Inspect, whose empty state points at the next action.

### 4.3 Inspect — Android

One visible state at a time: `AdbMissing → NoDevice → Unauthorized | Offline | NoPermissions → Ready → Capturing → Captured | CaptureFailed(error)`, plus `Disconnected(lastCapture)`.

- **Device tracking:** `adb track-devices` (fallback: poll `adb devices -l` every 3 s) while the Android target is visible. Auto-select when exactly one usable device; remember the last serial. The picker shows model · Android version · USB/Wi-Fi · state.
- **Capture** (primary button; `F5` / `Ctrl+R`): runs the hierarchy dump and the screenshot **concurrently**, with timeouts (dump 20 s, screenshot 10 s), and publishes **tree + screenshot atomically**. Single-flight: a request while one is running is ignored with an "already capturing" cue; `Esc` cancels and kills the process. One capture runs automatically when a device first becomes Ready, so the screen is never empty.
- **Live** (`Ctrl+L`): after each completed capture wait the interval (default 2 s, 1–10 s), pause while the window is minimized or another destination is open, stop after 3 consecutive failures with a banner "Live paused — *reason* [Resume]". **Live never saves snapshots.**
- **Disconnected:** keep the last capture, show "Disconnected — showing last capture from 12:03:41", pause Live; when the same serial returns, show "Reconnected".
- **Hints:** a blank screenshot, or ≤ 2 nodes belonging to `com.android.systemui`/keyguard → "This screen may be locked or blocks capture (secure window)". Dump errors map through Appendix A.
- **Wireless debugging dialog:** (1) pairing address + code → `adb pair`; (2) separate connect address/port → `adb connect`; prefill via `adb mdns services` when available; a second tab for legacy `adb connect ip:5555`. Always show adb's output on failure. Remember the last host.

### 4.4 Inspect — Windows

Entry points: **Pick** button · global hotkey · empty-state CTA · **Choose window…**.

- **Pick mode** (hotkey toggles; `Esc` or right-click cancels):
  1. UIScope hides (setting *Hide UIScope while picking*, default on).
  2. A click-through overlay spans **all monitors**. It outlines the UI Automation element under the cursor (2 px accent) with a label chip `<ControlType> · "<Name>"`, updating at ≥ 30 Hz (stale queries cancelled).
  3. **The pick click must not reach the target application.** Left-click or Enter picks. The helper swallows the click with a low-level **mouse** hook that exists *only while picking*; there is no keyboard hook, ever.
  4. On pick: capture the **top-level window** of the element (tree + screenshot), select the element, expand its ancestors, scroll it into view, bring UIScope to the front. Status: "Picked from notepad.exe — Untitled".
- **Choose window…:** visible top-level windows (icon, title, process; elevated ones marked). The route for elevated, minimized or hard-to-hover windows.
- **Refresh** (`F5`): re-capture the same window by handle; if gone → "Window closed — pick another" (keep last capture). Selection preserved by RuntimeId/path.
- **No pseudo-live mode** on Windows in v1 (snapshot-based). "Follow focus" is post-1.0.
- **Elevated target:** `ELEVATED_TARGET` error (Appendix A). Packaged apps cannot self-elevate → "Right-click UIScope in Start → Run as administrator".
- **Limited accessibility:** a tree of < 5 elements for a window larger than 300×300 px shows the `LIMITED_ACCESSIBILITY` hint (Chromium/Electron/Qt/Java).
- **Truncation:** depth ≤ 40, nodes ≤ 10,000; beyond that show a "Tree truncated" warning chip.
- **Coordinates:** all Windows bounds are **physical screen pixels**; the capture carries `origin` (screen position of the screenshot's top-left) and the canvas maps `bounds − origin` through `CaptureViewport`.
- **Password fields:** never read or show their value; mark `isPassword`.

### 4.5 Inspector workspace

Three resizable panes with splitters (widths persisted): **Tree** (300 dp) · **Canvas** (flexible) · **Details** (360 dp). Side panes collapse from the toolbar or keyboard.

**Tree**
- Virtualized; rows keyed by `ElementPath`. Row = kind icon · primary label (text → description/Name → identifier → class) · secondary (identifier or class, dimmed) · badges (clickable, scrollable, offscreen, duplicate id).
- Expansion, scroll position and selection live in the state holder and are **restored after every capture** by identity (path + class + identifier; fallback identifier + occurrence index). Default expansion = depth ≤ 2 plus the path to the selection.
- **Search** (`Ctrl+F` focuses the field): matches text, description/Name, identifier, class; highlights, shows "3 of 17"; `Enter`/`Shift+Enter` next/previous; `Esc` clears. Searching never changes the selection.
- Keyboard: ↑/↓ move · ←/→ collapse/expand · `Enter` copies the best selector · `Alt+↑` selects the parent.
- Context menu: Copy selector · Copy code · Copy path/XPath · Copy bounds · Expand/collapse subtree · Export subtree.

**Canvas**
- Screenshot fit-to-window by default. **Zoom** with `Ctrl+wheel` anchored at the cursor (plain wheel scrolls); **pan** with middle-drag or `Space`+drag (a plain click never pans); buttons Fit and 100 %.
- Hovered element = outline + tooltip (label, identifier, size); selected element = filled accent; **Show all bounds** toggle (default off).
- **Hit-testing is z-order aware:** iterate in reverse paint order (later siblings on top, deepest first) and pick the first node containing the point; ties → smallest area. `Alt+click` selects the parent.
- All coordinate math goes through one pure, unit-tested `CaptureViewport` (origin, scale, zoom, pan).
- Wireframe fallback when a snapshot has no screenshot.

**Details pane** — header (kind icon · label · class) and three tabs:
- **Selector** (default) → §4.6.
- **Properties** — grouped sections (Identity · State · Geometry · Platform) with label/value rows and a hover copy icon; "Copy all as JSON". Android: resource-id, class, package, text, content-desc, checkable/checked/clickable/enabled/focusable/focused/scrollable/long-clickable/password/selected, bounds, index. Windows: Appendix B.
- **Path** — ancestor list (click to select) and the absolute path/XPath.

### 4.6 Selector and code (the product's reason to exist)

- **Verdict card** — one computed sentence plus a tier chip:
  - *Reliable* — "Unique resource-id `login_button` (1 match)".
  - *Good* — "Unique text 'Log in' (1 match) — breaks if the app language changes".
  - *Needs index* — "`row` matches 14 elements; index 3 breaks if the list changes".
  - *Fragile* — "No stable attribute; path-based selector only".
- **Candidates** (ranked per Appendix C): strategy · expression · "matches N in this capture" · tier chip · [Copy]. Clicking a row makes it the **active selector**; the best is preselected.
- **Test selector:** evaluates the active selector against the current capture and highlights **all** matches (green if exactly 1, red if 0 or > 1).
- **Code:** target dropdown (task P1-5 lists the targets per platform) → read-only highlighted block → **[Copy code] [Copy selector]**. Output is generated from the *active selector*, escaped for the target language, and covered by golden tests.
- Warnings are specific, never generic: "text looks dynamic (digits/time)", "text is localized", "IDs in this app look obfuscated".

### 4.7 Snapshots

- A snapshot is **a capture saved on purpose**. `Save snapshot` (`Ctrl+S`) stores tree + screenshot + target info. Setting *Save every capture automatically* defaults to **off**; Live never saves.
- Default title `<app label or window title> · <time>`; rename and add a note.
- **Retention** defaults: at most **200 snapshots or 1 GB**, whichever first; oldest deleted first, **files included**; *Pin* exempts a snapshot. Settings shows storage used with [Open folder] and [Clear all…].
- **Snapshots screen:** list (thumbnail, title, target, time, node count, note) with search and sort; selecting shows a preview with **Open in Inspector** (loads offline; banner "Snapshot from 12:03 — not live [Capture now]"), Rename/Note, Pin, Export…, Delete (confirm; deletes files).
- **Share:** export/import `.uiscope` bundles (zip: `manifest.json`, `capture.json`, `screenshot.png`). Import by button or drag-and-drop; validate `schemaVersion`, cap at 100 MB, reject path traversal.
- First export shows a one-line privacy notice: snapshots contain whatever was on screen.

### 4.8 Export

- Dialog: **Scope** (whole capture / selected subtree) · **Format** (JSON · XML · Outline · PNG) · **Options** (Redact text values; Include screenshot) · **[Copy to clipboard] [Save as…]**.
- JSON per Appendix D. XML is valid XML 1.0 (illegal characters stripped, newlines in attributes as `&#10;`) with UIAutomator's real Android attribute set. Outline: `indent Class "text" #id [x,y,w,h]`. PNG = screenshot with the selected element outlined.
- Redaction replaces text-bearing fields with `[redacted]`; if a PNG is included, warn that screenshots cannot be redacted.
- Native file dialogs are opened off the UI thread, remember the last folder, and default to the format chosen in Settings.

### 4.9 Settings

All controls apply instantly (no Save button). **Every control works or is removed.**

| Section | Controls |
|---|---|
| General | Theme (System / Light / Dark — System follows the OS) · Check for updates (hidden in the Store build) |
| Android | ADB: detected path + version, [Browse…] [Test] [Download platform-tools…] · Live interval (1–10 s) |
| Windows | Pick hotkey (enable / rebind / reset) · Hide UIScope while picking |
| Snapshots | Save every capture automatically (off) · Retention (count, size) · storage used · [Open folder] [Clear all…] |
| Advanced | Renderer (Auto / Software, restart required) · Log level · Open logs folder · Copy diagnostics · Reset all settings |
| About | Version + build · licence · third-party notices · links (Documentation, Report an issue, Privacy policy) · What's new |

### 4.10 Errors and empty states

- Every failure renders **one component: title · what happened · likely cause · primary action · [Copy details]**. Codes, copy and actions are Appendix A; the UI never invents ad-hoc messages.
- An error that occurs while a capture exists appears as a **banner above the canvas** without discarding the capture.
- Empty states: *No ADB* · *No device* (checklist: cable, USB debugging, file-transfer mode, drivers) · *Unauthorized* (what to tap on the phone) · *No capture yet* (CTA) · *No search results* · *No snapshots*.

### 4.11 Keyboard shortcuts

In-app unless stated. **No single-letter shortcuts.** (macOS uses ⌘ in 1.1.)

| Action | Shortcut |
|---|---|
| Capture / Refresh | `F5`, `Ctrl+R` |
| Toggle Live | `Ctrl+L` |
| Pick element (global, Windows, optional) | `Ctrl+Alt+P` (rebindable) |
| Focus search | `Ctrl+F` |
| Next / previous match | `Enter` / `Shift+Enter` |
| Save snapshot | `Ctrl+S` |
| Export… | `Ctrl+E` |
| Copy best selector | `Ctrl+Shift+C` |
| Copy code | `Ctrl+Shift+K` |
| Select parent | `Alt+↑` |
| Cancel pick or capture / close dialog / clear search | `Esc` |

### 4.12 Accessibility, i18n, theming

- Every icon button has a content description and tooltip; focus order is logical; everything is keyboard-operable.
- Text contrast ≥ 4.5:1 in both themes (unit test over theme tokens); follow OS dark/light; scale with OS text size.
- Compose Desktop exposes limited accessibility on Windows — say so in the docs, and run Accessibility Insights for Windows (FastPass) on UIScope and fix critical findings.
- No emoji or glyph icons: use Material Icons (`materialIconsExtended` is already a dependency).
- All user-visible strings live in one `Strings` object (English only in v1) so localization is possible later.

---

## 5. Work plan

Sizes: **S** ≤ 0.5 day · **M** 1–2 days · **L** 3–4 days · **XL** ≥ 5 days. `[REGRESSION]` = write the Appendix G test first. Acceptance boxes are the definition of done for the task.

### 5.0 Dependency map (authoritative order)

*Checked by script: this graph is acyclic and every dependency is in the same or an earlier phase. "Needs" = outputs that must exist before the task can be finished; tasks with no path between them can run in parallel. Acceptance boxes tagged `needs manual verification`, and the "closes later" notes below, are deferred to a later task or owner round — they **do not block** starting dependent tasks.*

| Task | Needs |
|---|---|
| P0-1 | — |
| P0-2 | P0-1 |
| P0-3 | P0-2 |
| P0-4 | P0-2 |
| P0-5 | P0-2 |
| P0-6 | P0-5 |
| P0-7 | P0-1 |
| P1-1 | P0-2, P0-5 |
| P1-2 | P1-1 |
| P1-3 | P1-1 |
| P1-4 | P1-1, P0-6 |
| P1-5 | P1-4 |
| P1-6 | P0-5, P1-2 |
| P1-7 | P0-5, P1-1, P1-2 |
| P2-1 | P0-5, P1-6 |
| P2-2 | P0-5, P2-1 |
| P2-3 | P1-1, P2-2, P0-7, P1-3, P1-4 |
| P2-4 | P0-5, P2-1 |
| P2-5 | P1-1, P1-6, P2-2, P2-3 |
| P2-6 | P2-2 |
| P3-0 | — |
| P3-1 | P3-0 |
| P3-2 | P0-5, P3-1 |
| P3-3 | P1-1, P3-2 |
| P3-4 | P3-2, P3-3 |
| P3-5 | P1-1, P3-3 |
| P3-6 | P3-2, P3-4 |
| P3-7 | P3-2 |
| P3-8 | P1-1, P1-6, P3-3, P3-4, P3-5, P3-6, P3-7 |
| P3-9 | P2-2, P2-3, P2-4, P3-2 |
| P4-1 | P0-5, P1-6 |
| P4-2 | P4-1, P1-6 |
| P4-3 | P4-2, P4-10, P2-5, P2-6, P3-7, P3-8 |
| P4-4 | P4-3 |
| P4-5 | P4-3, P1-1, P3-5 |
| P4-6 | P4-3, P1-4, P1-5 |
| P4-7 | P4-2, P2-1, P2-4, P3-2, P3-6 |
| P4-8 | P4-2, P4-7, P1-6, P2-1, P2-4, P0-4, P0-5 |
| P4-9 | P4-3, P4-4, P4-5, P4-6 |
| P4-10 | P4-1, P0-5 |
| P5-1 | P1-2, P1-7, P4-3, P4-6, P4-10 |
| P5-2 | P1-3, P4-3 |
| P5-3 | P1-7, P4-8 |
| P6-1 | P1-1 |
| P6-2 | P6-1, P5-1 |
| P7-1 | P4-2 |
| P7-2 | P0-5, P3-2, P4-2 |
| P7-3 | P2-3, P2-4, P5-1 |
| P7-4 | P2-5, P3-8, P4-9, P5-2 |
| P7-5 | P0-4, P0-5, P4-8 |
| P7-6 | P2-4, P7-5, P3-9 |
| P8-1 | P3-1, P0-4, P4-8, P4-9, P4-10 |
| P8-2 | P8-1 |
| P8-3 | P8-2, P3-1 |
| P8-4 | P8-2 |
| P8-5 | P4-9, P5-2, P8-6 |
| P8-6 | P7-6 |
| P8-7 | P0-3, P4-9, P5-2, P8-6 |
| P8-8 | P8-1 |
| P9-1 | P5-3, P7-1, P7-2, P7-3, P7-4, P8-2, P8-7 |
| P9-2 | P9-1, P8-3 |
| P9-3 | P9-2, P8-4, P8-5, P8-6, P8-7, P8-8 |

**Waves** (everything in one wave can run in parallel once the previous waves are done):

- **W0:** P0-1, P3-0
- **W1:** P0-2, P0-7, P3-1
- **W2:** P0-3, P0-4, P0-5
- **W3:** P0-6, P1-1, P3-2
- **W4:** P1-2, P1-3, P1-4, P3-3, P3-7, P6-1
- **W5:** P1-5, P1-6, P1-7, P3-4, P3-5
- **W6:** P2-1, P3-6, P4-1
- **W7:** P2-2, P2-4, P3-8, P4-2, P4-10
- **W8:** P2-3, P2-6, P4-7, P7-1, P7-2
- **W9:** P2-5, P3-9, P4-8
- **W10:** P4-3, P5-3, P7-5
- **W11:** P4-4, P4-5, P4-6, P5-2, P7-6
- **W12:** P4-9, P5-1, P8-6
- **W13:** P6-2, P7-3, P7-4, P8-1, P8-5, P8-7
- **W14:** P8-2, P8-8
- **W15:** P8-3, P8-4, P9-1
- **W16:** P9-2
- **W17:** P9-3

**Critical path** (longest chain by estimated effort, ≈ 32.5 agent-days; Phase 6 is optional and not on it): P0-1 → P0-2 → P0-5 → P1-1 → P1-2 → P1-6 → P2-1 → P2-2 → P2-3 → P2-5 → P4-3 → P4-4 → P4-9 → P8-1 → P8-2 → P9-1 → P9-2 → P9-3.

**Independent tracks:** the Android track (P2-1…P2-6) and the Windows track (P3-0…P3-8) do not depend on each other and meet in P4-3; the Windows spike P3-0 has no prerequisite and can start on day 1 on a Windows machine.

**Closes later (annotation only, not a dependency):** `Strings` (P0-5 → P4-1) · export → parse round-trip (P1-3 → P2-3) · ADB consent dialog (P2-4 → P4-7/P4-8) · wireless dialog, window chooser, Windows UI (P2-6, P3-7, P3-8 → P4-3) · banners (P2-5 → P4-10) · visual alignment (P3-5 → P4-5 and Appendix K Round 2) · every `needs manual verification` box (→ Appendix K rounds).

### Phase 0 — Baseline and truth (≈ 3 d)

*Goal: a repo that builds from clean, tells the truth, has a test harness, and has the infrastructure every later task needs.*

**P0-1 · Build, toolchain, CI (S)**
- Add the foojay toolchain resolver to `settings.gradle.kts`; remove `org.gradle.java.installations.auto-provisioning=false`; README prerequisites = **JDK 21**.
- `.github/workflows/ci.yml`: matrix `windows-latest` + `ubuntu-latest`; JDK 21; Gradle cache; `./gradlew check`.
- Add `.gitattributes` (LF for `*.kt`, `*.kts`, `*.sh`; CRLF for `*.ps1`, `*.bat`) and `.editorconfig`.
- Add detekt with `EmptyCatchBlock` and `SwallowedException` on; baseline the existing code so it passes, **strict for all new/rewritten code**.
- Add the test dependencies from §3.9 (kotlin-test, coroutines-test, Compose UI test, SnakeYAML for tests) to the version catalog and to the modules' test source sets.
- [ ] A clean clone with only a JDK builds on Windows and Linux · [ ] CI green · [ ] README prerequisites correct.

**P0-2 · Scope cut, legacy quarantine, feature flags (M)**
- **Delete** the cut features (git history keeps them): `engine/ios/`, all of `engine/pc/` (the PowerShell, AppleScript and python/xdotool inspectors and `PcInspector`), `engine/hotkey/` **and the `jnativehook` dependency**, `ui/watch/`, `WatchRule*`, bookmark UI + repository, `AutoPinRule`, tray / multi-window / "launch at startup" code, `ui/launcher/`, `ui/diff/` (rebuilt in Phase 6).
- **Quarantine everything else that is being rewritten.** Move all remaining old Kotlin sources of `core`, `engine`, `ui` and `app` into a top-level `legacy/` folder that is **not part of the Gradle build**; keep the Gradle module skeletons, resources and the MSIX manifest. Set the module dependencies to the §3.1 graph (`ui → core`, `engine → core`, `app → all`). Replace `Main.kt` with a temporary shell (one window saying "rebuild in progress"). *Why:* Phases 1–3 replace the model and engines that the old UI and old engines compile against, while the new UI (Phase 4) needs the new engines first — without this step the build cannot stay green and the two sides wait on each other (a build-order cycle). From here on **new code is written fresh in the four modules; `legacy/` is read for ideas only, never imported**, and it is deleted in P8-1.
- Add `FeatureFlags` in `core` (`windowsPc = isWindows`, `diff = false`). The database schema is redefined in P1-7 (pre-launch, no data to preserve).
- [ ] `./gradlew check` is green with `legacy/` excluded · [ ] nothing in the build imports from `legacy/` · [ ] `ui` has no Gradle dependency on `engine` · [ ] `grep -ri "ios\|watch\|bookmark\|tray"` outside `legacy/` finds nothing user-facing.

**P0-3 · Truth reset of text (S)**
- Replace README with a short honest version (what v1 does, how to run, supported platforms). Collapse CHANGELOG to a single `[1.0.0] — unreleased` section that describes only what will actually ship (1.0.0 and 0.1.0 were never published, so delete the dead `releases/tag/v1.0.0` and `v0.1.0` links and the claims behind them). Replace `DISTRIBUTION.md` with what is true today (full rewrite in P8-7). (The old in-app copy is quarantined with the legacy UI in P0-2.)
- [ ] Every claim in README and CHANGELOG is true for the current build.

**P0-4 · `AppInfo` and versioning (S)**
- Gradle task generates `BuildInfo` **into `core`** (version from `version.txt`, build date, git short SHA when available) so every module can read the version.
- `AppInfo` reads `app.properties`: name, publisher, developer, repo URL, releases API URL, issues URL, privacy URL, docs URL, support link. **Owner-confirmed values:** publisher `TBTechs`; developer `TITANICBHAI`; repo `https://github.com/TITANICBHAI/UIScope-jvm`; releases API `https://api.github.com/repos/TITANICBHAI/UIScope-jvm/releases/latest`; issues page = repo `/issues` (also the Store *support link*); privacy URL = the repository's public `PRIVACY.md` page (no extra hosting). A support email is optional — `DISTRIBUTION.md` lists `uiscope@titanicbhai.dev`; use it only if the owner confirms it is a real inbox. Replace the hard-coded `APP_VERSION` and the wrong repo constants in `Main.kt` (`TITANICBHAI/UIScope` appears in two places). Set the jpackage `vendor` to `TBTechs`; leave the MIT `LICENSE` holder as it is. `AppInfo` is loaded in `app` and passed down by constructor injection (engine diagnostics, UI About) — never imported from `app`. It also exposes `isStoreBuild` (running from a `WindowsApps` path, or `UISCOPE_STORE=1`).
- [ ] One place to change identity · [ ] About, update check, MSI and MSIX version all read it.

**P0-5 · Infrastructure (M)**
- Implement `ProcessRunner` (one-shot) **and `ManagedProcess`** (long-lived: stdin writer, stdout/stderr line flows, `kill()`, the same tree-kill semantics — needed by `adb track-devices` in P2-2 and the Windows helper in P3-2), **`NetworkGateway`** (the *only* HTTP entry point: `java.net.http.HttpClient`, timeouts, system proxy; used by the ADB download and the update check), `AppError`/`Outcome`, logging setup, `DataPaths` and a dispatcher provider (§3). (`Strings` is created in P4-1, in `ui`.)
- [ ] `ProcessRunnerTest`: timeout kills the tree · cancellation kills it · 50 MB stdout does not deadlock · stderr kept separate · non-zero exit · missing executable → `AppError` · [ ] `ManagedProcess`: line streaming, kill on cancel, exit detected · [ ] `NetworkGateway`: timeout, redirect, proxy settings, tested against a fake local server · [ ] logs rotate in `<data>/logs` · [ ] INFO logs contain no element text / window titles / full serials (test with a fake).

**P0-6 · Test harness and red-first discipline (S)**
- Shared test utilities: golden-file helper, fixture loader, `FakeProcess`, `FakeHttpServer`, `FakeClock`. CI runs `check` and publishes test reports.
- The Appendix G tests are **not** written up-front: most of them need APIs that do not exist yet, so writing them in Phase 0 would make Phase 0 wait on Phases 1–3. Each one is written **at the start of its owning task** (the "Fixed by" column): stub the API, watch the test fail on its assertion for the stated reason, fix, go green, and record the red run in `PROGRESS.md`.
- [ ] Harness and fakes exist and are used by at least one real test · [ ] CI runs the tests and publishes reports.

**P0-7 · Fixtures (S)**
- `core/src/test/resources/fixtures/android/` (shared with `engine` tests): start with **synthetic** raw dumps modelled on the G1 fixture (clean, trailing-suffix, CRLF, `ERROR:`-prefixed, a list with duplicate ids, a dialog over an activity) so Phase 1 can proceed.
- Add `scripts/capture-fixtures.(sh|ps1)`: saves **raw `adb exec-out uiautomator dump` output and `screencap`** for a few screens. It must call `adb` directly and **never UIScope code**, so the fixtures cannot depend on the code they test. The owner runs it once on their phone (Appendix K, Round 0) for five different screens; the agent commits the output with a README (device, Android version, date).
- [ ] Synthetic fixtures committed · [ ] capture script committed and documented · [ ] real fixtures added when the owner returns them (P2-3 acceptance needs them; until then that box stays `needs real fixtures`).

### Phase 1 — Pure core (≈ 4 d)

*Goal: the platform-independent heart is correct and fully unit-tested.*

**P1-1 · Model v2 (M)**
- `Capture(id, takenAt, target, screen, screenshot?, roots, warnings)`; `Target = Android(device, foregroundPackage?) | Windows(window)`.
- `ElementNode(path, name?, className, identifier?, text?, contentDescription?, bounds, flags, properties, children)`. `identifier` = Android resource-id **or** Windows AutomationId (UI labels it per platform).
- `ElementIndex(capture)`: flat list in paint order, `byPath`, `parentOf`, `ancestors`, occurrence counts by identifier/text/description/class (used for uniqueness).
- `Bounds` helpers; pure `CaptureViewport` (origin, scale, zoom, pan, `toCanvas`, `toCapture`).
- [ ] Ids are stable when bounds change (G5) · [ ] `ElementIndex` for 10k nodes < 100 ms · [ ] `CaptureViewport` round-trips at scales 1.0/1.25/1.5/2.0 **with non-zero origin** (G6, fixes B3's math).

**P1-2 · Serialization (M)**
- kotlinx.serialization `CaptureJson` per Appendix D, with `schemaVersion` check and unknown-key tolerance. Delete every regex JSON parser and `TreeExporter.jsonStr`.
- [ ] G2 passes (newline, tab, quotes, backslash, control chars, emoji/surrogates) · [ ] round-trip equality on a 5k-node tree · [ ] a higher `schemaVersion` gives a clear error.

**P1-3 · Exporters (M)**
- `XmlExporter`: valid XML 1.0 (strip characters outside `#x9 | #xA | #xD | #x20–#xD7FF | #xE000–#xFFFD | #x10000–#x10FFFF`; escape `& < > " '`; newlines/tabs in attributes as character references). Android output = `<hierarchy rotation="…">` with UIAutomator's real attributes: `index text resource-id class package content-desc checkable checked clickable enabled focusable focused scrollable long-clickable password selected bounds="[l,t][r,b]"`.
- `OutlineExporter`; `Capture.redacted()` (replaces text-bearing fields with `[redacted]`, including `properties`).
- [ ] G2-XML passes (parsed with a secure `javax.xml` factory) · [ ] redaction test scans the output for sentinel strings and finds none · [ ] *(closed in P2-3, because `core` tests must not depend on `engine`)* export → `UiAutomatorParser` → equal on the P0-7 fixtures.

**P1-4 · `SelectorEngine` (L)** — implements Appendix C. **[REGRESSION M1]**
- `SelectorEngine.suggest(index, node): SelectorReport(candidates, best, verdict, warnings)`. Each `Candidate`: strategy, neutral expression, `matches` **computed by evaluating the expression against the index**, tier, reasons.
- Evaluators for the generated subset: Android (attribute equality, index, relative path) and Windows (AutomationId / Name / ControlType / ClassName, index, path). XPath is evaluated by a small custom evaluator and **cross-checked against `javax.xml.xpath`** on fixtures in tests.
- Delete `RuleAnalyzer`'s constants and `AndroidCodegen.isFragile`: **one** fragility concept.
- [ ] G3 passes: a unique-id TextView is *Reliable*; duplicates → *Needs index* with the correct N; `ok`/`fab`/`nav`/`btn1` raise no obfuscation warning; the tree-level warning fires only when ≥ 60 % of ≥ 10 ids look like `^[a-z]{1,2}\d*$` · [ ] each candidate's `matches` equals an independent evaluation on hand-built trees (re-run on the real fixtures once P2-3 converts them) · [ ] `suggest()` on 10k nodes < 50 ms.

**P1-5 · Codegen v2 (L)** — **[REGRESSION B6, M14]**
- Per-language escapers: Python (single-quoted: `\`, `'`, newline, CR, tab), Kotlin/Java (`\`, `"`, `$` in Kotlin, control characters), XPath (`concat()` when both quote kinds occur), YAML (double-quoted), C#, PowerShell (single-quoted: double the `'`), AutoHotkey v2 (backtick escapes).
- **Targets offered per capture platform.** Android: uiautomator2 (Python) · UiAutomator (Kotlin) · Appium (Java) · Appium (Python) · Maestro (YAML) · XPath. Windows: pywinauto (`backend="uia"`) · FlaUI (C#) · AutoHotkey v2 (UIA library) · PowerShell via `Add-Type -AssemblyName UIAutomationClient` (no third-party module).
- Templates take the **active `Selector`**, not a raw node. Fixes: `u2.connect()` (no package; comment on serials); app launch via `d.app_start('<pkg>')` / `monkey -p <pkg>` / Appium `activateApp` instead of a `MainActivity` guess; pywinauto connects to the *captured window* (pid / title) instead of `title_re='.*'`.
- **Verify every template against the framework's own documentation** and cite the doc URL in a comment. If a target cannot be verified, **drop it** rather than ship a guess (known unverified: the PowerShell cmdlet names in the current code, Maestro `description:`, the AutoHotkey library name/URL).
- Add the selector verdict as a comment when the tier is below *Good*.
- [ ] G4 golden tests for every target × hostile string (`Don't allow`, `Say "hi"`, `back\slash`, two-line text, `<a & b>`, `日本語`, `$dollar`, `` `tick ``, `100%`, empty) · [ ] Python outputs pass `python3 -m py_compile` in CI · [ ] XPath outputs compile with `javax.xml.xpath` · [ ] Maestro YAML loads with SnakeYAML · [ ] no target with unverified syntax remains.

**P1-6 · Settings v2 (S)** — **[REGRESSION B12]**
- Typed `Settings` behind `SettingsStore` (§3.7), persisted as `<data>/settings.json` (atomic write, `schemaVersion`) — **no database**, so this task and P1-7 are independent. Remove dead keys. Theme "System" reads the OS dark flag.
- [ ] Round-trip of every field · [ ] unknown keys ignored · [ ] G10 passes.

**P1-7 · Storage v2 (L)** — **[REGRESSION B7, M12]**
- DB baseline = **snapshot metadata only** (drop `sessions`, `bookmarks`, `watch_rules`, `app_settings`) as schema v1, plus a migration test harness; WAL/busy-timeout pragmas; `org.sqlite.tmpdir`; `SnapshotStoreImpl` (metadata in DB, `capture.json` + `screenshot.png` on disk; writes via temp file + atomic move); retention takes a plain `RetentionPolicy` value (count / bytes / pinned) **and deletes files** — it does not read `Settings`, the caller passes the policy; move legacy `platform-tools/` once; list queries never touch capture files.
- [ ] G7: retention deletes PNG and capture files; pinned survive; listing opens no capture file (spy); killing between temp-write and move leaves no visible partial snapshot; two threads insert/list concurrently · [ ] a stub v2 migration applies to a v1 DB.

### Phase 2 — Android engine and flow (≈ 4 d)

*Phases 2 and 3 deliver **engine + ports + state holders + tests only**. Every UI element they mention (ADB consent dialog, wireless dialog, device picker, window chooser, banners) is built in Phase 4 (P4-3, P4-7, P4-8, P4-10); acceptance boxes about those UI parts close there.*

**P2-1 · `AdbLocator` + persistence (M)** — **[REGRESSION B11]**
- Order: saved path → `ANDROID_SDK_ROOT`/`ANDROID_HOME` `platform-tools` → default SDK dirs (Windows `%LOCALAPPDATA%\Android\Sdk\platform-tools` · macOS `~/Library/Android/sdk/platform-tools` · Linux `~/Android/Sdk/platform-tools`) → `PATH` → UIScope-managed copy in `<data>/tools/platform-tools`. Validate with `adb version` (5 s timeout) and keep the version string. Persist whatever the user selects or downloads. Re-validate at launch, off the UI thread.
- Prefer an adb that already exists: two different adb versions on one machine kill each other's server. Always tell the user which adb is in use.
- [ ] Locator tests on fake directory layouts · [ ] a chosen/downloaded path survives restart · [ ] UI shows path + version.

**P2-2 · `AdbClient` + device tracking (M)** — **[REGRESSION M3]**
- Everything through `ProcessRunner` with timeouts (version 5 s, devices 5 s, pair/connect 15 s, dump 20 s, screencap 10 s, getprop 5 s).
- Parse `devices -l` strictly: only lines matching `^(\S+)\s+(device|offline|unauthorized|authorizing|connecting|recovery|sideload|bootloader|host|no permissions.*)\b`; ignore daemon and mismatch noise; read `model:` / `product:` / `transport_id:`; map `no permissions` → `NoPermissions`. Lazily read `ro.product.model`, `ro.build.version.release`, `ro.build.version.sdk` (cached per serial).
- Tracking: `adb track-devices` (length-prefixed protocol, reconnect with backoff) with a 3 s polling fallback. A line like "adb server version (N) doesn't match this client" → `ADB_VERSION_MISMATCH` with a *Restart adb* action.
- [ ] G9-parse (daemon lines, mismatch line, no permissions, unauthorized, offline, `emulator-5554`, wireless `adb-<serial>-<id>._adb-tls-connect._tcp`) · [ ] tracker emits changes within 1 s on a fake server · [ ] three consecutive failures back off instead of spinning.

**P2-3 · Capture pipeline (L)** — **[REGRESSION B1, M16]**
- Hierarchy strategies, in order, with the winner cached per device: (1) `exec-out uiautomator dump /dev/stdout`; (2) `exec-out uiautomator dump /dev/tty`; (3) `shell uiautomator dump /data/local/tmp/uiscope.xml` + `exec-out cat` + delete. Slice from the first `<?xml`/`<hierarchy` to the **last** `</hierarchy>`; text outside that span (`ERROR: …`) maps to Appendix A codes. Retry twice, 300 ms apart, for idle-state / null-root errors.
- Screenshot: `exec-out screencap -p`; validate the PNG signature; a uniform image → `SECURE_WINDOW` warning. Run both **concurrently**, each with its own timeout; publish atomically.
- Parser returns `ParseResult(nodes, warnings)`; secure `DocumentBuilder` (DOCTYPE disallowed, secure-processing on); strip illegal XML characters first; keep `rotation` and **all** attributes (`checkable`, `focusable`, `long-clickable`, `password`, `selected`, `hint`, `drawing-order`, `display-id` when present); assign `ElementPath` ids; **never return an empty list to signal failure**.
- [ ] G1 passes: clean dump · `UI hierchary dumped to:` suffix · CRLF · leading `ERROR:` line · BOM · illegal characters · 3k-node tree parsed < 200 ms · malformed → typed error · [ ] all P0-7 fixtures parse and their converted captures are committed as goldens (then re-run the P1-3 round-trip and the P1-4 selector tests on them) · [ ] a timeout or cancellation kills adb · [ ] manual: emulator **and** physical phone capture succeeds `needs manual verification`.

**P2-4 · ADB download, hardened (M)** — **[REGRESSION M4]**
- User-initiated only. The consent **text** states what, where, size and source (Google's official URL); the dialog itself is built in P4-7/P4-8. Downloads go through the P0-5 `NetworkGateway`. Pinned **versioned** URLs per OS with **SHA-256** in `AdbDownloadManifest.kt`, generated by `scripts/update-platform-tools.(sh|ps1)` (downloads from `dl.google.com`, hashes, writes the manifest — run it now and on every bump).
- Download to temp → verify → extract with a ZIP-slip guard (canonical path must stay inside the target) → atomic move to `<data>/tools/platform-tools`; byte progress, cancel, retry; honour system proxy (`java.net.useSystemProxies=true`); precise errors (DNS, TLS, proxy, disk full, checksum). Mark `adb` executable on macOS/Linux.
- [ ] G9-zip: entries `../evil`, absolute paths, drive-letter paths rejected · [ ] checksum mismatch aborts and deletes the temp file · [ ] cancel leaves no partial install · [ ] the downloaded copy is found after restart via the locator (no setting needed).

**P2-5 · `AndroidInspectState` (M)** — **[REGRESSION B9]**
- Implements the §4.3 state machine; single-flight capture; Live (interval from Settings, pause when minimized, stop after 3 failures); selection/expansion restored by identity; **no UI-thread work**. Lives in `core/state` and depends only on core ports (`AdbPort`, `SettingsStore`); tested with a fake `AdbPort`.
- Extend the temporary shell with a **dev harness**: a *Capture now* button that runs the real capture pipeline, shows the element count and saves the screenshot, so the owner can sanity-check Android capture before the real UI exists (Appendix K, early check 1a).
- [ ] A unit test per transition in §4.3 including disconnect/reconnect and Live auto-pause · [ ] a test dispatcher/strict check proves no process/file/DB call happens on Main.

**P2-6 · Wireless ADB (S)** — **[REGRESSION M3]**
- Per §4.3: pairing → connect with separate ports, adb output always shown on failure, prefill from `adb mdns services`, legacy tab. (Engine only; the dialog UI is built in P4-3.)
- [ ] G9-wifi parse tests for `pair` / `connect` outputs (success, "already connected", each failure) · [ ] failures show adb's own message.

### Phase 3 — Windows engine and pick UX (≈ 8 d, highest risk)

*Environment prerequisite: a Windows host with JDK 21 and the .NET SDK (to build the `net472` helper). If the agent cannot run on Windows, the owner builds and runs the helper and reports results; Windows-only steps are skipped in Linux CI. Like Phase 2, this phase delivers engine + ports + state holders + tests only — the UI for it (Pick button, Choose window…, banners) is built in Phase 4.*

**Architecture decision D-WIN-1.** A **persistent native helper process** owns everything that touches Win32/UIA: element queries, the pick overlay, the mouse hook while picking, the hotkey, window enumeration and window screenshots. Why: (1) UIA calls into a misbehaving app can hang — a process can be killed and restarted, a JVM thread cannot; (2) a `PerMonitorV2` DPI-aware manifest makes every coordinate a physical pixel with no JVM/AWT scaling traps (B3); (3) no `powershell.exe`, no `-ExecutionPolicy Bypass`, no temp scripts; (4) fast after warm-up (no per-call spawn). The fallback — in-process JNA COM — is allowed **only** if P3-0 proves the helper cannot meet the budgets.

**P3-0 · Spike + ADR (M)**
- In a throwaway `native/spike/` folder (the real `native/windows-helper` project is created in P3-1, **after** the ADR), prototype `ElementFromPoint` and a cached subtree fetch (UIA3 `IUIAutomation` + `CacheRequest`, `TreeScope.Subtree`, only the Appendix B properties). Measure on Windows 10 and 11 against Edge (Chromium), VS Code (Electron), Calculator and Settings (UWP/WinUI), a WPF app, a WinForms app, Notepad (Win32), a Qt app and a Java Swing app. Record in `docs/adr/0001-windows-uia.md`.
- Budgets: hover query p95 ≤ 100 ms · 3,000-node window ≤ 2 s · helper cold start ≤ 500 ms · helper RSS ≤ 80 MB.
- [ ] ADR committed with a measurement table · [ ] budgets met, or the fallback chosen with reasons.

**P3-1 · Helper project and build (M)**
- C# `net472` (preinstalled on supported Windows 10/11); no runtime NuGet dependencies unless the ADR justifies one (FlaUI.UIA3 is acceptable, MIT). `app.manifest`: `PerMonitorV2`, `longPathAware`, `uiAccess=false`. Gradle task `buildWindowsHelper` (runs `dotnet build -c Release` on Windows only; skipped elsewhere) copies the exe to app resources `helper/UIScope.Helper.exe`. CI builds it on `windows-latest`.
- [ ] `./gradlew :app:packageMsi` on Windows includes the helper · [ ] non-Windows builds skip it cleanly · [ ] the helper exits when stdin closes.

**P3-2 · Protocol and client (M)**
- Newline-delimited JSON over stdin/stdout (Appendix J). Kotlin `WindowsHelperClient`: spawn, `hello` handshake with major-version check, correlation ids, event stream, per-request timeouts, kill-and-restart on timeout (max 3 restarts per minute, then `UIA_UNAVAILABLE`), clean shutdown. A `FakeHelper` implements the same protocol for tests.
- [ ] G-HELPER: timeout → restart · malformed line ignored and logged · version mismatch → clear error · events arrive in order · [ ] screenshots are passed by temp-file path, not base64.

**P3-3 · Tree + screenshot capture (L)** — **[REGRESSION B2, B3]**
- `captureWindow(hwnd)`: top-level window info (title, process name/id, `isElevated`, rect, DPI) + a cached subtree with the Appendix B properties, supported patterns and `RuntimeId`; limits depth 40 / 10,000 nodes (flag `truncated`); offscreen elements kept but marked; passwords redacted; **a failing property never drops a subtree** (per-property guard inside the helper).
- Window screenshot at physical resolution with `origin` (`PrintWindow` with `PW_RENDERFULLCONTENT` for occluded windows; fall back to a screen blit of the rect); never contains the overlay.
- [ ] G6 passes against helper-produced fixtures · [ ] Notepad, Calculator, Explorer, Edge and VS Code each yield > 10 nodes · [ ] manual calibration at 100/125/150/200 % and mixed-DPI dual monitors — rectangles land within 1 px of the real control `needs manual verification`.

**P3-4 · Pick mode (L)** — **[REGRESSION M6]**
- Overlay window(s) spanning all monitors: click-through, always on top, excluded from screenshots (`SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)` on Windows 10 2004+, otherwise hide the overlay before capturing), hover outline + label chip, throttled queries. A low-level **mouse** hook exists only during pick and swallows the pick click. `Esc` / right-click cancel; Enter picks; UIScope hides/restores; the hook is removed on every exit path.
- Add a `--demo-pick` command-line mode to the helper (draws the overlay, prints the picked element as JSON, exits) so the owner can check alignment and click-swallowing **before** the main UI exists (Appendix K, early check 2a).
- [ ] Picking a Close/Delete button in another app never activates it `needs manual verification` · [ ] the overlay never appears in captures · [ ] works across two monitors with different DPI · [ ] hover latency p95 ≤ 100 ms · [ ] a debug command shows zero hooks installed after cancel.

**P3-5 · Origin end-to-end and AWT removal (S)** — **[REGRESSION B3]**
- Carry `origin` from the helper through `Capture.screen` to `CaptureViewport` — the **single** place that maps bounds to canvas coordinates (the canvas, P4-5, uses it; state holders do not). Delete the AWT `MouseInfo`/`Robot` paths and every DPI assumption in Kotlin.
- [ ] A helper-produced capture fixture maps through `CaptureViewport` to the expected canvas coordinates (G6) · [ ] no `MouseInfo`/`Robot` use remains for Windows · [ ] *visual alignment is closed in P4-5 / Appendix K Round 2.*

**P3-6 · Hotkey (S)** — **[REGRESSION B8]**
- The helper registers the hotkey with `RegisterHotKey` (no keystroke hook). Configurable; if taken → `HOTKEY_IN_USE` notice asking for another; default `Ctrl+Alt+P`; unregistered on exit. (`GlobalHotkeyManager`, the global `HotkeyBus` events and the `jnativehook` dependency were already removed in P0-2.)
- [ ] `jnativehook` absent from the dependency graph · [ ] typing R or Ctrl+F in other apps does nothing · [ ] the hotkey works while UIScope is hidden.

**P3-7 · Window picker and hints (S)**
- `listWindows` (visible, titled, non-tool; icon, process, elevated flag) → *Choose window…*. Surface `ELEVATED_TARGET`, `LIMITED_ACCESSIBILITY`, `WINDOW_GONE`, `TREE_TRUNCATED` per Appendix A.
- [ ] Each hint/error appears in the right situation (manual list in `MANUAL_QA.md`).

**P3-8 · `WindowsInspectState` (M)** — **[REGRESSION B9]**
- State machine of §4.4; single-flight; lives in `core/state` and depends only on core ports (`WindowsInspectorPort`); tested with a fake port. The Pick button, window chooser and banners are UI (P4-3, P4-10).
- [ ] Transition tests (Idle → Picking → Capturing → Captured/Failed · refresh after the window closed · helper restart mid-capture).

**P3-9 · Static guardrails (S)**
- Add a Gradle `guardrails` task wired into `check` that fails the build on forbidden patterns **outside `legacy/`**: `ProcessBuilder` outside `engine/process`; `powershell`, `osascript`, `xdotool`, `pyatspi`, `ExecutionPolicy`; `jnativehook`; `MouseInfo` and `java.awt.Robot`; `URL(`, `HttpClient`, `openConnection` outside `NetworkGateway`. Document each rule in `docs/guardrails.md`.
- [ ] `./gradlew check` fails when a forbidden pattern is added (one test per rule) · [ ] the repository passes · [ ] closes B10 (no direct `ProcessBuilder` anywhere) and keeps B2 and B8 from coming back.
- *Non-Windows behaviour (the PC target is hidden with "Windows app inspection is available on Windows. Android inspection works on this OS.") is UI and is built and checked in P4-3.*

### Phase 4 — UI/UX build (≈ 6 d)

**P4-1 · Theme (S)**
- One `UiScopeTheme` (the old inline copy is in `legacy/`); tokens for spacing, type, shape, colour; System/Light/Dark through the OS flag; Material Icons only. Also create the `Strings` object (all user-visible copy, keyed by `AppError.code` — Appendix A).
- [ ] G-THEME contrast test passes in both themes · [ ] "System" follows the OS at runtime.

**P4-2 · Shell (M)** — **[REGRESSION M10]**
- Window-state persistence, minimum size, clamp to monitor; navigation rail; menu bar; status bar; toast host; dialog host. Single-instance hook point for P7-1.
- [ ] Size/position/maximized restored · [ ] opens at 960×600 without clipping · [ ] no screen reads settings synchronously.

**P4-3 · Inspect screen restructure (L)** — **[REGRESSION B9]**
- Build the Inspect screen from `InspectScreen`, `TargetBar` (target switch, device picker, *Choose window…*, wireless-debugging dialog), `TreePane`, `CanvasPane`, `DetailsPane` (`SelectorTab`, `PropertiesTab`, `PathTab`) — **fresh code; `legacy/` is reference only.** Composables take UI state + callbacks only (state holders from P2-5 and P3-8) and render errors with the `AppErrorView` from P4-10 (**build P4-10 first**); splitters with persisted widths; collapsible panes. On non-Windows the PC target is hidden with the empty state "Windows app inspection is available on Windows. Android inspection works on this OS."
- [ ] `ui` has no dependency on `engine` (enforced by the Gradle module graph from P0-2) · [ ] no process/file/DB/JNA call in composition · [ ] the toolbar fits at minimum width with an overflow menu · [ ] on a non-Windows build the PC target shows the explanatory empty state.

**P4-4 · Tree pane (M)** — **[REGRESSION M2]**
- Per §4.5: restoration after capture, search with highlight/count/next-prev, expand/collapse all, keyboard navigation, context menu, badges, keys by `ElementPath`.
- [ ] Live mode on a changing screen keeps expansion, scroll and selection (scripted-capture test) · [ ] search over 10k nodes < 100 ms · [ ] keyboard-only navigation works.

**P4-5 · Canvas (M)** — **[REGRESSION M13, B3]**
- Per §4.5: `CaptureViewport`, z-order hit-testing, cursor-anchored zoom, pan rules, Fit/100 %, show-all-bounds, hover tooltip, wireframe fallback. Remove double-click reset.
- [ ] G11: a dialog over an activity selects the dialog node; ties → smallest area · [ ] zoom anchors at the cursor (viewport math test) · [ ] smooth interaction on a 3k-node capture `needs manual verification` · [ ] Windows captures align at 100–200 % scaling (Appendix K, Round 2) `needs manual verification`.

**P4-6 · Selector and Properties tabs (M)**
- Per §4.6: verdict card, candidates with live `matches`, *Test selector* (highlight all matches), code block with target dropdown, copy buttons, copy-on-click values, "Copy all as JSON".
- [ ] Every candidate shows the engine's `matches` · [ ] Test selector highlights all matches · [ ] copy actions put exactly the displayed text on the clipboard (UI test).

**P4-7 · First run and Help (S)**
- Per §4.2 with **real** checks; Help pages; shortcuts sheet. Builds the ADB-download **consent dialog** (text from P2-4) that Settings (P4-8) reuses.
- [ ] On a machine without ADB, step 3 shows not-found with Download/Browse · [ ] no hard-coded "OK" rows.

**P4-8 · Settings and About (M)** — **[REGRESSION B12]**
- Per §4.9: instant apply, working controls only; About with licences, third-party notices, links; Copy diagnostics; Reset. *Check for updates* is hidden when `AppInfo.isStoreBuild` (P0-4).
- [ ] UI test: changing the theme applies without restart · [ ] every control has an observable effect (checklist in `MANUAL_QA.md`) · [ ] About shows the `BuildInfo` version.

**P4-9 · Accessibility and shortcuts (S)**
- Content descriptions, tooltips, focus order, `onPreviewKeyEvent` shortcuts per §4.11 (**no single-letter shortcuts**), menu accelerators.
- [ ] The core loop is completable keyboard-only · [ ] an Accessibility Insights FastPass run is recorded in `docs/a11y.md` with remaining known gaps.

**P4-10 · Messages (S)** — **[REGRESSION M11]**
- `AppErrorView` + Appendix A catalogue in `Strings`; banner and full-screen variants; *Copy details*. P4-3 needs it, so build it first (it only needs `Strings` from P4-1 and `AppError` from P0-5).
- [ ] Every Appendix A code has copy and an action · [ ] an error during Live shows a banner and keeps the last capture.

### Phase 5 — Snapshots and export (≈ 3 d)

**P5-1 · Snapshots screen (M)** — per §4.7
- List/search/sort, preview, *Open in Inspector* (offline, read-only), rename/note/pin, delete (files too), export/import `.uiscope`, drag-and-drop import with validation.
- [ ] Opening a snapshot reproduces tree, screenshot and selector verdicts offline · [ ] import rejects wrong `schemaVersion`, > 100 MB, path-traversal entries, missing `capture.json` · [ ] delete removes files (test).

**P5-2 · Export dialog (M)** — per §4.8
- JSON / XML / Outline / PNG, clipboard, redaction, native dialogs off the UI thread.
- [ ] Copying a 5k-node export blocks the UI < 100 ms · [ ] a redacted export contains none of the original text (sentinel scan).

**P5-3 · Retention (S)** — **[REGRESSION B7]**
- Enforce on save and at startup (off the UI thread); storage meter in Settings.
- [ ] B7 test green · [ ] auto-save + Live for 10 minutes stays under the configured cap.

### Phase 6 — Compare (optional for 1.0; required for 1.1)

*The Diff code deleted in P0-2 was unsalvageable (see B5). This phase rebuilds it properly. Keep it behind `FeatureFlags.diff` until both tasks pass.*

**P6-1 · Matching engine (L)** — **[REGRESSION B5]**
- Build an `ElementIndex` for A and B and match in passes: (1) identical path + class + identifier; (2) identifier + class + occurrence index among nodes sharing that identifier; (3) class + text/description + matched parent; (4) bounds IoU ≥ 0.7 + same class. Whatever remains is *Added* / *Removed*. For matched pairs diff **every** field (text, description, flags, bounds moved/resized, properties).
- [ ] G12: nested trees, duplicate ids, nodes without ids, moved nodes, text change, list item inserted · [ ] 3k vs 3k nodes < 500 ms · [ ] Windows captures supported.

**P6-2 · Compare UI (M)**
- Pick two snapshots (or live vs snapshot); side-by-side screenshots with coloured boxes; filter chips (Added / Removed / Changed / Moved); clicking locates the element in both; export the diff as JSON/text.
- [ ] Flag removed only after P6-1 and P6-2 pass · [ ] docs and Store copy mention Compare only then.

**Watch mode is not part of this plan.** If it returns, it should be a "wait for / assert element" helper that generates code, not a background monitor.

### Phase 7 — Hardening, security, performance (≈ 3 d)

**P7-1 · Single instance (S)** — **[REGRESSION M15]** — lock file or local socket in `<data>`; a second launch focuses the first window and exits.
**P7-2 · Crash handling (S)** — uncaught-exception handler → log + dialog (*Copy diagnostics* / *Restart*); a helper crash is handled as `UIA_UNAVAILABLE` with automatic restart.
**P7-3 · Security pass (M)**
- Secure XML parsers everywhere (P2-3 audited across the repo) · ZIP-slip guards on download **and** `.uiscope` import · process spawning with argument lists only (grep for `sh -c`, `cmd /c`) · the user-supplied adb path is only ever executed with `version` and never through a shell · file writes only to the data dir or user-chosen paths · size caps on imports · dependency vulnerability scan in CI (OWASP dependency-check or GitHub dependency review).
- [ ] `docs/SECURITY_CHECKLIST.md` fully ticked.

**P7-4 · Performance pass (M)** — profile with 3k- and 10k-node trees on both platforms against Appendix F; move image decoding off the UI thread; compute flat lists/indexes once per capture; lazy property sections; measure cold start; record numbers in `docs/perf.md` with the reference hardware.
**P7-5 · Update check v2 (S)** — **[REGRESSION M17]**
- Setting is opt-out; semantic-version compare (`latest > current` only); repo/URLs from `AppInfo`; real JSON parsing; **disabled** when `AppInfo.isStoreBuild` (P0-4); uses the P0-5 `NetworkGateway`; never downloads anything; shows a "What's new" link; failures are logged, not shown.
- [ ] Unit tests for version compare and the "disabled in Store build" switch.
**P7-6 · Network inventory (S)** — the `NetworkGateway` already exists (P0-5) and the ADB download (P2-4) and update check (P7-5) use it; this task adds a test/doc listing the only two allowed actions (GitHub releases API when enabled; `dl.google.com` on a user click) and fixes any bypass the P3-9 guardrail reports.
- [ ] Code search for `URL(`/`HttpClient`/`openConnection` outside the gateway finds nothing.

### Phase 8 — Packaging, compliance, launch assets (≈ 5 d)

**P8-1 · jpackage configuration (M)** — **[REGRESSION M9]**
- **Delete `legacy/`** — the new code now covers everything it was kept for.
- (The MSIX is the primary deliverable; MSI/EXE are the optional GitHub channel and share this configuration.) Replace `includeAllModules` with an explicit `modules(...)` list (start from `suggestRuntimeModules`; it must cover SQLite JDBC, serialization, AWT/Swing file dialogs, logging). Move `store/*.png` artwork out of runtime resources into `branding/`. Verify `.ico` (multi-size), `.icns`, PNG. Ship `LICENSE` and `THIRD_PARTY_NOTICES.md` inside the app. Per-user MSI/EXE; **keep `upgradeUuid` stable forever**.
- [ ] Installed app launches on clean Windows 10 (1809+) and Windows 11 VMs with **no JDK installed** · [ ] installed size recorded (target ≤ 150 MB) · [ ] icons correct in Start menu, taskbar and installer.

**P8-2 · MSIX (M)**
- Create `scripts/build-msix.ps1`: lay out the app image as `UIScope\`, inject the version into `AppxManifest.xml` (**the Store requires the 4th segment to be 0**), `makeappx pack`, optional `signtool` for sideload testing. **Do not change `Identity Name/Publisher`** — they are reserved in Partner Center. Capability: `runFullTrust` only. Set `MinVersion` to `10.0.17763.0` unless testing shows otherwise. Produce `UIScope-<ver>-x64.msix` (+ `.msixupload` if required). Run the **Windows App Certification Kit** and attach the report. For local sideload and WACK runs the script's `-TestSign` switch creates a throwaway self-signed certificate (never published, never committed); the package uploaded to Partner Center is **unsigned** — the Store signs it.
- [ ] MSIX installs and runs via sideload on a clean VM · [ ] WACK passes · [ ] manifest version = `version.txt`.

**P8-3 · Release CI (M)**
- `.github/workflows/release.yml` on tag `v*`: build the helper, MSI, EXE and MSIX on `windows-latest`; compute `SHA256SUMS`; create a GitHub Release with notes from CHANGELOG. The MSIX is built **unsigned** for Store upload (Partner Center signs it); a throwaway self-signed variant is produced only for local sideload/WACK testing and is never published.
- [ ] Tagging a test version yields a draft release with all artifacts and checksums.

**P8-4 · Distribution and signing (S)** — **Primary channel: Microsoft Store.** Microsoft signs the MSIX on publication; **do not buy, configure or script a code-signing certificate.** (The throwaway test certificate for sideload/WACK runs is created by `build-msix.ps1 -TestSign` in P8-2.) The optional GitHub MSI/EXE downloads are unsigned by design: the README explains that Windows SmartScreen may warn on first run ("More info → Run anyway").
**P8-5 · Store listing (S)** — finalize Appendix I (truthful), ≥ 4 real 1920×1080 screenshots, icons/tiles, category *Developer tools*, price Free, age-rating answers, privacy URL, support URL/email, certification notes.
**P8-6 · Legal and policy docs (S)** — `PRIVACY.md` (what is stored locally, the two network actions, no telemetry) **published at a public URL** (default: the repository's `PRIVACY.md` page on GitHub — no extra hosting); `THIRD_PARTY_NOTICES.md` generated by a Gradle license report and shown in About; `SECURITY.md`; `CONTRIBUTING.md`; trademark note (Android is a trademark of Google LLC; Windows is a trademark of Microsoft Corporation; no endorsement implied); keep the MIT licence and confirm bundled dependency licences are compatible.
**P8-7 · Documentation (M)**
- README (what it is, screenshots/GIF, install options, 60-second quick-start per target, supported OS, FAQ) · `docs/troubleshooting.md` (Windows OEM/Google USB driver, Linux udev rule, unauthorized device, wireless debugging, elevated windows, Chromium/Electron/Qt/Java accessibility switches, two adb versions fighting) · corrected CHANGELOG · issue templates (the bug template asks for *Copy diagnostics* output) · `docs/MANUAL_QA.md`.
- [ ] A stranger can install from the listing or GitHub release and complete the core loop using only the README.

**P8-8 · Non-Windows builds (S)** — stop publishing macOS/Linux artifacts for 1.0 (keep building them in CI for 1.1 preparation); the README states "macOS and Linux (Android inspection only) — planned for 1.1".
- [ ] Every statement in README and Store copy is verifiable in the shipped build.

### Phase 9 — QA, beta, release gate (≈ 5 d + beta time)

**P9-1 · Execute the QA matrix** (Appendix E); record results in `docs/qa-report-1.0.md`.
**P9-2 · Beta** — 5–10 external testers (QA engineers / Android developers) for ≥ 1 week via a GitHub pre-release or a Store flight; short feedback form; measure the success targets of §1.3. Findings become fix tasks that re-enter their owning task, followed by a re-run of P8-2/P8-3 and the affected QA rows; **at most two beta rounds**; documentation and screenshots are frozen after P9-1 and re-checked once at go/no-go.
**P9-3 · Go / no-go** — all of §6 ticked, no open blocker or major, crash-free beta, owner sign-off.

---

## 6. Launch readiness — Definition of Done for v1.0

**Product**
- [ ] The core loop works on Windows and Android, with a verdict and code for every offered target.
- [ ] Shipped scope equals §1.4; nothing that was cut is reachable or mentioned anywhere.

**Quality**
- [ ] `./gradlew check` is green on Windows and Linux in CI; every Appendix G test is enabled and passing.
- [ ] detekt strict passes on all code: no empty catch, no swallowed exceptions.
- [ ] QA matrix executed; no open blocker or major issue.

**Performance** — [ ] every budget in Appendix F met on the recorded reference machines.

**Security and privacy**
- [ ] No keystroke hook anywhere; the mouse hook exists only during pick.
- [ ] The only network actions are the opt-out update check and the user-initiated ADB download.
- [ ] ZIP-slip and XXE fixed; `docs/SECURITY_CHECKLIST.md` ticked; no sensitive data in logs.

**Legal** — [ ] licence, third-party notices, public privacy-policy URL, trademark note, ADB-download consent text.

**Packaging** — [ ] MSIX installs, runs and uninstalls cleanly on clean VMs via sideload · [ ] WACK passes · [ ] optional MSI/EXE (GitHub) install, upgrade (same `upgradeUuid`) and uninstall cleanly · [ ] the README carries the SmartScreen note for the unsigned GitHub downloads.

**Store** — [ ] listing, screenshots, age rating, privacy URL, certification notes ready · [ ] the Store build has the update check disabled.

**Docs and support** — [ ] README, troubleshooting and CHANGELOG truthful · [ ] issue templates · [ ] support address monitored.

**Operations**
- [ ] Release checklist: tag → CI artifacts → Store submission → GitHub release → announcement.
- [ ] Rollback plan: keep the previous MSI/MSIX; know how to unpublish a Store submission.
- [ ] After launch: daily triage for two weeks; a 1.0.1 hotfix path (branch + tag + CI) is rehearsed.

### 6.1 Risks and mitigations

| Risk | Likelihood / impact | Mitigation |
|---|---|---|
| Windows helper is harder than estimated (UIA quirks per framework) | High / High | P3-0 spike first; budgets and ADR; per-property guards; hints for limited-accessibility apps; scope Windows tree features to what the matrix proves |
| Antivirus / SmartScreen false positives | Medium / High | No keystroke hook, no PowerShell, no `-ExecutionPolicy Bypass`; mouse hook only while picking; Store distribution (Microsoft-signed package); SmartScreen note for the unsigned GitHub downloads |
| Store rejects the ADB download | Medium / Medium | User-initiated, consent text, official URL, checksum, explained in certification notes; fallback: Store build = detect/browse only, download only in direct builds |
| `uiautomator dump` unreliable on some devices/screens | High / Medium | Strategy fallbacks, retries, error catalogue, honest hints; no promise beyond what the dump can do |
| JVM startup/size feels heavy | Medium / Low | Explicit modules, `-Xmx` tuning, startup budget in Appendix F |
| DPI / multi-monitor edge cases | High / Medium | Helper owns coordinates (PerMonitorV2); calibration QA at 100–200 % and mixed DPI |
| Scope creep from the cut features | Medium / Medium | §1.4 is binding; ideas go to `docs/proposals.md` |

---

## 7. Decisions already made, and what only the owner can supply

### 7.1 Decisions (do not reopen)

| ID | Decision |
|---|---|
| D1 | **Windows first.** Android works on all OSes but ships first on Windows only; macOS/Linux (Android-only) in 1.1. |
| D2 | v1 scope is §1.4: iOS, Watch, multi-window, tray, bookmarks and macOS/Linux PC inspection are cut; Diff is hidden until rebuilt. |
| D3 | Windows engine = persistent native helper (C#, `net472`, PerMonitorV2); in-process JNA COM only as an ADR-justified fallback. |
| D4 | Remove `jnativehook`; the global hotkey uses `RegisterHotKey` (default `Ctrl+Alt+P`). |
| D5 | No telemetry in 1.x. Crash information stays local; users copy diagnostics themselves. |
| D6 | Update check is opt-out, GitHub releases only, and disabled in the Store build. |
| D7 | ADB: prefer an existing install; download is user-initiated, version-pinned and checksum-verified; **not bundled** (licence/Store-policy caution — revisit after launch; this is not legal advice). |
| D8 | User data lives in the OS app-data directory; old `~/.uiscope` sessions are not migrated. |
| D9 | Free, MIT licence, no ads, no accounts. |
| D10 | Name "UIScope"; Store name "UIScope: Desktop & Android UI Inspector" (already reserved in the manifest). |
| D11 | Version number: **1.0.0** (owner confirmed that no earlier version was ever published). |
| D12 | English only for v1; all strings centralised for later localization. |
| D13 | **Distribution:** Microsoft Store first; Microsoft signs the MSIX; no code-signing certificate for 1.0. GitHub MSI/EXE are an optional, unsigned secondary channel. |
| D14 | **Identity:** publisher TBTechs, developer TITANICBHAI, repository `TITANICBHAI/UIScope-jvm`; package namespace `com.titanicbhai.uiscope` unchanged. |

### 7.2 Owner answers and what is still open

**Answered (already applied throughout this plan):** publisher is TBTechs (solo developer TITANICBHAI) · repository `https://github.com/TITANICBHAI/UIScope-jvm` · v1.0.0 was never published, so ship **1.0.0** · no code signing — the Store signs the MSIX · the owner has a Windows PC and an Android phone.

**Still open — none of these block the agent (use the stated default):**

1. **Manual checks by the owner.** The implementation agent can write and unit-test code, but it cannot see your screen or hold your phone. Items marked `needs manual verification` therefore need *you* to run a short checklist on your own Windows PC and Android phone and report pass/fail (steps in **Appendix K**; roughly 30–40 minutes per round — after Phase 4 (Android and Windows), after Phase 5 and before release — plus a 10-minute fixture capture at the very start and two optional 10-minute early checks). Your normal PC and phone are enough. Optional extras that make the Windows checks stronger: set display scaling to 125 % or 150 % (Settings → System → Display → Scale) and use a second monitor if you have one.
2. **Support contact.** *Default:* the GitHub Issues page is the Store support link. `DISTRIBUTION.md` lists `uiscope@titanicbhai.dev` — add it to the listing only if it is a real inbox you read.
3. **Store submission.** The agent prepares everything (package, listing text, screenshots, privacy URL, certification notes); the owner clicks *Submit* in Partner Center.
4. **Confirm the cuts in §1.4**, especially Watch and multi-window *(default: cut)*.
5. *Optional:* a trademark/name search for "UIScope" in the software class before investing in branding.

---

## 8. Appendices

### Appendix A — Error and message catalogue

Every message = **title · what happened · likely cause · primary action · [Copy details]**. Codes are `AppError.code`. "Warning" = shown as a chip/banner without discarding data.

**Android**

| Code | When | Title / what to tell the user | Primary action |
|---|---|---|---|
| `ADB_NOT_FOUND` | Locator finds no working adb | "ADB not found" — UIScope needs Android's adb tool to talk to your device. | Download platform-tools · Browse… · How to install |
| `ADB_VERSION_MISMATCH` | Output contains "adb server version (N) doesn't match this client" | "Another adb is running" — Android Studio or another tool started a different adb version. | Restart adb |
| `ADB_START_FAILED` | Server won't start / port 5037 busy | "adb couldn't start" — show adb's message. | Retry · Copy details |
| `NO_DEVICE` | No devices listed | "No device found" — checklist: data cable (not charge-only), USB debugging on, File-transfer mode, Windows: install the phone maker's or Google USB driver. | Rescan · Connect over Wi-Fi… |
| `DEVICE_UNAUTHORIZED` | State `unauthorized` | "Allow USB debugging on the phone" — unlock it and tap Allow on the prompt (tick *Always allow*). | Rescan |
| `DEVICE_OFFLINE` | State `offline`/`connecting` | "Device offline" — unplug and replug, or toggle USB debugging. | Restart adb |
| `NO_PERMISSIONS` | Linux `no permissions` | "Linux can't access the device" — add a udev rule for your phone's vendor id and replug. | Copy udev rule · Open guide |
| `DUMP_NULL_ROOT` | `null root node` | "The screen can't be read right now" — the app is transitioning or blocks accessibility. | Retry |
| `DUMP_IDLE_TIMEOUT` | `could not get idle state` after 2 auto-retries | "The screen keeps animating" — pause the video/animation/spinner and retry. | Retry |
| `UIAUTOMATOR_BUSY` | UiAutomation already registered/connected | "Another automation tool is using the device" — stop Appium / uiautomator2 / Maestro sessions on this device. | Retry |
| `DUMP_TIMEOUT` | 20 s elapsed | "Timed out reading the screen" — the device is slow or busy. | Retry |
| `SCREENSHOT_FAILED` | Bad exit code or invalid PNG | "Couldn't take a screenshot" — the tree is still shown if available. | Retry |
| `SECURE_WINDOW` *(warning)* | Uniform screenshot, or only systemui/keyguard nodes | "This screen may block capture" — banking/DRM apps and locked screens hide content; the tree may be incomplete. | Dismiss |
| `PARSE_FAILED` | XML invalid after slicing | "Couldn't read the UI dump" | Copy details |
| `WIRELESS_PAIR_FAILED` / `WIRELESS_CONNECT_FAILED` | adb pair/connect failed | Show **adb's own message** + hints (same Wi-Fi network, pairing code expires, the *connect* port differs from the pairing port). | Retry |

**Windows**

| Code | When | Title / what to tell the user | Primary action |
|---|---|---|---|
| `UIA_UNAVAILABLE` | Helper can't start or crashed 3× in a minute | "Windows inspection isn't available" — UIScope's helper couldn't start (possibly blocked by security software). | Retry · Copy details |
| `ELEVATED_TARGET` | Target is elevated, UIScope is not | "That window runs as administrator" — Windows stops normal apps from reading elevated windows. Close UIScope, right-click it and choose *Run as administrator*. | Dismiss |
| `WINDOW_GONE` | hwnd invalid | "The window was closed" | Pick again |
| `WINDOW_MINIMIZED` | Target is minimized | "The window is minimized" — restore it for a screenshot (the tree was still read). | Dismiss |
| `LIMITED_ACCESSIBILITY` *(hint)* | < 5 nodes for a window > 300×300 px | "This app exposes little accessibility information" — Chromium/Electron: start with `--force-renderer-accessibility`; Qt: set `QT_ACCESSIBILITY=1`; Java: enable the Java Access Bridge. | Open guide |
| `TREE_TRUNCATED` *(warning)* | Depth/node limit hit | "Tree truncated at 10,000 elements" | Dismiss |
| `HOTKEY_IN_USE` | `RegisterHotKey` failed | "That shortcut is taken" — another app uses it; choose a different one. | Change shortcut |

**General**

| Code | When | Title | Primary action |
|---|---|---|---|
| `SNAPSHOT_SAVE_FAILED` / `DISK_FULL` | Write failed | "Couldn't save the snapshot" (+ reason) | Retry |
| `SNAPSHOT_IMPORT_INVALID` | Bad schemaVersion / too large / path traversal / missing capture | "That file can't be imported" (+ specific reason) | — |
| `EXPORT_FAILED` | Write/clipboard failed | "Couldn't export" (+ reason) | Retry |
| `UNEXPECTED` | Anything uncaught | "Something went wrong" | Copy diagnostics · Restart |

### Appendix B — Windows (UI Automation) properties

| UIA property | Stored as | Notes |
|---|---|---|
| Name | `name` | May be localized; shown as "Name" |
| AutomationId | `identifier` | May be empty; shown as "Automation ID"; numeric-only ids are Win32 control ids |
| ControlType | `properties["ControlType"]` | `ControlType.` prefix stripped; drives icon/kind |
| LocalizedControlType | `properties["LocalizedControlType"]` | |
| ClassName | `className` | |
| FrameworkId | `properties["FrameworkId"]` | Win32 / WPF / WinForm / XAML / Chrome … |
| ProcessId (+ process name) | `properties["ProcessId"]`, `target.window.processName` | |
| BoundingRectangle | `bounds` | **Physical screen pixels**; offscreen → `flags.offscreen` |
| IsEnabled, IsOffscreen, IsKeyboardFocusable, HasKeyboardFocus, IsContentElement, IsControlElement | `flags` / `properties` | |
| IsPassword | `flags.password` | **Value is never read** when true |
| HelpText, AccessKey, AcceleratorKey, ItemStatus, ItemType | `properties` | |
| RuntimeId | `properties["RuntimeId"]` | Identity within a session; used to relocate the picked element |
| NativeWindowHandle | `properties["Hwnd"]` | Hex |
| Supported patterns (Invoke, Value, Toggle, ExpandCollapse, SelectionItem, Scroll, Window, …) | `properties["Patterns"]` | Comma-separated |
| Value (ValuePattern, non-password, ≤ 200 chars), Toggle state, ExpandCollapse state | `text` / `properties` | |

### Appendix C — Selector rules (deterministic)

**Candidate generation** (each candidate is then *evaluated against the capture*; `matches` is the real count):

| # | Android | Windows |
|---|---|---|
| 1 | resource-id | AutomationId |
| 2 | content-desc | ControlType + Name |
| 3 | text | ClassName + Name |
| 4 | resource-id + text | AutomationId + occurrence index |
| 5 | class + text / class + content-desc | anchor (unique AutomationId or ControlType+Name) + relative path |
| 6 | resource-id + occurrence index | absolute path from the window |
| 7 | anchor (nearest ancestor with a unique resource-id) + relative path | coordinates (last resort) |
| 8 | absolute path | — |
| 9 | coordinates (last resort) | — |

**Tiers**

- **Reliable** — `matches == 1`, the attribute is the platform identifier, and the tree is not flagged obfuscated. (Numeric-only Windows AutomationIds → **Good**.)
- **Good** — `matches == 1` via content-desc, or text/Name that is neither dynamic nor flagged.
- **Needs index** — the attribute is shared by N > 1 elements; an index candidate matches exactly 1 by construction but breaks if the list changes.
- **Fragile** — path-only, class-only or coordinates. Coordinates are shown under "Last resort" and are never the best candidate.
- A candidate with `matches ≠ 1` can never be Reliable or Good.

**Warnings** (chips, not tiers)

| Warning | Trigger |
|---|---|
| `TEXT_LOCALIZED` | Any text/Name-based candidate: "depends on the app language" |
| `TEXT_DYNAMIC` | Text contains 2+ consecutive digits, a time/date pattern, a currency symbol, `%`, `…`, or is longer than 40 characters |
| `IDS_OBFUSCATED_TREE` | ≥ 10 identifiers in the capture and ≥ 60 % match `^[a-z]{1,2}\d*$` after the `/` — **tree-level only; never flag a single short id** |
| `ID_NUMERIC_ONLY` | Windows AutomationId is all digits |

**Ranking:** Reliable > Good (content-desc) > Good (text) > Needs index > Fragile; within a tier prefer the shorter expression. Equality is exact and case-sensitive. Expressions are stored in a neutral form (`Attr`, `Index`, `Anchor`, `Path`) and rendered by codegen.

### Appendix D — Capture JSON, schema v1

Persisted inside snapshots and exported as JSON. Unknown keys are ignored on read; a higher `schemaVersion` is rejected with a clear message. `screenshot` is a relative file name inside a snapshot folder or `.uiscope` bundle. The device `serial` is stored locally but **omitted from exports and bundles**.

```json
{
  "schemaVersion": 1,
  "app": { "name": "UIScope", "version": "1.0.0" },
  "capturedAt": "2026-10-15T09:41:12Z",
  "target": {
    "type": "android",
    "device": { "serial": "R58M12ABCDE", "model": "Pixel 7", "androidVersion": "14", "sdk": 34 },
    "foregroundPackage": "com.example.app"
  },
  "screen": { "width": 1080, "height": 2400, "originX": 0, "originY": 0 },
  "screenshot": "screenshot.png",
  "warnings": [],
  "roots": [
    {
      "path": "0",
      "className": "android.widget.FrameLayout",
      "name": null,
      "identifier": null,
      "text": null,
      "contentDescription": null,
      "bounds": { "x": 0, "y": 0, "width": 1080, "height": 2400 },
      "flags": { "enabled": true, "clickable": false, "scrollable": false, "focused": false,
                 "checked": null, "offscreen": false, "password": false },
      "properties": { "package": "com.example.app", "index": "0" },
      "children": []
    }
  ]
}
```

Windows variant of `target` (with `screen.originX/originY` = screen position of the screenshot's top-left, in physical pixels):

```json
"target": {
  "type": "windows",
  "window": { "title": "Untitled - Notepad", "processName": "notepad.exe", "pid": 4242,
              "frameworkId": "Win32", "isElevated": false }
}
```

### Appendix E — QA matrix

**Windows**

| Dimension | Values |
|---|---|
| OS | Windows 10 1809 (minimum) · Windows 10 22H2 · Windows 11 (current) |
| Display scale | 100 % · 125 % · 150 % · 200 % |
| Monitors | single · dual same DPI · dual mixed DPI (e.g. 150 % + 100 %) · a monitor to the left of / above the primary (negative coordinates) |
| Target apps | Notepad (Win32) · Calculator and Settings (UWP/WinUI) · File Explorer · Edge and Chrome (Chromium) · VS Code (Electron) · a WPF app · a WinForms app · a Qt app · a Java Swing app · an elevated app (Task Manager, regedit) |
| Scenarios | pick via hotkey / button / Choose window · cancel pick (Esc, right-click) · click-swallowing on a destructive button · minimized and closed targets · helper killed mid-capture · tree > 10,000 nodes · password field · overlay absent from screenshots |

**Android**

| Dimension | Values |
|---|---|
| Devices | emulator API 26 / 30 / 34 · Pixel · Samsung (One UI) · Xiaomi/HyperOS or similar (extra USB-debugging security toggles) |
| Connection | USB · Wi-Fi pairing (Android 11+) · legacy `adb connect` · multiple devices · unplug during Live · reconnect |
| Apps | Settings · XML-views app · Jetpack Compose app · WebView page · React Native app · Flutter app · a Unity game (poor tree expected) · a FLAG_SECURE app · lock screen · rotation · dialog over activity |
| adb situations | none installed · Android Studio's adb present (version mismatch) · custom path · download path · Linux with and without udev rule |

**Installers and platform**

Clean install · per-user install without admin · upgrade from the previous build (same `upgradeUuid`) · uninstall (data kept/removed as documented) · MSIX sideload install · **WACK** · first run with no internet · first run behind a proxy · low disk space · non-ASCII user name and install path.

**Product quality:** Accessibility Insights FastPass on UIScope · keyboard-only walkthrough of the core loop · light and dark theme · 960×600 minimum window · text scaling 150 %.

### Appendix F — Performance budgets

| Metric | Budget |
|---|---|
| Cold start to interactive | ≤ 3 s on a mid-range laptop with an SSD |
| UI thread | no frame blocked > 100 ms during capture, Live, search, export |
| Android capture (dump + screenshot over USB) | p95 ≤ 3.5 s (dominated by the device's uiautomator) |
| Windows hover query | p95 ≤ 100 ms |
| Windows window capture (3,000 nodes) | ≤ 2 s |
| Helper cold start / resident memory | ≤ 500 ms / ≤ 80 MB |
| Tree search (10,000 nodes) | ≤ 100 ms |
| `SelectorEngine.suggest` (10,000 nodes) | ≤ 50 ms |
| `ElementIndex` build (10,000 nodes) | ≤ 100 ms |
| Snapshot save | ≤ 300 ms, off the UI thread |
| Memory, Live for 10 minutes on a 3,000-node screen | ≤ 400 MB resident, no upward trend |
| Disk, auto-save + Live for 10 minutes | within the retention cap |
| Installed size | ≤ 150 MB (record the actual value) |

### Appendix G — Regression tests and repros

Write each test **first, inside its owning task** (the *Fixed by* column): red for the stated reason, then fix, then green. "Fails today because" describes the old behaviour; for a new API add a stub first so the test fails on its assertion, not on a compile error.

| ID | Test class (module) | Asserts | Fails today because | Fixed by |
|---|---|---|---|---|
| G1 | `UiAutomatorParserTest` (engine) | clean dump → 2 nodes; dump + `UI hierchary dumped to:` suffix → 2 nodes; CRLF; leading `ERROR:` line; BOM; illegal XML chars; 3k nodes < 200 ms; malformed → typed error, **not** an empty list | suffix case returns 0 nodes (B1) | P2-3 |
| G2 | `JsonCodecTest`, `XmlExporterTest` (core) | text containing `\n`, `\t`, `"`, `\`, U+0000–U+001F, emoji/surrogates round-trips; XML parses with a secure factory | invalid JSON: *Invalid control character* (B4) | P1-2, P1-3 |
| G3 | `SelectorEngineTest` (core) | unique-id TextView → Reliable and not fragile; duplicates → Needs index with correct N; `ok`/`fab`/`nav`/`btn1`/`tab2` raise no warning; tree-level obfuscation only at ≥ 60 % of ≥ 10 ids; every `matches` equals independent evaluation | score 52 → WEAK/fragile; Strong unreachable; short ids flagged (M1) | P1-4 |
| G4 | `CodegenGoldenTest` (core) | every target × hostile strings (`Don't allow`, `Say "hi"`, `back\slash`, two-line text, `<a & b>`, `日本語`, `$dollar`, `` `tick ``, `100%`, empty); Python `py_compile`; XPath compiles; YAML loads | generated code invalid (B6) | P1-5 |
| G5 | `ElementIdentityTest` (core) | path ids unchanged when bounds change; relocation by path/identity after a recapture | ids embed bounds (M2) | P1-1 |
| G6 | `CaptureViewportTest` (core) | window at screen (300,120), scale 1.5: absolute bounds → canvas coordinates and back within 0.5 px; zoom anchored at cursor | no origin subtraction (B3) | P1-1, P3-5 |
| G7 | `SnapshotStoreTest` (engine) | retention deletes PNG and capture files; pinned survive; listing opens no capture file; torn write invisible; concurrent insert/list | files never deleted; trees loaded for lists (B7, M12) | P1-7 |
| G8 | `ProcessRunnerTest` (engine) | timeout and cancellation kill the process tree; 50 MB stdout; stderr separate | no runner or timeouts exist yet (B10) — closes when the engines adopt it (P2-2…P2-4) and the P3-9 guardrail enforces it | P0-5 |
| G9 | `AdbParseTest`, `AdbLocatorTest`, `AdbDownloaderTest`, `AdbWifiParseTest` (engine) | see fixture below; locator order and persistence; ZIP-slip rejection; checksum mismatch; pair/connect outputs | blind `drop(1)`; path not persisted; unchecked zip entries (M3, M4, B11) | P2-1/2/4/6 |
| G10 | `SettingsTest` (core/ui) | every field round-trips; "System" follows OS; changing theme applies immediately | System = dark; Back reads stale settings (B12) | P1-6, P4-8 |
| G11 | `HitTestTest` (core) | dialog over activity selects the dialog node; ties → smallest area | picks max depth (M13) | P4-5 |
| G12 | `DiffMatchTest` (core) | nested trees, duplicate ids, nodes without ids, moved nodes, text change, inserted list item | sees 0 nodes (B5) | P6-1 |
| G13 | `UiSmokeTest` (ui) | app starts on Inspect; each empty state; error component with action; settings apply instantly | — | Phase 4 |

**Observed today (what "red" looks like)** — produced by compiling the project's own `core` classes with Kotlin 2.0.0 / JDK 21:

```text
unique-id TextView  : score=52 tier=WEAK   fragile=true      # should be Reliable
unique-id clickable : score=60 tier=MEDIUM fragile=false     # best reachable; Strong needs 80
obfuscation warning for ok / btn1 / tab2 / fab / nav : true  # false positives
el = d(text='Don't allow')                                    # invalid Python
//android.widget.Button[@text='Don't allow' and @package='com.app']   # invalid XPath
val el = device.findObject(By.text("Say "hi""))               # invalid Kotlin
d = u2.connect()  # or u2.connect('com.app')                  # package passed as a serial
json.load(...)  ->  Invalid control character at: line 7 column 22   # text "Line one\nLine two"
UiAutomatorParser: clean dump -> 2 nodes ; dump + "UI hierchary dumped to: /dev/stdout" -> 0 nodes
```

**G1 fixture** (single line; the trailing text is the standard `uiautomator dump` message):

```text
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0"><node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.app" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,2400]"><node index="0" text="Hello" resource-id="com.app:id/title" class="android.widget.TextView" package="com.app" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][100,100]" /></node></hierarchy>UI hierchary dumped to: /dev/stdout
```

**G9 `devices -l` fixture** (expect exactly 6 devices; the mismatch line is noise → `ADB_VERSION_MISMATCH` signal, not a device):

```text
* daemon not running; starting now at tcp:5037
* daemon started successfully
List of devices attached
R58M12ABCDE            device usb:1-1 product:o1sxxx model:SM_G991B device:o1s transport_id:3
emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1
0123456789ABCDEF       no permissions (user in plugdev group; are you root?); see [http://developer.android.com/tools/device.html]
adb-R58M12ABCDE-aBcDeF._adb-tls-connect._tcp device product:o1sxxx model:SM_G991B device:o1s transport_id:4
ZY22XXXXXXXX           unauthorized usb:1-2 transport_id:5
192.168.1.20:5555      offline product:o1sxxx model:SM_G991B device:o1s transport_id:6
adb server version (41) doesn't match this client (40); killing...
```

**B2 repro (PowerShell)** — observed on PowerShell 7.6.7; confirm on Windows PowerShell 5.1. Moot once the PowerShell code is deleted (P3-9), but it is the reason for the rebuild:

```powershell
function Walk($el, $pid, $d) { "walk-body-ran pid=$pid" }
try { Walk 1 2 3 } catch { "CAUGHT: " + $_.Exception.Message }
# CAUGHT: Cannot overwrite variable pid because it is read-only or constant.
```

**B5 repro (Diff parser, ported to Python)** — on exporter-format JSON for a root node with id-bearing descendants it prints `0`; give the root a resource-id and it returns only the root:

```python
import re
def extract_nodes(tree_json):               # port of DiffScreen.extractNodes
    res, depth, start = {}, 0, 0
    rid = re.compile(r'"resourceId"\s*:\s*"([^"]*)"')
    for i, ch in enumerate(tree_json):
        if ch == '{':
            if depth == 0: start = i
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:                   # only the OUTERMOST object (the root, with all children inside)
                m = rid.search(tree_json[start:i+1])
                if m and m.group(1).strip() and m.group(1) not in res:
                    res[m.group(1)] = True
    return res
```

### Appendix H — Source map (what happens to each part of the current code)

**All old Kotlin sources move to `legacy/` in P0-2 (excluded from the build; reference only) and `legacy/` is deleted in P8-1.** The table says what each part becomes.

| Path (under the module's `src/main`) | Disposition |
|---|---|
| `app/.../Main.kt` | Rewrite as shell + wiring (P4-2); update check → P7-5 |
| `app/msix/AppxManifest.xml` | **Keep identity**; inject version, set `MinVersion` (P8-2) |
| `app/resources/store/*.png` | Move to `branding/` (P8-1) |
| `core/model/*` | Rewrite (P1-1) |
| `core/export/*` (`TreeExporter`, `RuleAnalyzer`) | Rewrite (P1-2, P1-3, P1-4) |
| `core/codegen/*` | Rewrite (P1-5) |
| `core/db/*`, `core/repository/*` | Rewrite with migrations (P1-6, P1-7) |
| `engine/android/*` | Harden / rewrite into `adb/` (Phase 2) |
| `engine/pc/WindowsUiAutomation.kt`, `MacOsAccessibility.kt`, `LinuxAtSpi.kt`, `PcInspector.kt` | **Delete** (P0-2) |
| `engine/ios/*` | **Delete** (P0-2) |
| `engine/hotkey/*` | **Delete** (P0-2); new hotkey in the helper (P3-6) |
| `ui/inspector/*` | Split and rewrite (P4-3…P4-6); reuse ideas, not code |
| `ui/launcher/*` | **Delete** (P4-2) |
| `ui/onboarding/*` | Rewrite (P4-7) |
| `ui/settings/*` | Rewrite (P4-8) |
| `ui/history/*` | Replace with Snapshots (P5-1) |
| `ui/diff/*` | **Delete now**, rebuild in Phase 6 |
| `ui/watch/*` | **Delete** (P0-2) |
| `ui/theme/*` | Consolidate (P4-1) |
| Gradle files, `libs.versions.toml` | Update (P0-1, §3.9, P8-1) |
| `README.md`, `CHANGELOG.md`, `DISTRIBUTION.md` | Rewrite (P0-3, P8-7) |

### Appendix I — Store listing draft (truthful for v1)

*Update this if any scope decision changes. Do not add claims that are not in the shipped build.*

- **Name:** UIScope: Desktop & Android UI Inspector *(reserved)*
- **Publisher:** TBTechs · **Developer:** TITANICBHAI · **Website / support:** https://github.com/TITANICBHAI/UIScope-jvm (Issues page)
- **Short description (≤ 100 characters):** Inspect any Windows app or Android screen and get a reliable selector plus ready-to-use test code.
- **Category:** Developer tools · **Price:** Free · **Age rating:** answer the IARC questionnaire honestly (no user-generated content, no purchases, no online interaction)
- **Description:**

> UIScope shows you the UI element tree of any Windows app or Android screen — and tells you which selector you can trust.
>
> Point at an element and UIScope shows its properties, where it sits in the hierarchy and how to find it again. It checks whether each possible selector matches exactly one element, rates how stable it is, and writes ready-to-paste code for your test framework.
>
> **Windows apps** — Press Ctrl+Alt+P (or the Pick button), hover any window and click an element. See Automation ID, Name, Control Type, supported patterns, bounds and the full UI Automation tree. Code for pywinauto, FlaUI (C#), AutoHotkey v2 and PowerShell.
>
> **Android devices** — Connect over USB or Wi-Fi debugging; nothing to install on the phone. Capture the screen and its full UI hierarchy, or follow along in Live mode. Code for uiautomator2, UiAutomator, Appium (Java and Python), Maestro and XPath.
>
> **Made for real work** — Selector verdicts (unique? stable? localized? dynamic?), search, click-to-select on the screenshot, test any selector against the capture, saved snapshots you can reopen offline and share as .uiscope files, export to JSON, XML, text or PNG.
>
> **Private by design** — No account, no telemetry. Everything stays on your PC. UIScope contacts the internet only if you choose to check for updates or to download Android's platform-tools from Google.
>
> **Requirements** — Windows 10 (version 1809) or later. Android inspection needs adb (Android platform-tools); UIScope can download it for you when you choose. Inspecting windows that run as administrator requires running UIScope as administrator.
>
> UIScope does not modify the apps you inspect. Android is a trademark of Google LLC. Windows is a trademark of Microsoft Corporation.

- **Keywords:** UI inspector, UI Automation, Android, selector, XPath, test automation, accessibility
- **What's new (1.0.0):** First public release — Windows and Android inspection, selector verdicts, code generation, snapshots and export.
- **Screenshots (≥ 4 at 1920×1080, real captures):** (1) Windows pick with hover highlight · (2) Inspect view with the Selector tab and verdict · (3) Android capture with tree, canvas and code · (4) Snapshots list · (5) light theme.
- **Certification notes:** "UIScope reads other applications' UI through the Windows UI Automation API, which requires the `runFullTrust` capability. An optional global shortcut is registered with `RegisterHotKey`; a low-level *mouse* hook is active only while the user is picking an element and is removed afterwards; there is no keyboard hook. For Android inspection the user may choose to download Google's platform-tools from dl.google.com; no other software is downloaded and no user data is transmitted. No account or test credentials are needed."

### Appendix J — Windows helper protocol (v1)

Transport: UTF-8, **one JSON object per line** on stdin (requests) and stdout (responses and events). The helper writes **only** protocol lines to stdout; diagnostics go to stderr. The helper exits when stdin closes. Unknown fields are ignored; the client refuses a different **major** protocol version.

```text
request   { "id": 7, "type": "captureWindow", "hwnd": "0x00120A3C", "maxDepth": 40, "maxNodes": 10000 }
response  { "id": 7, "ok": true,  ... }
error     { "id": 7, "ok": false, "error": { "code": "WINDOW_GONE", "message": "..." } }
event     { "event": "pickHover", ... }
```

| Request | Response / events |
|---|---|
| `hello` | `{ helperVersion, protocol: 1, osBuild, dpiAwareness: "PerMonitorV2", isElevated }` |
| `listWindows` | `{ windows: [ { hwnd, title, processName, pid, isElevated, rect, iconPath? } ] }` |
| `captureWindow { hwnd, maxDepth, maxNodes }` | `{ window: {…}, screenshot: { path, width, height, originX, originY }, roots: [ nodes per Appendix D / B ], truncated }` — screenshot is a **temp-file path**, not base64 |
| `startPick { hideFromCapture: true }` | events `pickHover { point, element: { label, controlType, name, rect } }`, then `pickResult { hwnd, point, element: { runtimeId, automationId, name, controlType, rect } }` or `pickCancelled` |
| `stopPick` | `{}` (removes overlay and mouse hook) |
| `registerHotkey { modifiers, key }` | `{}` or error `HOTKEY_IN_USE`; later event `hotkey` |
| `unregisterHotkey` | `{}` |
| `ping` / `shutdown` | `{}` |

The Kotlin side relocates the picked element inside the captured tree by `runtimeId`, falling back to identifier + rect.

### Appendix K — Owner's manual checks (about 30–40 minutes per round)

*Why this exists:* an AI agent can write and unit-test the code, but it cannot see your screen or touch your phone. These short checklists are the only parts that need a human. Run the relevant round when the agent says it is ready (the timing is in each heading), note pass/fail for each step, and paste **Settings → Advanced → Copy diagnostics** with any failure. The agent copies and refines these into `docs/MANUAL_QA.md`.

**Round 0 — Fixture capture (at the very start, about 10 minutes).** Needs: your Android phone, a USB data cable and `adb`. Do step 1 of Round 1 (USB debugging), then run `scripts/capture-fixtures.sh` (macOS/Linux) or `scripts/capture-fixtures.ps1` (Windows) and follow its prompts to save five different screens (Settings, a Jetpack Compose app if you have one, a web page in a WebView, a long list, a dialog over a screen). Hand the output folder to the agent — these raw files are the test data for the Android parser and selector tests.

**Round 1 — Android (after Phase 4, the first time the real UI exists).** Needs: your Android phone and a USB data cable. *Optional early check 1a, after Phase 2:* press *Capture now* in the temporary shell — **pass if** it reports a non-zero element count for your phone's current screen and saves a screenshot.

1. On the phone: Settings → About phone → tap *Build number* 7 times → Developer options → turn on *USB debugging*. Plug in and tap *Allow* on the phone. 
2. Start UIScope. **Pass if** your phone's model appears within about 5 seconds and one capture happens by itself.
3. Open three different apps (Settings, a game, one of your own) and press Capture each time. **Pass if** the screenshot and tree match the phone and nothing spins for more than about 20 seconds.
4. Click an element on the screenshot. **Pass if** the tree selects the same element and the Selector tab shows a verdict with a match count.
5. Copy code for each target and paste it into a text editor. **Pass if** text containing an apostrophe or quote (for example "Don't allow") still looks like valid code.
6. Turn Live on for one minute and scroll on the phone. **Pass if** UIScope stays responsive, the tree keeps its expansion, and the snapshot count does not grow unless you press Save.
7. Unplug the phone during Live, then plug it back in. **Pass if** you see "Disconnected — showing last capture" and it reconnects afterwards.
8. Open a banking or other secure app screen. **Pass if** you get the "may block capture" hint instead of a mystery blank.

**Round 2 — Windows pick (after Phase 4).** Needs: your Windows PC. Do it once at your normal display scaling and once at 125 % or 150 % (and with a second monitor, if you have one). *Optional early check 2a, right after P3-4:* run `UIScope.Helper.exe --demo-pick` — **pass if** hovering draws a rectangle that hugs the element, a click is *not* delivered to the app underneath, and the picked element prints as JSON.

1. Press Pick (or `Ctrl+Alt+P`) and hover Notepad, Calculator, File Explorer and Edge. **Pass if** the highlight rectangle hugs the element you point at and the label shows its type and name.
2. In pick mode, click a *Close*, *Save* or *Delete* button of another app. **Pass if** UIScope captures it and the other app's button is **not** triggered.
3. Start pick mode and press `Esc`; start it again and right-click. **Pass if** pick mode ends and nothing else happens.
4. After picking, compare UIScope's screenshot overlay with the real window. **Pass if** the highlight lines up with no shift at both scalings.
5. Start Task Manager as administrator and try to pick it. **Pass if** you get the "runs as administrator" message.
6. In another app press `R` and `Ctrl+F`. **Pass if** UIScope does nothing.
7. Pick a Chrome or Edge page and a VS Code window. **Pass if** you see a real tree (or the "limited accessibility" hint), not an empty one.

**Round 3 — Everyday use and install (after Phase 5, and again before release).**

1. Save a snapshot, close UIScope, reopen it, and open the snapshot from the Snapshots screen. **Pass if** the tree and screenshot are there with no device attached.
2. Export JSON and paste it into any JSON validator. **Pass if** it is valid.
3. In Settings switch between Light, Dark and System. **Pass if** it applies immediately and *Reset* works.
4. Launch UIScope twice. **Pass if** the second launch just brings the first window forward.
5. Shrink the window to its smallest size. **Pass if** nothing is cut off and the toolbar has an overflow menu.
6. Install the MSIX (sideload) on a clean user account or VM, run the core loop, then uninstall. **Pass if** there are no errors and nothing is left behind.
7. In Windows *Installed apps*, check the entry. **Pass if** the name is "UIScope" and the publisher is "TBTechs".
