# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/); versions follow [Semantic Versioning](https://semver.org/).
Nothing has been released yet. Entries below describe only what exists in the code today.

## [1.0.1] — unreleased (the first tagged release; 1.0.0 was never published)

### Fixed — the two core features (reliability pass)
Both inspection targets were built and unit-tested against fakes but had never run end to end; the first real run exposed these.
- **Android: "Download platform-tools" could never work.** The build shipped an empty pinned manifest, so the download was permanently
  unavailable and anyone without adb on `PATH` was stuck. The release is now looked up in Google's own SDK package list at the moment the user agrees
  (versioned archive, size and checksum verified; any address off `dl.google.com` is refused). A pinned build entry still wins.
- **Android: one odd `PATH` entry stopped the adb search.** A quoted folder or a character the file system rejects made adb discovery throw, so the
  Android side sat on "looking for adb". Such entries are now cleaned up or skipped, and the old standalone SDK folder is checked as well.
- **Windows: the helper's tree walk took minutes on real windows and always hit the 5 s timeout**, which killed and restarted the helper until
  it gave up. The walk now fetches each element with its children in one call (UI Automation caching), has a node and time budget, and is
  flagged truncated instead of hanging; captures get a 90 s backstop instead of 5 s.
- **Windows: pick mode could not work.** It started a second message loop on the thread that already ran one (an exception, caught by a
  dialog nobody could see), drew a solid frame with no hole that sat under the pointer and was hit-tested instead of the element, did slow
  work inside the low-level mouse hook, and let half a click through. It was rewritten: no nested loop, a click-through outline, hover queries on a
  worker thread, the click and its release both swallowed.
- **Windows: the screenshot showed whatever was on top of the window**, UIScope itself included. Windows are now asked to paint themselves
  (`PrintWindow`), with a screen copy as the fallback.
- **Windows: the pick shortcut was never registered**, and the key table registered `Ctrl+Alt+F1` instead of `Ctrl+Alt+P` (lower-case letters are
  function keys). It is now registered from the settings once the helper is up, conflicts are reported (`HOTKEY_IN_USE` used to arrive as a
  generic error), and the setting gained a switch.
- **Windows: protocol hardening.** Control characters in a window title made the whole window list unreadable; the helper's text encoding depended on
  the console code page; a helper that exited left every waiting request to sit out its timeout and a failed handshake left a dead helper
  registered; the helper process never exited when UIScope did (its UI thread kept it alive); elevated targets were misjudged when the helper was
  itself elevated; the manifest placed `longPathAware` outside the schema; and the staged exe was copied without closing the resource stream or handling a locked file.
- Screenshots the helper takes are written to UIScope's own scratch folder (they used to pile up in the system temp folder forever).

### Changed
- The window is titled **UIScope**, not "developer harness"; the View menu of permanently disabled items is gone; Help gained working
  Keyboard shortcuts, Website, Troubleshooting and Report an issue items.
- New *Settings → Windows* switches for the pick shortcut and for minimizing UIScope while picking (both existed only inside `settings.json`).
- `UIScope.Helper.exe --selftest` prints what works and what does not (UI Automation, window list, a timed capture, screenshot, mouse hook).
- Copy diagnostics now includes both inspectors' states and the helper's process and stderr summary.
- A project website lives in `web/` (plain HTML, no external requests) and the app links to it.

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
