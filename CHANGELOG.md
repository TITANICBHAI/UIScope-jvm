# Changelog

All notable changes to UIScope are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
UIScope uses [Semantic Versioning](https://semver.org/).

---

## [Unreleased]

_Nothing yet._

---

## [1.0.0] — 2026-06-05

First public release — all four product modes ship in a single installer.

### Added

#### PC Inspector (Phase 3)
- Live accessibility tree inspection for any window on Windows, macOS, and Linux
- Windows UIAutomation via JNA — no C++ code, pure Kotlin
- macOS AX API via JNA (ApplicationServices.framework)
- Linux AT-SPI2 via JNA (D-Bus / atspi-2.0)
- Alt+Shift+P global hotkey — locks pick mode on the element under the cursor from any app
- Hover highlight overlay drawn on the desktop (overlay window, always-on-top)
- PC code generation: AutoHotKey v2, Python pywinauto, C# FlaUI, PowerShell

#### Android Inspector (Phase 2)
- USB and wireless ADB device discovery (auto-scans every 5 s)
- UIAutomator tree dump via `adb exec-out uiautomator dump /dev/tty` — no on-device agent
- Live screencap refresh (configurable 1–30 s interval)
- Bidirectional selection — click node → highlight on screenshot, click screenshot → select node
- Wireless ADB: pre-Android 11 IP connect + Android 11+ pairing code flow
- Android code generation: Python uiautomator2, Kotlin UIAutomator2, Appium Java, Appium Python, Maestro YAML, XPath

#### Shared Inspector features
- Three-panel layout: Element Tree | Device Canvas | Properties panel
- Properties panel: all node fields, one-click copy per field, "Fragile selector" warning badge
- Breadcrumb bar — full ancestor path, click any crumb to jump up
- Bookmarks — pin/unpin any node, persisted to SQLite
- Search / filter (Ctrl+F) — fuzzy match across all node fields in real time
- Export: JSON, XML, UIAutomator outline
- Session auto-save to `~/.uiscope/uiscope.db` + screenshot on every capture

#### Diff Mode
- Pick any two saved sessions
- Three-column view: added elements (green), removed elements (red), changed elements (amber)
- Property-level diff for changed nodes

#### Watch Mode
- Define rules: element appears / disappears / text matches / text changes
- Continuous monitoring on connected Android device
- Toast notification + log entry when a rule fires

#### App shell
- Launcher screen — mode picker with last-mode memory
- Onboarding screen — first-run ADB and OS accessibility check
- History screen — browse, search, and re-open past sessions
- Settings screen — ADB path, polling interval, theme, export format, screenshot quality, tray behaviour, startup mode
- System tray (Windows/Linux) with quick-launch menu
- Global hotkeys: Alt+Shift+P (pick), Alt+Shift+R (refresh), Ctrl+F (search)
- Auto-update check at launch — silent GitHub Releases API call, animated banner if newer version found
- Multi-window: File → New Window (Ctrl+Shift+N) opens a fully independent second inspector
- Native menu bar: File (New Window, Quit) + View (theme toggle) on every window
- Dark theme by default; Light theme switchable per-session in View menu or Settings

#### Distribution
- Windows: EXE (NSIS), MSI (enterprise), MSIX (Microsoft Store compatible)
- macOS: DMG, PKG — optional notarization via GitHub secrets
- Linux: DEB (Ubuntu/Debian), AppImage (universal)
- GitHub Actions CI — auto-builds all 7 installer formats on every `v*.*.*` tag push

### Technical
- Kotlin 2.0.0 / Compose Multiplatform Desktop 1.6.11
- JVM target 19 (GraalVM CE 22.3.1 for local dev; Temurin 21 in CI)
- SQLDelight 2.0.2 — SQLite stored at `~/.uiscope/uiscope.db`
- JNA 5.14.0 — no native code, pure Kotlin bindings to OS accessibility APIs
- jnativehook 2.2.2 — system-wide hotkeys without OS permissions dialogs
- Software renderer forced on Linux (`-Dskiko.renderApi=SOFTWARE`) for VNC/headless stability

---

## [0.1.0] — 2026-05-01 _(internal skeleton)_

- Project scaffold: Gradle multi-module (core / engine / ui / app)
- SQLDelight schema: sessions, bookmarks, app_settings
- Core models: ElementNode, Session, Bookmark, DiffResult, WatchRule
- LauncherScreen and OnboardingScreen skeletons
- Compose Multiplatform Desktop wired up with UiScopeTheme

---

[Unreleased]: https://github.com/TITANICBHAI/UIScope-jvm/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/TITANICBHAI/UIScope-jvm/releases/tag/v1.0.0
[0.1.0]: https://github.com/TITANICBHAI/UIScope-jvm/releases/tag/v0.1.0
