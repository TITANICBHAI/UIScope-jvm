---
name: UIScope phase coverage
description: Which spec phases are implemented vs deferred for UIScope Kotlin desktop app
---

**Why:** The spec has 4 phases; all phases are now complete as of June 2026.

## All Phases COMPLETE

### Phase 1 — Skeleton
- LauncherScreen: mode picker with two cards, last-mode memory, History + Settings nav buttons
- OnboardingScreen: ADB + OS accessibility check, first-run gating
- InspectorScreen: 3-panel layout (Tree | Canvas | Properties), PC pick mode, Android ADB mode
- Core models: ElementNode, Session, Bookmark, DiffResult, WatchRule, AutoPinRule
- Repositories: SessionRepository, BookmarkRepository, SettingsRepository
- SQLDelight schema: sessions, bookmarks, app_settings

### Phase 2 — Android Mode live
- AdbManager, AdbDevice, UiAutomatorParser, ScreencapManager
- Bidirectional node↔screencap selection
- Android code gen: uiautomator2, Kotlin UIAutomator2, Appium, Maestro, XPath
- JSON/XML/outline export via TreeExporter

### Phase 3 — PC Mode live
- Windows UIAutomation (JNA), macOS AX API (JNA), Linux AT-SPI2 (JNA)
- PC overlay + hover pick mode + element lock + event subscription
- PC code gen: AutoHotKey v2, Python pywinauto, C# FlaUI, PowerShell
- Session history saved to SQLite on every inspection capture

### Phase 4 — Polish & Ship (ALL BUILT)
- GlobalHotkeyManager: jnativehook, Alt+Shift+P/R/Ctrl+F from any app
- HotkeyBus: SharedFlow event bus wired into InspectorScreen
- System tray (Windows/Linux) / menu bar (macOS) — Main.kt Tray + menu items
- Auto-update: silent GitHub Releases API check at launch, animated banner
- Diff mode UI: DiffScreen — pick two sessions, compute added/removed/changed nodes
- Watch mode UI: WatchScreen — add rules, start/stop monitoring on connected Android device
- Multi-window: File → New Window (Ctrl+Shift+N) opens a fully independent second window
- MenuBar: File menu (New Window, Quit) + View menu (theme toggle) on all windows
- Bookmarks: toggle pin in PropertiesPanel, persisted via BookmarkRepository
- Search/filter: Ctrl+F in TreePanel, fuzzy match across all node fields
- SettingsScreen: all §18 settings (ADB path, polling, theme, export, history, screenshot quality, tray, startup)

### Distribution (ALL BUILT)
- App icon: icon.png (512x512), icon.ico (multi-size), icon.icns placeholder (macOS — needs Mac to generate)
- MSIX assets: all 8 required PNGs generated from master icon
- AppxManifest.xml: complete, CI workflow patches version automatically
- version.txt: `1.0.0` — CI overrides with tag name
- CHANGELOG.md: comprehensive, covers all phases
- LICENSE: MIT
- GitHub Actions CI: release.yml builds EXE/MSI/MSIX/DMG/PKG/DEB/AppImage on every v*.*.* tag

## V2 (future — not planned yet)
- iOS support via libimobiledevice
- CLI companion: `uiscope dump --adb 192.168.1.5 --output tree.json`
- Replay recording: capture sequence → export as executable test script
- Plugin API: third-party code-gen targets
- icon.icns (macOS) — must be generated on a macOS machine with `sips` + `iconutil`
