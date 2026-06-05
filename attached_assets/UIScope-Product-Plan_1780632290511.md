# UIScope — Complete Product Document

> *"See what your UI is made of."*

---

## 1. Origin & Inspiration

UIScope is derived from TITANICBHAI's existing **NodeSpy** project — a pure Kotlin Android tool that uses the Accessibility API to live-inspect, pin, and export the UI node tree of any app running on an Android device. NodeSpy proved the concept: the author understands UI tree inspection deeply, has already written the data models, export logic, and overlay mechanics, and has dogfooded the tool himself when building other apps.

UIScope takes that concept off the phone and onto the desktop, expanding it to cover two surfaces:

1. **Any app running on the same PC** (Windows, macOS, Linux)
2. **Any app running on a connected Android device** via ADB

---

## 2. The Problem

Every developer, tester, or automation builder eventually needs to answer the same question:

> *"What is this UI element actually called, and how do I target it in code?"*

Current options:

| Tool | Problem |
|---|---|
| Microsoft Inspect.exe | Ships with Windows SDK. Designed in 2003. Crashes. No search, no export. |
| Android Studio Layout Inspector | Requires 2 GB install. Only works for apps you're building from source. |
| Appium Inspector | Requires Appium server + Node.js + Java driver. 30-minute setup to see one element. |
| `adb shell uiautomator dump` | Raw XML in a terminal. No visuals, no interactivity, no code generation. |
| macOS Accessibility Inspector | Xcode-only. Not available as a standalone download. |

UIScope replaces all of them with one clean, fast, native Kotlin Compose Multiplatform app that works on Windows, macOS, and Linux.

---

## 3. Who Uses It

| User | Daily Pain | How UIScope Solves It |
|---|---|---|
| Android developer / QA | Needs `resourceId`, `contentDescription`, `className` for test scripts | ADB mode: connect phone, browse live tree |
| Windows automation engineer | Needs `AutomationId`, `Name`, `ControlType` for scripts | PC mode: hover any window element |
| RPA builder (UiPath, Power Automate) | Needs reliable element selectors | PC mode with one-click selector export |
| Appium / Maestro test writer | Needs XPath/selectors without guessing | ADB mode with code generator |
| Accessibility app developer | Needs to understand other apps' accessibility tree | Both modes |
| Desktop app developer | Needs to debug their own app's automation tree | PC mode |

---

## 4. The Product

### 4.1 App Identity

- **Name:** UIScope
- **Tagline:** *See what your UI is made of.*
- **Icon:** A crosshair inside a rounded square — minimal, instantly communicates "targeting an element."
- **Platforms:** Windows, macOS, Linux (all from one codebase)
- **Privacy:** Fully offline. No accounts, no telemetry, nothing leaves the machine. Stated explicitly on the download page — this is a feature, not a footnote.

---

### 4.2 Mode Picker — Launch Screen

Every session begins (and can return to) this screen:

```
╔═══════════════════════════════════════════════════════╗
║                       UIScope                         ║
║             See what your UI is made of.              ║
╠═══════════════════════════╦═══════════════════════════╣
║                           ║                           ║
║    🖥️   Inspect This PC   ║    📱   Inspect Android   ║
║                           ║                           ║
║  Point at any window on   ║  Connect your Android     ║
║  this computer. See its   ║  phone via USB or WiFi.   ║
║  full element tree.       ║  Inspect any app on it.   ║
║                           ║                           ║
║  No setup required.       ║  Requires: Developer Mode ║
║  Works with any app.      ║  enabled + USB or ADB IP  ║
║                           ║                           ║
╚═══════════════════════════╩═══════════════════════════╝
```

- One click enters that mode.
- A **mode switch button** is always visible in the top bar — switch mid-session without restarting.
- The last-used mode is remembered and pre-selected on next launch.

---

### 4.3 PC Mode — Full Behaviour

**Step by step:**

