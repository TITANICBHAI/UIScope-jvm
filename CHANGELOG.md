# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/); versions follow [Semantic Versioning](https://semver.org/).
The newest version is on top. The app's "What's new" window and the website's changelog page are generated from this file.

## [1.0.2] — 2026-11-04

A reliability release for the two things UIScope exists for: seeing an Android screen, and inspecting a Windows app.

### New
- **Your phone's screen shows up first.** The picture appears as soon as it is captured, usually a second or two before UIScope has finished reading the elements, and a "Reading the elements..." cue tells you the rest is coming.
- **Windows: choosing a window brings it to you.** A minimized window is restored, the window is brought to the front, UIScope reads it and then comes back in front with the result. Turn it off in Settings if you prefer windows to stay where they are.
- **Show window** button: bring the window you are inspecting to the front again at any time, restoring it if it was minimized.
- **Keep UIScope on top** (Settings and the View menu): stay visible next to the app you are inspecting.
- **A hint next to your pointer while picking.** UIScope minimizes itself while you pick, so the instructions and the name of the element under the pointer now float beside it.
- **Help and About, with the links in plain sight**: version and build, Website, Support, Documentation, Troubleshooting, Privacy and Source, one-click **Copy diagnostics**, and this changelog. A small version button in the status bar opens "What's new" from anywhere, and a window shows it once after every update.
- **Plain-words errors that stay on screen.** When a capture fails (Android or Windows) a banner now says what happened and what to do, with the technical reason under it. Before, only a word in the status bar changed.
- A line above the Android screen says which phone, Android version, screen size, app and time you are looking at.

### Fixed
- **Android: the phone's screen never appeared.** A capture is a screenshot plus an element dump, and when the dump failed (video, games, animations, apps that block accessibility, or another automation tool) the good screenshot was thrown away with it. The screenshot is now always shown; only the elements are missing, with the reason.
- **Android: a good screenshot was rejected as "not an image".** Phones with several displays print a warning in front of the PNG, and older adb versions on Windows corrupt line endings. UIScope now finds the picture inside the output, repairs it when it can, and has a second way of fetching it through a file on the phone.
- **Android: one undecodable screenshot could end the whole capture.** The optional "is this screen blank" check no longer takes the capture down with it.
- **Android: slow phones and Wi-Fi debugging timed out** after 10 s while saving the screenshot; the limit is now 20 s.
- **Windows: a minimized window was a dead end** ("restore it and capture again"). It is restored automatically.
- **Windows: the screenshot could show UIScope instead of the window.** When the window could not paint itself, UIScope copied the screen while standing in front of it. The window is now brought forward first.
- **Windows: picking an element never switched to the window.** The click that picks is swallowed so the application below does not react, which also meant Windows never activated it. Picking now brings the window forward for its screenshot and then returns to UIScope with the result.
- **Windows: the "Choose window..." list was read once at start-up**, so windows opened later were missing and closed ones lingered. It is read again every time it opens, minimized windows are marked, and UIScope's own window is not listed.
- **Windows: an unexpected failure during a capture could leave the screen stuck on "Capturing"** for good. It is reported and the next capture works.

### Improved
- The Android and Windows buttons at the top show which one is selected.
- The Android and Windows screens say what is happening and what to do next instead of showing an empty area.
- Error copy for every capture problem was rewritten to say that the screen is still shown when only the elements are missing.

### Development history (not shown in the app)
- Everything below this heading is the engineering log of the 1.0.x work that came before, kept for the repository.

## [1.0.1] — engineering log (the work that led up to 1.0.2)

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
