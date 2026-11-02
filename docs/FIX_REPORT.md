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