1. Inspector opens with three panels: tree (left), visual canvas (center), properties (right).
2. User presses the **crosshair/pick button** or the global hotkey `Alt+Shift+P`.
3. A transparent full-screen overlay appears. The cursor becomes a targeting reticle.
4. As the mouse moves, UIScope highlights the element under the cursor with a blue border in real time, showing its name in a small tooltip.
5. User **clicks to lock** on that element.
6. Overlay disappears. UIScope focuses on the selected element.
7. **Left — Tree Panel:** Full parent/child hierarchy. Selected element is highlighted. Click any ancestor or sibling to re-focus.
8. **Center — Visual Canvas:** Live screenshot of the target window, with a bounding-box overlay drawn on the selected element's exact position.
9. **Right — Properties Panel:** `Name`, `AutomationId`, `ControlType`, `ClassName`, `Bounds (x, y, w, h)`, `IsEnabled`, `IsKeyboardFocusable`, `IsOffscreen`, and any control patterns supported: `Value`, `Scroll`, `Toggle`, `Selection`, `Invoke`, `RangeValue`.
10. **Bottom — Code Generator:** One-click copy of ready-to-paste code in:
    - AutoHotKey v2
    - Python (pywinauto)
    - C# (FlaUI / UIAutomation)
    - PowerShell (UIAutomation module)

**Engine:** Windows UI Automation API (the COM-based `UIAutomation.dll` that ships with every Windows install, accessed from Kotlin/JVM via JNA — no C++ required). macOS uses the macOS Accessibility API via the same JNA bridge pattern.

**Live updates:** UIScope subscribes to UI Automation structure-change and focus-change events — event-driven, no polling. The tree updates automatically when the target app changes state.

---

### 4.4 Android Mode — Full Behaviour

**Step by step:**

1. **Device Detection Screen:** UIScope scans for connected ADB devices automatically over USB and any active wireless ADB sessions. Shows device name, model, Android version, screen resolution.
2. User **selects a device** from the list, or manually enters a wireless ADB address (`192.168.x.x:5555`) and clicks Connect.
3. **Connected state:** Same three-panel inspector layout opens.
4. **Center — Visual Canvas:** Live screencap of the phone (via `adb exec-out screencap -p`), refreshed on demand or on a configurable polling interval (default: 2 s, or manual-only if preferred).
5. **Left — Tree Panel:** UIAutomator node tree of the current foreground app (via `adb shell uiautomator dump`). Lazy-loaded, virtualised for large trees.
6. **Interaction (bidirectional):**
   - Click a node in the tree → red bounding box drawn on the screencap at that element's bounds.
   - Click directly on the screencap → UIScope finds the node whose bounds contain that point and selects it in the tree.
7. **Right — Properties Panel:** `resourceId`, `text`, `contentDescription`, `className`, `packageName`, `bounds`, `isClickable`, `isEnabled`, `isScrollable`, `isFocused`, `isChecked`, depth in tree, index among siblings.
8. **Bottom — Code Generator:** One-click copy of:
    - Python (uiautomator2)
    - Kotlin (UIAutomator2)
    - Appium (Java / Python)
    - Maestro YAML
    - XPath

**Engine:** Pure ADB process calls via `ProcessBuilder`. ADB is either detected from the system PATH / Android SDK, or a bundled minimal `platform-tools` binary is included in the installer. No app needs to be installed on the phone — only Developer Mode and USB Debugging enabled.

---

### 4.5 Features Shared Across Both Modes

| Feature | Detail |
|---|---|
| **Search** | Filter the element tree by name, ID, class, or text content. Instant, fuzzy-matched. |
| **Session History** | Every inspection is saved locally with timestamp, screenshot, and tree JSON. Reopen any past session with no device needed. |
| **Export** | Save full tree as JSON, XML, or a human-readable indented outline. Export selected node only or full tree. |
| **Bookmarks / Pins** | Pin specific elements (by ID + class fingerprint) across sessions. Useful for recurring test targets — mirrors NodeSpy's AutoPinRule concept. |
| **Diff Mode** *(Pro)* | Capture two states → view which elements were added, removed, or had property changes, side by side. |
| **Watch Mode** *(Pro)* | Define a node condition (e.g. element with text "Update available" appears) — UIScope monitors and screenshots/logs when it triggers. |
| **Auto-Refresh Toggle** | Switch between on-demand refresh and timed polling. Configurable interval. |
| **Tree Virtualisation** | Trees beyond 1,000 nodes are lazy-loaded and collapsed to depth 3 by default. Expands on click. No performance degradation. |
| **Dark / Light Theme** | System-aware by default. Manually overridable in settings. |
| **Global Hotkeys** | All configurable. Defaults: `Alt+Shift+P` pick, `R` refresh, `Ctrl+F` search, `Ctrl+E` export, `Ctrl+D` diff snapshot, `Esc` cancel. |

