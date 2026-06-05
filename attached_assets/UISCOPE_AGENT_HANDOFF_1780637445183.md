# UIScope — Agent Handoff Document

> Everything a new agent needs to start building UIScope from this repo.
> Read this entire file before touching any code.

---

## 1. What This Repo Is

This repo is **NodeSpy** (https://github.com/TITANICBHAI/NodeSpy) — a pure Android app (Kotlin 1.9.24, Android Gradle, Jetpack Compose) by TITANICBHAI that uses the Android Accessibility API to live-inspect, pin, and export the UI node tree of any app running on a device.

**UIScope is the next product.** It takes NodeSpy's concept off the phone and onto the desktop — a Kotlin Compose Multiplatform Desktop app that inspects UI element trees for:
1. **Any app running on the same PC** (Windows, macOS, Linux) via native accessibility APIs
2. **Any app on a connected Android device** via ADB — no app installed on phone, no on-device agent

The full product spec is in: `attached_assets/UIScope-Product-Plan_1780631330007.md`
**Read it fully before building anything.** It is 480 lines and covers every screen, behaviour, error state, tech stack decision, and phase plan.

---

## 2. Architecture Decision: Fresh Start, Port the Data Layer

**Do NOT try to adapt the existing Android Gradle project.** Converting it to KMP Desktop means fighting Android config the entire time. Start a fresh Compose Multiplatform Desktop project.

The existing repo is 100% Android-specific at the build, service, and UI layers. The only salvageable code is the pure Kotlin data/export layer.

---

## 3. ⚠️ Critical Cautions Before You Start

Read all of these before writing a single line of code.

### 3.1 — This is a Native Desktop App, Not a Web App
CMP Desktop produces a native `.exe` / `.dmg` / `.AppImage`. **It cannot be previewed in Replit's browser preview pane.** You can compile and run `./gradlew run` to launch the desktop window on the host machine during development, but there is no web preview. Build and verify compilation in Replit; visual testing requires a real machine or CI artifact download.

### 3.2 — ExportBuilder Has a Hidden Android Dependency
**`ExportBuilder.kt` line 53 calls `CaptureStore.recentForPackage(capture.pkg)` directly.** `CaptureStore` is an Android singleton that won't exist in the desktop project. When porting `ExportBuilder`, break this dependency by changing the signature:
```kotlin
// BEFORE (Android — do not port as-is)
fun build(capture: NodeCapture, pinnedIds: Set<String>): String

// AFTER (desktop — pass recentCaptures explicitly)
fun build(capture: NodeCapture, pinnedIds: Set<String>, recentCaptures: List<NodeCapture> = emptyList()): String
```
Then pass `recentCaptures` through to `RuleAnalyzer.analyze()`.

### 3.3 — Gson Cannot Be Used in the Ported ExportBuilder
`ExportBuilder` uses `com.google.gson.GsonBuilder`. Gson is JVM-only and does not work in KMP. Replace with `kotlinx.serialization`. This is not a simple swap — there are three things to do:
1. Add Gradle plugin: `kotlin("plugin.serialization")` + dependency `org.jetbrains.kotlinx:kotlinx-serialization-json`
2. `ExportBuilder` builds `Map<String, Any?>` which `kotlinx.serialization` cannot encode natively (it doesn't support `Any?`). Either convert to typed data classes with `@Serializable`, or use `buildJsonObject { }` from `kotlinx.serialization.json` to build the JSON tree directly.
3. Replace `gson.toJson(payload)` with `Json { prettyPrint = true }.encodeToJsonElement(...)` or `buildJsonObject { ... }.toString()`.

### 3.4 — Android-Only Compose APIs in Reference Files
The following APIs appear in the reference screen files and **do not exist in CMP Desktop** — remove them from any ported UI code:
- `LocalContext.current` — no Context in CMP Desktop
- `navigationBarsPadding()` — Android insets modifier, doesn't exist on desktop
- `ClipboardManager` (Android) — use `java.awt.Toolkit.getDefaultToolkit().systemClipboard` on desktop
- `Intent` / `Intent.ACTION_SEND` — no Android intents; use `Desktop.getDesktop().open(url)` or `ProcessBuilder`
- `Toast.makeText()` — use a `Snackbar`-equivalent or a custom overlay in CMP
- `BitmapFactory.decodeFile()` — use `javax.imageio.ImageIO.read(File(...))` on desktop, then convert to `ImageBitmap`
- `produceState` with `Dispatchers.IO` — this IS available in CMP Desktop (coroutines are multiplatform), keep this pattern

### 3.5 — kotlinx.coroutines.flow IS Multiplatform
`CaptureStore` uses `MutableStateFlow` / `MutableSharedFlow` from `kotlinx.coroutines.flow`. These are fully KMP-compatible and work identically in CMP Desktop. The in-memory store pattern is safe to replicate — just replace the `PrefsStore` (Android SharedPreferences) persistence with SQLDelight calls.

### 3.6 — Global Hotkeys Are Non-Trivial on Desktop
CMP Desktop's `onKeyEvent` only fires when the app has focus. The plan's global hotkeys (`Alt+Shift+P` to activate pick mode from any app) require a system-level keyboard hook. Use the `jnativehook` library (MIT, pure Java, works on Windows/macOS/Linux via JNI). This is a Phase 4 concern — do not block Phase 1 on it.

### 3.7 — JNA Platform-Specific Binaries
The PC engine (Windows UIAutomation, macOS AX API, Linux AT-SPI2) uses JNA to call native OS APIs. JNA requires platform-specific native binaries bundled in the JAR. Use `net.java.dev.jna:jna` and `net.java.dev.jna:jna-platform`. Key caution: on Windows, `UIAutomation.dll` ships with every install, but the JNA interface definitions need to match the COM interface exactly. On macOS, the AX API lives in `ApplicationServices.framework` — accessible via JNA's `NativeLibrary.getInstance("ApplicationServices")`.

### 3.8 — UIAutomation Elevation Gap (Windows)
Windows UI Automation works without UAC elevation for most apps. **Exception: if the target app is running as Administrator, UIScope (running as a normal user) cannot read its UI tree.** The Windows security model blocks cross-privilege-level UI Automation by default. Show a clear error: *"This app is running as Administrator. Launch UIScope as Administrator to inspect it."* Do not silently return an empty tree.

### 3.9 — macOS Accessibility Permission Is Mandatory
On macOS, the AX API returns nothing without explicit Accessibility permission granted in `System Settings → Privacy & Security → Accessibility`. UIScope must:
1. Detect if permission is missing at startup (call `AXIsProcessTrusted()` via JNA)
2. Show the exact settings path and a button that opens `System Preferences` to that exact pane
3. Never crash silently — if the permission is missing, the entire PC mode is inoperable on macOS

### 3.10 — Linux AT-SPI2 Must Be Present
PC mode on Linux requires `at-spi2-core`. Check on first launch in PC mode. If missing, show the install command for the detected distro:
- Debian/Ubuntu: `sudo apt install at-spi2-core`
- Fedora: `sudo dnf install at-spi2-core`
- Arch: `sudo pacman -S at-spi2-core`
Most GNOME/KDE desktops have it pre-installed, but always check.

### 3.11 — ADB Path Detection Order
Check for ADB in this exact order:
1. `$ANDROID_HOME/platform-tools/adb` (env var)
2. `$ANDROID_SDK_ROOT/platform-tools/adb` (env var)
3. `adb` on `$PATH`
4. Bundled minimal platform-tools in the app's data directory (offered as one-click download if all above fail)

Never assume ADB is available. Always handle the not-found case with the error UI from plan §6.

### 3.12 — UIAutomator Dump Stdout vs File
Two ways to run UIAutomator dump. Prefer option B:
```bash
# Option A (slower — writes to device, requires pull)
adb shell uiautomator dump /sdcard/window_dump.xml
adb pull /sdcard/window_dump.xml

# Option B (faster — pipes directly to stdout, no temp file on device)
adb exec-out uiautomator dump /dev/tty
```
Option B pipes the XML directly through the ADB connection. Parse from the process stdout stream directly in `ProcessBuilder`.

### 3.13 — screencap Command
```bash
# Correct — pipes PNG bytes directly to stdout
adb exec-out screencap -p
```
Read stdout bytes and decode as PNG (`ImageIO.read(inputStream)`). Do NOT write to device storage and pull — it's slower and leaves files on the phone.

### 3.14 — UIAutomator Failure Modes
`uiautomator dump` silently produces empty or minimal XML when:
- Screen is off or locked → show: *"Could not read device UI — is the screen on and unlocked?"*
- App uses `FLAG_SECURE` (some banking/system apps) → show: *"This app may be blocking accessibility — some system apps do this"*
- UIAutomator itself crashes on some Android versions → retry once automatically, then show the error

### 3.15 — Code Signing (Plan for It Early)
From plan §13 — without code signing, install drop-off is severe:
- **Windows:** Unsigned `.exe` triggers SmartScreen ("Windows protected your PC"). OV certificate: ~$70–200/year (Sectigo or Certum)
- **macOS:** Unsigned `.app` is **completely blocked** by Gatekeeper by default. Apple Developer Program + notarization: $99/year
- **Linux:** No OS-level enforcement. GPG-sign the release assets on GitHub (free)

This is a distribution concern, not a code concern — but the agent setting up CI/GitHub Actions packaging should budget for it.

### 3.16 — Gradle Version Requirements
The existing repo uses Kotlin 1.9.24 and Android-only Compose BOM 2024.09.00. **These are not the versions needed for CMP Desktop.** The new project must use:
- Kotlin 2.0+ (required for CMP 1.7+)
- Compose Multiplatform 1.7+ (`org.jetbrains.compose` Gradle plugin)
- Gradle 8.5+
- JVM target: 17 (matches existing project, keep it)

Do not reuse or inherit any version declarations from the existing `build.gradle` files.

### 3.17 — JVM Bundling Means Large Installers
CMP Desktop's Compose JB packaging bundles the entire JVM runtime inside the installer. The resulting installer is **~80–100 MB**. This is expected and intentional — users never need to install Java. Do not try to reduce this by unbundling the JVM; it would break the "works out of the box" guarantee.

### 3.18 — Tree Virtualisation Is Required from Day One
The plan requires lazy-loading trees beyond 1,000 nodes (collapsed to depth 3 by default). Use `LazyColumn` (works identically in CMP Desktop) for the tree panel from the very first implementation. Do not use a regular `Column` with a loop — some app trees have 2,000–5,000+ nodes and will freeze the UI.

---

## 4. Files to Port Directly

Pure Kotlin, zero Android dependencies. Copy into the new project under `com.tbtechs.uiscope`.

### `data/NodeCapture.kt` → `core/model/NodeCapture.kt`
The core data model. No changes except package rename.
- `NodeCapture` — one full snapshot: pkg, timestamp, screen dimensions, flat node list, screenshot path, starred flag, auto-pinned IDs
- `NodeEntry` — one element: id, parentId, className, resourceId, text, contentDescription, hint, bounds (L/T/R/B ints), flags, depth, childIds
- `NodeFlags` — all boolean element state: enabled, clickable, longClickable, scrollable, checkable, checked, focused, selected, visible, password, editable

For UIScope's PC mode, `pkg` maps to the window/process name, `activityClass` maps to the control type or window class.

---

### `data/AutoPinRule.kt` → `core/model/AutoPinRule.kt`
The bookmark/pin rule engine. No changes except package rename.
- `AutoPinRule` — id, glob pattern, matchField (RES_ID / TEXT / CLASS / DESC / HINT), enabled
- `globMatch(pattern, value)` — `*` wildcard glob, case-insensitive
- `AutoPinRule.matches(node: NodeEntry)` — extension function

---

### `data/ExportRecord.kt` → `core/model/ExportRecord.kt`
Export event record for session history. No changes except package rename.
- Fields: `timestamp`, `captureId`, `pkg`, `nodeCount`

---

### `export/ExportBuilder.kt` → `core/export/ExportBuilder.kt`
Full structured JSON export builder. **Two changes required** (see §3.2 and §3.3 above):
1. Break the `CaptureStore` dependency — add `recentCaptures: List<NodeCapture> = emptyList()` parameter
2. Replace Gson with `kotlinx.serialization`

Output format (`NodeSpyCaptureV1`) stays identical — it is already automation-tool-ready and used by FocusFlow consumers.

---

### `export/RuleAnalyzer.kt` → `core/export/RuleAnalyzer.kt`
Selector quality scoring engine. No changes except package rename. Implements plan §15 exactly.
- `RuleRecommendation` — per-node: selectorType, selector map, confidence 0–100, tier (strong/medium/weak), stability float, reasons list, warnings list
- `RuleQualitySummary` — aggregate: strong/medium/weak counts, exportable count, average confidence
- Scoring: resId uniqueness (+48 unique / +34 reused), text label (+24 unique / +14 reused), cross-capture stability (+14 if ≥67%), actionable (+8). Tiers: strong ≥80, medium ≥55, weak <55
- "Fragile selector" warnings auto-generated when no resId or text label — connects directly to the yellow badge in the Code Generator UI (plan §15)

---

### `ui/theme/Color.kt` → `ui/theme/Color.kt`
Complete dark colour palette. `androidx.compose.ui.graphics.Color` is identical in CMP Desktop — **copy as-is, zero changes needed.**

| Token | Hex | Use |
|---|---|---|
| `Background` | `#0D1117` | App background |
| `Surface` | `#161B22` | Cards, panels |
| `SurfaceVar` | `#21262D` | Canvas background |
| `Outline` | `#30363D` | Borders |
| `OnBackground` | `#E6EDF3` | Primary text |
| `Muted` | `#8B949E` | Secondary text |
| `AccentBlue` | `#58A6FF` | Primary accent, PC highlight colour |
| `AccentGreen` | `#3FB950` | Success, pinned/marked state |
| `AccentOrange` | `#F0883E` | Warnings, weak tier |
| `AccentPurple` | `#D2A8FF` | Image-type nodes |
| `AccentRed` | `#F85149` | Errors, Android highlight colour |
| `AccentYellow` | `#E3B341` | Medium tier, input-type nodes |
| `NodeLayout` / `NodeLayoutBorder` | `#58A6FF` (33% / 100% alpha) | Layout container nodes |
| `NodeText` / `NodeTextBorder` | `#3FB950` (33% / 100% alpha) | Text nodes |
| `NodeButton` / `NodeButtonBorder` | `#F0883E` (33% / 100% alpha) | Button/clickable nodes |
| `NodeImage` / `NodeImageBorder` | `#D2A8FF` (33% / 100% alpha) | Image nodes |
| `NodeInput` / `NodeInputBorder` | `#E3B341` (33% / 100% alpha) | Input/editable nodes |
| `NodeOther` / `NodeOtherBorder` | `#8B949E` (13% / 100% alpha) | Everything else |
| `NodeSelectedBorder` | `#FFFFFF` | Currently selected node overlay |

---

### `ui/theme/Theme.kt` → `ui/theme/Theme.kt`
MaterialTheme dark scheme. **Only change:** rename `NodeSpyTheme` → `UIScopeTheme`.

---

## 5. Files to Use as Reference (Logic Valuable, Code Android-Coupled)

Read for patterns — do not copy directly. All import `android.*`.

### `InspectorScreen.kt` — extract these exactly:

**`nodeColors(node: NodeEntry): Pair<Color, Color>`** — maps node type to (fill, border) colour pair:
```kotlin
fun nodeColors(node: NodeEntry): Pair<Color, Color> = when {
    node.cls.contains("Layout") || node.cls.contains("Frame") ||
        node.cls.contains("Constraint") || node.cls.contains("Coordinator") ->
        NodeLayout to NodeLayoutBorder
    node.cls.contains("Text") && !node.flags.editable -> NodeText to NodeTextBorder
    node.cls.contains("Button") || (node.flags.clickable && !node.flags.scrollable) ->
        NodeButton to NodeButtonBorder
    node.cls.contains("Image") || node.cls.contains("Icon") -> NodeImage to NodeImageBorder
    node.flags.editable || node.cls.contains("EditText") -> NodeInput to NodeInputBorder
    else -> NodeOther to NodeOtherBorder
}
```

**`friendlyNodeKind(node: NodeEntry): String`** — maps className to human label (Button, Text Input, Container, Tab, Bar, Image, etc.). Full implementation is in lines 472–484 of `InspectorScreen.kt`.

**`ConfidenceBadge(recommendation: RuleRecommendation)`** — the coloured confidence pill shown on tree rows and properties panel:
```kotlin
// Color: AccentGreen (strong), AccentYellow (medium), AccentOrange (weak/else)
// Text: "${recommendation.confidence} ${recommendation.tier.uppercase()}"
// Style: small monospace text in rounded rect with 12% alpha background of same colour
```

**Canvas hit-testing pattern** — used in `VisualCanvas.kt`:
```kotlin
// Tap-to-select: find deepest visible node containing the tap point
val hit = capture.nodes
    .filter { it.flags.visible }
    .lastOrNull { n ->
        offset.x in (n.boundsL * scaleX)..(n.boundsR * scaleX) &&
        offset.y in (n.boundsT * scaleY)..(n.boundsB * scaleY)
    }

// Region drag-select: find all visible nodes intersecting the drag rectangle
val intersecting = capture.nodes.filter { n ->
    n.flags.visible &&
    n.boundsR > selL && n.boundsL < selR &&
    n.boundsB > selT && n.boundsT < selB
}
```

**`DetailRow(key, value)`** — the key: value monospace row used throughout the properties panel:
```kotlin
Row(Modifier.padding(vertical = 1.dp)) {
    Text("$key: ", color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    Text(value, color = OnBackground, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}
```

**`QualityPill(label, count, color)`** — the strong/medium/weak count badge in the inspector header.

**`RecommendationCard`** — shows per-node confidence, selector string, stability ratio, reasons, warnings. Pattern for the Properties panel's bottom section.

---

### `SimpleInspectorScreen.kt` — extract these patterns:

**Screenshot + overlay drawing** — for `VisualCanvas.kt` in Android mode:
```kotlin
// 1. drawImage fills the canvas with the screenshot
drawImage(image = snap, dstOffset = ..., dstSize = ...)
// 2. drawRect overlays each visible node's bounds on top
// Marked nodes: AccentGreen fill (25% alpha) + AccentGreen solid border (3f stroke)
// Selected nodes: white fill (12% alpha) + white dashed border (2.5f, dashEffect 8f/4f)
// Default: nothing drawn when screenshot exists (nodes only shown when no screenshot)
```

**No-screenshot fallback** — when no screenshot: draw all nodes as coloured rectangles using `nodeColors()`. This is the PC mode default view before a screencap is taken.

**Screenshot loading state** — `produceState<ImageBitmap?>(null, screenshotPath)` with `withContext(Dispatchers.IO)` and `ImageIO.read()` (desktop equivalent of `BitmapFactory.decodeFile()`). Show `CircularProgressIndicator` while decoding.

---

### `CaptureStore.kt` — extract these patterns:

**Dedup logic** — skip a new capture if same pkg + activityClass + node count arrived within 800 ms of the previous one. Prevents event floods.

**Auto-pin on capture** — when adding a capture, run all enabled `AutoPinRule`s against its nodes; store matching IDs as `autoPinnedIds` on the `NodeCapture`.

**`recentForPackage(pkg, limit = 5)`** — returns the N most recent captures for a given package. Feed into `RuleAnalyzer.analyze()` to measure cross-capture selector stability.

**`StateFlow` per concern** — each piece of state (captures list, selected capture ID, pinned IDs, logging enabled, device list) gets its own `MutableStateFlow`. Single source of truth. The shape of this store is the right pattern for UIScope's session store; replace `PrefsStore` (SharedPreferences) with SQLDelight.

---

### `AppSuggestions.kt` — extract this function only:

```kotlin
fun matchesSearch(query: String, node: NodeEntry): Boolean {
    val searchText = listOfNotNull(node.resId, node.text, node.desc, node.hint, node.cls)
        .joinToString(" ").lowercase()
    return searchText.contains(query.lowercase())
}
```
This is the pattern for UIScope's **Search** feature (plan §4.5) — searching across all node fields simultaneously with a single query string.

---

## 6. Files to Discard Entirely

| File | Reason |
|---|---|
| `PrefsStore.kt` | 100% Android SharedPreferences + Context. Replace with SQLDelight settings table. |
| `AppMode.kt` | `SIMPLE/DEVELOPER` modes are NodeSpy-specific. UIScope uses `PC/ANDROID` enum. |
| `NodeSpyAccessibilityService.kt` | Android Accessibility Service. UIScope's Android engine is ADB-over-desktop, not on-device. |
| `FloatingBubbleService.kt` | Android WindowManager floating overlay. Irrelevant on desktop. |
| `NotificationHelper.kt` | Android notification channels. Irrelevant on desktop. |
| `MainActivity.kt` | Android Activity. Replaced by CMP `singleWindowApplication {}`. |
| `NodeSpyApplication.kt` | Android Application class. Not needed on desktop. |
| `NodeSpyApp.kt` | Android Navigation component. CMP Desktop uses a different nav pattern (state-based). |
| All other `ui/screens/*.kt` | Deeply Android-coupled. Visual + logic reference only, do not copy. |
| `AndroidManifest.xml` + all `res/` | Android resources. 100% irrelevant. |
| `app/build.gradle`, root `build.gradle` | Android Gradle. Full replacement with KMP Desktop Gradle required. |
| `app/src/main/res/xml/accessibility_service_config.xml` | Android accessibility service config. Irrelevant. |

---

## 7. Target Tech Stack

| Layer | Technology | Notes |
|---|---|---|
| UI framework | Kotlin Compose Multiplatform Desktop | `org.jetbrains.compose` plugin |
| PC tree — Windows | Windows UI Automation via JNA | `UIAutomation.dll` — ships with every Windows |
| PC tree — macOS | macOS AX API via JNA | `ApplicationServices.framework` — see caution §3.9 |
| PC tree — Linux | AT-SPI2 via JNA | Check for `at-spi2-core` on first launch — see caution §3.10 |
| Android tree | ADB via `ProcessBuilder` | `uiautomator dump` + `screencap` — see cautions §3.11–3.14 |
| Serialisation | `kotlinx.serialization` | Replaces Gson — see caution §3.3 |
| Local storage | SQLDelight (SQLite) | Session history, bookmarks, settings — replaces PrefsStore |
| Hotkeys (global) | `jnativehook` library | Phase 4 — see caution §3.6 |
| Build & packaging | Gradle + Compose JB packaging | `.exe` / `.dmg` / `.deb` / `.AppImage` |
| Kotlin version | 2.0+ | Required for CMP 1.7+ — see caution §3.16 |
| JVM target | 17 | Matches existing repo |

---

## 8. Target Project Structure

```
uiscope/
├── core/
│   ├── model/          ← PORT: NodeCapture.kt, AutoPinRule.kt, ExportRecord.kt
│   ├── export/         ← PORT: ExportBuilder.kt (fix deps), RuleAnalyzer.kt
│   └── codegen/        ← BUILD: AHK v2, Python pywinauto, C# FlaUI, PowerShell (PC)
│                                uiautomator2, Kotlin UIAutomator2, Appium, Maestro (Android)
├── engine/
│   ├── pc/             ← BUILD: Windows UIAutomation JNA, macOS AX API JNA, Linux AT-SPI2 JNA
│   └── android/        ← BUILD: AdbManager (ProcessBuilder), UIAutomatorXmlParser, ScreencapManager
├── ui/
│   ├── theme/          ← PORT: Color.kt (as-is), Theme.kt (rename UIScopeTheme)
│   ├── launcher/       ← BUILD: ModePicker screen — 2 equal cards, last-mode memory
│   ├── inspector/      ← BUILD: shared 3-panel shell used by both modes
│   │   ├── TreePanel.kt         ← LazyColumn virtualised tree, breadcrumb bar (plan §17)
│   │   ├── VisualCanvas.kt      ← REFERENCE: InspectorScreen + SimpleInspectorScreen
│   │   └── PropertiesPanel.kt   ← REFERENCE: NodeDetailStrip, DetailRow, ConfidenceBadge
│   ├── codegen/        ← BUILD: code output panel + copy bar (plan §4.3, §4.4)
│   ├── history/        ← BUILD: session history browser (plan §4.5)
│   ├── onboarding/     ← BUILD: first-run check screen (plan §5)
│   └── settings/       ← BUILD: hotkeys, theme, ADB path, polling interval (plan §18)
└── Main.kt             ← singleWindowApplication { UIScopeTheme { ... } }
```

---

## 9. Development Phases

| Phase | Scope | Duration |
|---|---|---|
| **1 — Skeleton** | Mode picker, shared 3-panel layout (static), session model + SQLite, ADB device detection | Weeks 1–2 |
| **2 — Android Mode live** | UIAutomator dump parse, screencap display, bidirectional node↔screencap selection, basic code gen, JSON export | Weeks 3–4 |
| **3 — PC Mode live** | Windows UIAutomation JNA engine, screen overlay + hover detection, element lock + tree, PC code gen, session history | Weeks 5–6 |
| **4 — Polish & Ship** | Diff mode, Watch mode, search, bookmarks, global hotkeys, system tray, CI packaging | Weeks 7–8 |

---

## 10. Key Product Behaviours to Know Before Building

- **Mode picker is the launch screen.** Two equal cards: "Inspect This PC" + "Inspect Android". Last-used mode remembered. Mode switch button always visible in top bar. (Plan §4.2)
- **3-panel layout** — Tree (left) | Visual Canvas (centre) | Properties (right). Same shell for both modes. (Plan §4.3, §4.4)
- **Bidirectional selection is first-class.** Click tree node → highlight on canvas. Click canvas → select in tree. This is not optional. (Plan §4.3, §4.4)
- **Code generator bar** at the bottom. One-click copy per target. Multiple targets per mode. Fragile selector yellow badge when no stable ID. (Plan §4.3, §4.4, §15)
- **Session history** — every inspection saved locally (timestamp, screenshot, tree JSON). Reopen without device. (Plan §4.5)
- **Fully offline.** No accounts, no telemetry, no cloud. State this in onboarding. (Plan §4.1, §9)
- **ADB: detect or download.** If not on PATH, offer one-click minimal platform-tools download (~5 MB). (Plan §5, §6)
- **First-run onboarding once.** Shows ADB check + OS accessibility permission check. Green ticks / amber warnings. Nothing blocks proceeding. (Plan §5)
- **Node breadcrumb bar** between tree and properties panels: `Window › ContentPane › Button[2]`. Each crumb clickable. "Copy as XPath" button. (Plan §17)
- **Tree virtualisation required.** Trees >1,000 nodes: lazy-load, collapse to depth 3 by default. (Plan §4.5, caution §3.18)
- **System tray** (Windows/Linux) / menu bar (macOS): Quick Pick, Open UIScope, Switch Mode, Quit. (Plan §19)
- **Auto-update check** at launch: silent GitHub Releases API query. If newer version found, show subtle banner. No auto-installer. (Plan §21)
- **Multi-window** (Phase 4): two simultaneous windows, one PC + one Android. `File → New Window`. (Plan §20)

---

## 11. Product Plan Section Index

`attached_assets/UIScope-Product-Plan_1780631330007.md` — read before building each phase:

| Section | Content |
|---|---|
| §4.2 | Mode picker exact ASCII layout |
| §4.3 | PC Mode full step-by-step + JNA engine details |
| §4.4 | Android Mode full step-by-step + ADB details |
| §4.5 | Shared features table (search, history, export, bookmarks, diff, watch, hotkeys) |
| §5 | First-run onboarding flow |
| §6 | All error states — exact copy for each failure case |
| §13 | JVM bundling, code signing costs, system requirements |
| §14 | Android connection detail (USB, wireless pre-11, QR pairing Android 11+) |
| §15 | Obfuscated/missing resource ID handling + fragile selector badge |
| §16 | Visual Canvas interaction table (zoom, pan, right-click menus, tooltip) |
| §17 | Node breadcrumb bar + Copy as XPath |
| §18 | Full settings screen — every setting, default value, and option list |
| §19 | System tray / menu bar behaviour |
| §20 | Multi-window support |
| §21 | Auto-update mechanism |
| §23 | Linux AT-SPI2 specifics |

---

*Handoff prepared: June 2026.*
