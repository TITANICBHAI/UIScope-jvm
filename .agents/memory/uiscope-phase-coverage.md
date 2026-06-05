---
name: UIScope phase coverage
description: Which spec phases are implemented vs deferred for UIScope Kotlin desktop app
---

**Why:** The spec has 4 phases; Phases 1–3 are complete. Phase 4 items should not be confused with missing work.

## Phases 1–3 (COMPLETE)

- LauncherScreen: mode picker with two cards, last-mode memory, History + Settings nav buttons
- OnboardingScreen: ADB + OS accessibility check, first-run gating
- InspectorScreen: 3-panel layout (Tree | Canvas | Properties), PC pick mode, Android ADB mode
- TreePanel: lazy virtualised tree, collapse depth ≥3 for >1000 nodes, search/filter mode (Ctrl+F)
- VisualCanvas: zoom/pan, hit-test, bidirectional selection, tooltips, context menu
- PropertiesPanel: Android + PC property sets
- BreadcrumbBar: clickable ancestor path
- CodegenPanel: Android (Espresso/UI Automator/Appium) + PC (PyWinAuto/PowerShell/AHK), fragile selector badge
- WirelessAdbDialog: wireless ADB pairing UI
- HistoryScreen: session list, search, tree JSON preview, export JSON, delete
- SettingsScreen: ADB path, theme, polling interval, auto-refresh, export format, history retention
- Main.kt navigation: ONBOARDING → LAUNCHER ↔ INSPECTOR ↔ HISTORY / SETTINGS
- Core models: ElementNode, Session, Bookmark, DiffResult, WatchRule, AutoPinRule
- Core export: TreeExporter (JSON/XML/outline), RuleAnalyzer (confidence scoring, fragile badge)
- Repositories: SessionRepository, BookmarkRepository, SettingsRepository
- PC engines: Windows UIAutomation (JNA), macOS AX API (JNA), Linux AT-SPI2 (JNA)
- Android engines: AdbManager, AdbDevice, UiAutomatorParser, ScreencapManager
- SQLDelight schema: sessions, bookmarks, app_settings

## Phase 4 (NOT YET BUILT — explicitly deferred in spec)

- jnativehook global hotkeys (Alt+Shift+P from any app)
- System tray (Windows/Linux) / menu bar (macOS)
- Auto-update check (GitHub Releases API)
- Multi-window (File → New Window)
- Diff mode UI (DiffResult model exists, UI not built)
- Watch mode UI (WatchRule model exists, UI not built)