---

## 5. First-Run Onboarding

Shown once on first launch, never again:

1. **PC Mode check:** Is ADB on PATH? Is the OS accessibility permission granted?
   - macOS: explicit permission dialog (`System Settings → Privacy → Accessibility`). UIScope shows the exact path and opens it for them.
   - Windows: no permission needed for UI Automation on most apps. If running in a limited account, a UAC note is shown.
2. **Android Mode check:** No ADB on PATH? UIScope offers a one-click download of minimal Android platform-tools (adb binary only, ~5 MB). Places it in the app's data directory. Done.
3. Both checks are shown as green ticks / amber warnings — nothing blocks the user from proceeding regardless.

---

## 6. Error States

| Error | What Shows | Fix Offered |
|---|---|---|
| ADB not found | "ADB not found on PATH" with amber icon | One-click download of platform-tools OR field to paste existing SDK path |
| No Android device detected | Checklist: USB Debugging on? Cable plugged in? Trust dialog accepted on phone? | Link to Android developer docs |
| UIAutomator dump fails | "Could not read device UI — is the screen on and unlocked?" | Retry button |
| PC Accessibility permission denied | "UIScope needs Accessibility access" with exact OS settings path | Button that opens that exact settings pane |
| Target app tree empty | "This app may be blocking accessibility — some system apps do this" | Explanation, no false promise of a workaround |

---

## 7. Technical Architecture

### Stack

| Layer | Technology | Reason |
|---|---|---|
| UI framework | Kotlin Compose Multiplatform (Desktop) | Native Windows/macOS/Linux. Same language as NodeSpy. Modern, beautiful. |
| PC UI tree (Windows) | Windows UI Automation via JNA | COM-based, ships with every Windows. No C++ needed. |
| PC UI tree (macOS) | macOS Accessibility API via JNA bridge | Built into every Mac. |
| Android tree | ADB via ProcessBuilder | `uiautomator dump` + `screencap`. Bundleable. No extra installs on phone. |
| Local storage | SQLDelight (SQLite) | Session history, bookmarks, settings. Multiplatform. |
| Build & packaging | Gradle + Compose JB packaging | Produces `.exe`, `.dmg`, `.deb`, `.AppImage` natively. |

### Project Structure

```
uiscope/
├── core/
│   ├── model/          # ElementNode, Session, Bookmark, DiffResult, WatchRule
│   ├── export/         # JSON, XML, outline exporters
│   └── codegen/        # Per-target code generators
├── engine/
│   ├── pc/             # Windows UIAutomation JNA + macOS Accessibility
│   └── android/        # ADB process manager, UIAutomator XML parser, screencap
├── ui/
│   ├── launcher/       # Mode picker screen
│   ├── inspector/      # Shared 3-panel shell
│   │   ├── TreePanel.kt
│   │   ├── VisualCanvas.kt
│   │   └── PropertiesPanel.kt
│   ├── codegen/        # Code output + copy bar
│   ├── history/        # Session history browser
│   └── settings/       # Hotkeys, theme, ADB path, polling interval
└── Main.kt
```

---

## 8. Development Phases

