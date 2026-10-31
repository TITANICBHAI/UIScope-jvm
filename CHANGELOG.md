# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/); versions follow [Semantic Versioning](https://semver.org/).
Nothing has been released yet. Entries below describe only what exists in the code today.

## [1.0.1] — unreleased (the first tagged release; 1.0.0 was never published)

### Added
- Rebuilt foundations: multi-module build with a JDK 21 toolchain, CI (Windows and Linux), detekt rules against empty and swallowed catch blocks.
- Capture data model with stable, structure-only element ids, an element index and pure viewport math.
- Process runner with whole-tree kill on timeout and cancellation, long-lived process wrapper, single HTTP gateway, data-folder
  locations, rotating logs.
- App identity (`app.properties`) and version generated from `version.txt`.
- Test harness and synthetic Android dump fixtures.
- XML and outline export with redaction, a deterministic selector engine (reliability tiers, warnings, verdict), and code generation for
  uiautomator2, UI Automator, Appium (Java, Python), Maestro, XPath, pywinauto, FlaUI, AutoHotkey v2 and PowerShell with correct escaping.

- Android engine (not connected to the user interface yet): finds adb, lists devices and watches for changes, captures the UI tree and screenshot with
  fallbacks and retries, wireless-debugging commands, and a checksum-verified download of Google's platform-tools.

- Typed settings with a file-backed store, and the Android inspector's state machine (device tracking, capture, Live, disconnect/reconnect,
  selection restore) behind a port, ready for the interface.

- The full Compose user interface: the Inspect screen for both targets (tree | screenshot canvas | details with
  selector, properties and path tabs, pane splashes and keyboard shortcuts), the Snapshots screen (search, sort,
  pin, notes, offline replay, `.uiscope` export/import), the export dialog (JSON/XML/Outline/PNG with redaction),
  Settings, first-run and Help screens, and the storage meter.
- Windows app inspection: the bundled helper process (UI Automation tree, screenshots, pick overlay with hover,
  click-swallowing and Esc-cancel, the `Ctrl+Alt+P` hotkey), typed errors per Appendix A (elevated targets,
  limited accessibility, truncation), and the origin-corrected canvas.
- Snapshots with retention (count/byte caps, pinned exempt, files deleted with rows) and a storage meter.
- Reliability: single-instance lock (a second launch focuses the first window), a crash dialog with
  Copy-diagnostics/Restart, automatic helper restarts, and the security guardrails + dependency scanning in CI.

### Changed
- The application window is a temporary developer harness (Android capture check) while the user interface is rebuilt.

### Removed
- Features that were not going to ship in 1.0 were removed from the build.
