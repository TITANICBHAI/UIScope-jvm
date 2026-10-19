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

### Changed
- The application window is a placeholder while the user interface is rebuilt.

### Removed
- Features that were not going to ship in 1.0 were removed from the build.