| Phase | Scope | Duration |
|---|---|---|
| **1 — Skeleton** | Mode picker, shared 3-panel layout (static), session model + SQLite, ADB device detection | Weeks 1–2 |
| **2 — Android Mode live** | UIAutomator dump parse, screencap display, bidirectional node↔screencap selection, basic code gen, JSON export | Weeks 3–4 |
| **3 — PC Mode live** | Windows UIAutomation JNA engine, screen overlay + hover detection, element lock + tree, PC code gen, session history | Weeks 5–6 |
| **4 — Polish & Ship** | Diff mode, Watch mode, search, bookmarks, global hotkeys, CI packaging, ProductHunt assets | Weeks 7–8 |
| **V2 (future)** | iOS support via libimobiledevice, CLI companion (`uiscope dump`), recording a sequence as a replay script | Post-launch |

---

## 9. Monetization

**UIScope is completely free. No tiers, no paid features, nothing locked behind a paywall.**

Every feature — PC mode, Android mode, Diff Mode, Watch Mode, multi-device, code generation, session history, export, bookmarks, custom templates — ships to every user at no cost.

No accounts. No subscriptions. No cloud. No license keys. No "upgrade" prompts.

This is itself a marketing advantage: the download page leads with *"Free. Forever. No account required."* In a space full of tools that gate the useful features behind a Pro plan, being unconditionally free drives word-of-mouth and removes every friction point between a user finding UIScope and actually using it.

---

## 10. Distribution & Growth

| Channel | Action |
|---|---|
| **GitHub Releases** | Every tagged release auto-builds `.exe`, `.dmg`, `.AppImage` via GitHub Actions CI. Free, trusted by developers. |
| **winget** | `winget install TBTechs.UIScope` — submit to winget-pkgs. Gets passive ongoing Windows installs. |
| **Homebrew Cask** | `brew install --cask uiscope` — Mac developers expect this. |
| **ProductHunt** | 60-second screen recording: hover → highlight → copy selector. Hits front page in the dev tools category. |
| **r/androiddev, r/QAtools, r/AutoHotKey** | Genuine posts with demo GIF. These communities vote up tools that solve real pain. |
| **Appium / Maestro / UiPath community Discords** | Direct audience. Engineers who need this tool daily. |
| **Dev.to / Hashnode** | Article: "I replaced Inspect.exe with something I built" — picked up by dev newsletters. |

---

## 11. Competitive Positioning

| UIScope vs. | UIScope advantage |
|---|---|
| Inspect.exe | Modern UI, search, export, code gen, cross-platform, actively maintained |
| Android Studio Layout Inspector | Lightweight standalone, works on any app (not just yours), no 2 GB install |
| Appium Inspector | No Appium server required, installs in seconds, far simpler UX |
| Accessibility Insights (Microsoft) | Covers Android too, code generation, session history, Diff mode |
| uiautomator dump (CLI) | Visual, interactive, bidirectional, no XML parsing by hand |

UIScope's unique position: **the only tool that covers both PC and Android in one install, with no server, no SDK requirement, and no account.**

---

## 12. Future V2 Ideas

- **iOS support** via libimobiledevice (no Xcode needed on Windows/Linux)
- **CLI companion**: `uiscope dump --adb 192.168.1.5 --output tree.json`
- **Replay recording**: capture a sequence of inspections and export as an executable test script
- **Plugin API**: third-party code-gen targets (Flutter driver, Detox, etc.)

---

## 13. Distribution & Installation Reality

### JVM Bundling
Compose Multiplatform Desktop packages the JVM runtime directly inside the installer. The user never needs to install Java. Trade-off: the installer is ~80–100 MB. In return, UIScope works on any machine out of the box — no "you need Java 17" friction, no version conflicts.

### Code Signing & Notarization
This is a real cost that must be planned before shipping:

| Platform | Problem | Solution | Cost |
|---|---|---|---|
| **Windows** | Unsigned `.exe` triggers SmartScreen: "Windows protected your PC" — most users click away | OV code signing certificate from Sectigo or Certum | ~$70–200/year |
| **macOS** | Unsigned `.app` is blocked by Gatekeeper entirely by default | Apple Developer Program + notarization via `xcrun notarytool` | $99/year |
| **Linux** | No OS-level signing enforcement | GPG-sign the release assets on GitHub | Free |

