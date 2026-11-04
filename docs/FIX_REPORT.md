# Fix report, round 2 (version 1.0.2): the phone screen never appeared, and Windows windows were not switched to

Round 1 (below) fixed what could be found by reading the code. On a real phone and PC the two core features still failed. This round
starts from the symptoms and finds the cause of each.

## Android: "UIScope doesn't even show me my phone's screen"

| # | Cause | Effect | Fix |
|---|---|---|---|
| A1 | A capture was all-or-nothing: if `uiautomator dump` failed, the finished screenshot was thrown away with it | any video, game, animation, app that blocks accessibility, or another automation tool (Appium, uiautomator2) meant no screen at all | the screenshot is always delivered; a failed dump gives a picture with no elements and a warning that names the reason |
| A2 | The screenshot was accepted only if the output *started* with the PNG signature, but `adb exec-out` merges the phone's warnings (multi-display phones print one) into the same stream; old Windows adb also turned LF into CR LF | a perfectly good picture was rejected as "not a PNG" | the PNG is found inside the output (noise in front and behind cut away, CR LF damage repaired); a second route fetches it through a file on the phone; the remembered route goes first next time |
| A3 | Nothing showed why a capture failed: `CaptureFailed` and the capture warnings were never rendered on Android (only the status bar's word) | "I don't even know what is what" | banner with the reason and its fix, warning rows (with Retry), the specific detail under the explanation |
| A4 | The screen waited for the slowest half (the dump takes 1 to 30 s, the screenshot under 1 s) | a long blank wait | the picture is published the moment it is saved ("Reading the elements..." until the tree follows) |
| A5 | One undecodable screenshot (odd colour profile) threw out of the optional blank-screen check and failed the capture; screenshot timeout 10 s | failures on some phones and on Wi-Fi debugging | the check is best-effort, timeout 20 s |
| A6 | The Android and Windows chips looked identical whether selected or not | unclear which side is active | selected state shown |

## Windows: "I select the window but it doesn't switch to it, it just minimizes everything"

| # | Cause | Effect | Fix |
|---|---|---|---|
| W12 | The helper never restored or raised a window (there was no `SetForegroundWindow`/`ShowWindow` anywhere): a minimized window was a dead end, and the screen-copy fallback photographs whatever is on top (UIScope) | choosing a window did nothing visible; pictures of the wrong thing | `captureWindow` restores, brings the window forward (polite call, then the input-queue and F24 tricks, then `SwitchToThisWindow`), lets it repaint, captures, then raises UIScope again; new `activateWindow` request for the Show window button |
| W13 | Pick mode swallows the click (so the target app never reacts), UIScope had minimized itself, and it came back *before* the capture, on top of the window being read | "it minimizes everything and nothing switches" | UIScope stays out of the way until the capture ends, and the helper raises it again itself |
| W14 | The window list was read once at start-up | windows opened later were missing, closed ones lingered, UIScope itself was listed | read again each time the list opens; minimized windows marked; UIScope's own window left out |
| W15 | The capture failure was never displayed; an exception inside a capture left single-flight locked on "Capturing" for good | silent failures, a frozen screen | banner with reason and action; exceptions become `CaptureFailed`, the next capture works |
| W16 | The "click to inspect, Esc cancels" hint lived only inside UIScope's window, which is minimized while picking | nothing on screen said what was happening | the helper draws a label beside the pointer: element type and name, and how to finish |

## Product polish

- Version **1.0.2**; `CHANGELOG.md` rewritten user-first (engineering history kept under it); bundled into the app at build time and rendered on the website by `scripts/build-changelog-page.py`.
- **Help and About** shows the version, build, publisher and every link (Website, Support, Documentation, Troubleshooting, Changelog, Privacy, Source, Report an issue), *Copy diagnostics*, the release notes and the shortcuts. A status-bar button (`v1.0.2 - What's new`, `Support`) is visible on every screen, and *What's new* opens once after an update.
- Settings: *Bring a window to the front when I choose it* (default on), *Keep UIScope on top* (also in the View menu).
- The in-app website and support links point at the live site (`uiscope.pages.dev`); the site gained `support.html` and `changelog.html`.

## How this round was checked, and what was not

- `core` (227 tests) and `engine` (218 tests) compiled and passed, including new tests for the PNG recovery, the screen-without-elements capture, the early picture, the window activation requests, the list refresh and the crash-safe capture.
- The C# helper compiled under C# 5 rules with Mono and its logic checks passed.
- **Not verified here**: the Compose modules (`ui`, `app`: only checked for syntax), the helper on real Windows, and anything on a real phone. `docs/MANUAL_QA.md` Round 12 is the checklist for your hardware.

---

# Fix report: why Android and Windows did not work, and what changed

The app was built and tested against fakes (210+ unit tests, green CI) but neither inspector had ever run against real hardware, and the
handoff documents said so ("not yet verified" on every device row). Reading the code with that in mind turned up the following. Items are
ordered by how likely they were to be the thing you hit.

## Android

| # | Problem | Effect for you | Fix |
|---|---|---|---|
| A1 | `PlatformToolsManifestData` shipped **empty** | *Download platform-tools* was permanently unavailable; with no adb on `PATH` the Android side could never start | `PlatformToolsFeed` reads Google's SDK package list when you agree to the download; versioned archive, size + checksum verified, only `https://dl.google.com` accepted |
| A2 | `AdbLocator` threw on a quoted or invalid `PATH` entry | the adb search aborted, status stuck on "looking for adb" | entries are un-quoted or skipped; old SDK folder checked too |
| A3 | `UIA_UNAVAILABLE`-style copy was misleading elsewhere | n/a | clearer wording and "Copy details" |

## Windows

| # | Problem | Effect for you | Fix |
|---|---|---|---|
| W1 | UI Automation walk read ~12 properties per element with separate cross-process calls, against a **5 s** client timeout | any real window timed out; the client killed and restarted the helper 3 times, then gave up with "Windows inspection isn't available" | cached walk (one call per element), 25 s budget with a *truncated* flag, 90 s backstop |
| W2 | Pick mode started `Application.Run` inside the running message loop | exception on the first pick (hidden dialog) | session runs on the existing loop |
| W3 | The pick "outline" was a solid red window with no hole, under the pointer | hit-testing found the frame, never the element | click-through layered frame with a transparent middle; hover queries ignore the helper's own windows |
| W4 | Slow work inside the low-level mouse hook; the click-up leaked to the other app | system-wide pointer lag; stray clicks | hook only records; work runs on other threads; both button events swallowed |
| W5 | Screenshot used `CopyFromScreen` | picture of whatever was on top (often UIScope) | `PrintWindow` (full content) with the screen copy as fallback |
| W6 | Hotkey never registered anywhere; key table gave **F1** for `P` | the shortcut did nothing; if it had been registered it would have been Ctrl+Alt+F1 | registered from settings once the helper is up, correct key codes, `HOTKEY_IN_USE` reported, Settings switch |
| W7 | `Console.OutputEncoding`, unescaped control characters, duplicate JSON keys | possible handshake failure; one odd window title broke the window list | explicit UTF-8 streams, full JSON escaping, no duplicate keys |
| W8 | A helper that died left requests waiting out their timeout; a failed handshake left a dead helper registered; the helper never exited with the app | long hangs, orphan processes locking the exe | fail fast and respawn once; clean up after a failed handshake; the helper exits when stdin closes |
| W9 | Elevated-target check ignored an elevated helper, and "cannot open" counted as elevated | false "runs as administrator" errors | elevation judged properly |
| W10 | `longPathAware` outside the manifest schema; helper copied without closing its stream | possible side-by-side startup error; file-lock failures | manifest fixed; staging rewritten |
| W11 | Screenshots written to the system temp folder, never deleted | disk growth | scratch folder in the data folder, pruned after 24 h |

## Shell and polish

- The window said "developer harness"; View menu and three Help items were permanently disabled stubs.
- `hideWhilePicking` and the pick hotkey settings existed with no UI and no behavior.
- Everything now links to the website (`web/`), and *Copy diagnostics* includes both inspectors and the helper's process and stderr summary.

## How this was checked, and what was not

- Kotlin `core` and `engine` modules were compiled and their test suites run (with a shim for the older coroutines library available where this
  was written; CI uses the real one).
- The C# helper was compiled with Mono under **C# 5 rules** against stand-ins for UI Automation, and its pure logic (JSON escaping, request
  parsing, key codes) was run. See `native/windows-helper/tests/check.sh` (also a CI job now).
- **Not verified here**: the helper on real Windows, anything on a real phone, and the Compose modules (`ui`, `app`) — they were reviewed
  but not compiled. CI will be their first compile. `docs/MANUAL_QA.md` Round 11 is the exact checklist for your hardware.
