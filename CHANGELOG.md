# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/); versions follow [Semantic Versioning](https://semver.org/).
Nothing has been released yet. Entries below describe only what exists in the code today.

## [1.0.0] — unreleased

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

### Changed
- The application window is a temporary developer harness (Android capture check) while the user interface is rebuilt.

### Removed
- Features that were not going to ship in 1.0 were removed from the build.