Without signing, the app will have significant drop-off at the install step regardless of how good it is. Budget this from day one.

### System Requirements

| Platform | Minimum OS | RAM | Disk |
|---|---|---|---|
| Windows | Windows 10 (1809+) | 150 MB | ~120 MB |
| macOS | macOS 12 Monterey | 150 MB | ~120 MB |
| Linux | Any modern distro with GTK3 | 150 MB | ~120 MB |

No internet connection required after install. Ever.

---

## 14. Android Connection — Full Detail

### USB Connection (All Android versions)
1. Enable Developer Mode (tap Build Number 7 times in Settings → About Phone).
2. Enable USB Debugging.
3. Plug in USB cable.
4. Phone shows **"Trust this computer?"** dialog — user must tap **Allow**.
   - UIScope shows a persistent banner: *"Check your phone and tap 'Allow' on the dialog."* This is the most common point of confusion for first-time users and is handled explicitly, not silently.
5. UIScope detects the device and shows it in the list.

### Wireless ADB — Pre-Android 11 (IP-based)
- User finds their device IP in Settings → Wi-Fi → device details.
- Enters `192.168.x.x:5555` into UIScope's manual connect field.
- UIScope runs `adb connect 192.168.x.x:5555` in the background.

### Wireless ADB — Android 11+ (QR Pairing)
Android 11 introduced pairing via QR code (Settings → Developer Options → Wireless Debugging → Pair device with QR code). UIScope shows a **Pair via QR** button that:
1. Opens a pairing dialog in UIScope.
2. UIScope starts a local pairing server and displays a QR code on screen.
3. User scans it with their phone.
4. Device appears in the list — no IP address needed.

This is significantly easier and should be the promoted connection method for Android 11+ users.

### Android Version Compatibility

| Feature | Minimum Android |
|---|---|
| UIAutomator tree dump | Android 4.3 (API 18) |
| ADB screencap | Android 4.0 (API 14) |
| IP-based wireless ADB | Android 8.0 (API 26) |
| QR code wireless pairing | Android 11 (API 30) |
| Best overall experience | Android 9.0 (API 28) recommended |

---

## 15. Handling Obfuscated / Missing Resource IDs

Many production apps use ProGuard or R8, which strips or scrambles resource IDs. A button might have `resourceId = "com.example:id/a"` or no resourceId at all.

UIScope handles this transparently:
- When `resourceId` is present and meaningful → uses it as the primary selector.
- When `resourceId` is obfuscated or absent → auto-generates a fallback selector using `className + index + text` (XPath or uiautomator By chain).
- A **yellow "fragile selector" warning badge** appears on the generated code with a tooltip: *"This element has no stable ID. The selector may break if the app updates."*
- User can manually edit the generated code in the bottom panel before copying.

---

## 16. Visual Canvas — Interactions

| Interaction | Action |
|---|---|
| Scroll wheel / pinch | Zoom in/out on the screenshot |
| Click + drag | Pan the canvas |
| Double-click | Fit screenshot to window (reset zoom) |
| Hover over element box | Shows element name in tooltip |
| Right-click on canvas | Context menu: Copy screenshot, Copy bounds, Save image |
| Right-click on node box | Copy selector, Jump to node in tree, Copy bounding rect |

A **zoom percentage indicator** is shown in the canvas corner. Useful for pixel-level inspection of small elements on high-DPI screens.

---

## 17. Node Breadcrumb Bar

Displayed between the tree panel and the properties panel:

```
Window › ContentPane › ScrollView › LinearLayout › Button[2]
```

- Shows the full path from the root to the currently selected node.
- Each crumb is clickable — clicking a parent node selects it.
- **"Copy as XPath"** button at the end of the breadcrumb copies the full absolute XPath path in one click.

---

## 18. Settings Screen — Full Contents

| Setting | Options | Default |
|---|---|---|
| ADB path | Auto-detect / Custom path | Auto |
| Android polling interval | 0.5 s / 1 s / 2 s / 5 s / Manual only | 2 s |
| Auto-refresh on mode entry | On / Off | On |
| Highlight colour (PC mode) | Any colour | Blue |
| Highlight colour (Android mode) | Any colour | Red |
| Global hotkeys | Remappable per action | See §4.5 |
| Theme | System / Light / Dark | System |
| Default export format | JSON / XML / Outline | JSON |
| Session history retention | Last 10 / 50 / 100 / Unlimited | Last 50 |
| Android screenshot quality | Full resolution / Scaled (faster) | Full |
| Auto-save sessions | On / Off | On |
| Launch at system startup | On / Off | Off |
| Minimize to system tray | On / Off | On |
| Pro license key | Text field | — |

---

## 19. System Tray & Startup Behaviour

When minimized, UIScope retreats to the **system tray** (Windows/Linux) or **menu bar** (macOS) rather than staying on the taskbar. Right-clicking the tray icon shows:

- **Quick Pick** — immediately activates pick mode in whichever mode was last used, without bringing up the full window
- **Open UIScope** — restores the full window
- **Switch to PC / Android** — switches mode directly from tray
- **Quit**

If **"Launch at system startup"** is enabled in settings, UIScope starts with the OS, hidden in the tray, consuming ~20 MB idle. Quick Pick via tray hotkey then works even without ever opening the main window.

---

## 20. Multi-Window Support

UIScope supports **two simultaneous windows**:
- One in PC mode, one in Android mode — inspect both surfaces at the same time.
- Open a second window via `File → New Window` or `Ctrl+Shift+N`.
- Each window has its own session, tree, and canvas independently.
- Useful for: comparing a desktop app to its Android counterpart side by side, or running two Android devices simultaneously (Pro feature).

---

## 21. Auto-Update Mechanism

UIScope checks for updates **silently at launch** by querying the GitHub Releases API for the latest tag. If a newer version exists:

- A subtle **"Update available: v1.x.x"** banner appears at the bottom of the window.
- Clicking it opens the GitHub Releases page in the browser.
- There is **no in-app auto-downloader or auto-installer** — users download and run the new installer themselves.

This keeps the app simple, auditable, and trustworthy. Users know UIScope never silently modifies itself.

---

## 22. Open Source Strategy & Licensing

UIScope is **fully MIT licensed** — the entire codebase, every feature, every mode, is open source on GitHub with no proprietary modules or hidden components.

| Reason | Detail |
|---|---|
| **Trust** | Developers can read exactly what the app does before installing it. Critical for a tool that reads other apps' UI trees. |
| **Discoverability** | Open source repos get indexed, starred, and linked. GitHub is itself a distribution channel. |
| **Contributions** | Community members can add code-gen targets, fix bugs, and port to new platforms. |
| **Longevity** | Even if development pauses, users can fork and maintain it themselves. |

There are no license keys, no activation, no proprietary anything. Download, inspect the code, build it yourself if you want.

---

## 23. Linux Specifics

PC mode on Linux uses **AT-SPI2** (Assistive Technology Service Provider Interface), the Linux accessibility standard supported by GNOME, KDE, XFCE, and most major desktop environments.

- Required package: `at-spi2-core` (pre-installed on most GNOME/KDE distributions).
- UIScope checks for AT-SPI2 on first launch in PC mode. If missing, shows: *"AT-SPI2 is required for PC inspection on Linux"* with the exact install command for the detected distro (apt / dnf / pacman).
- AppImage and `.deb` installers both supported.

---

## 24. Support & Community

| Channel | Purpose |
|---|---|
| **GitHub Issues** | Bug reports — pre-filled template asks for OS, version, steps to reproduce |
| **GitHub Discussions** | Feature requests, questions, show-and-tell |
| **Discord server** | Real-time community, faster feedback loop during early growth |
| **In-app feedback button** | Opens a GitHub Issue pre-filled with app version, OS, and mode — lowers the barrier to reporting |

Response SLA goal (solo/indie): issues acknowledged within 48 hours, PRs reviewed within 1 week.

---

*Document written: June 2026. Based on research of TITANICBHAI/NodeSpy and related repositories.*
