# Manual QA — owner's checks

An AI agent can write and unit-test the code, but it cannot see your screen or touch your phone. These checklists are the parts that
need a human. Run the relevant round when it is announced, note **pass/fail for each step**, and paste *Settings → Advanced → Copy
diagnostics* with any failure (that menu arrives with the Settings screen in Phase 4).

Rounds are seeded from Appendix K of the plan. Every acceptance box tagged `needs manual verification` is closed here.

## Open items right now

| From | Check | Status |
|---|---|---|
| P0-1 | `./gradlew check` is green on a clean clone on **Windows and Linux** (CI) | **verified on CI** — run 41109448073 (commit `8f8ce06`) green on `windows-latest` + `ubuntu-latest` with temurin 21 provided by `setup-java` (the foojay *auto-provisioning* path itself was not exercised — the runner already had a JDK) |
| P0-1 | detekt runs inside `check` with Kotlin 2.0 (workaround for detekt 1.23 is in `build.gradle.kts`) | **verified on CI** — detekt runs and is clean in every green run since `8f8ce06` |
| P0-2 | `./gradlew :app:run` opens a window (now the developer harness described under Round 1) | **not yet verified** — a desktop launch cannot be checked on CI; needs a human at a display |
| P0-7 | Round 0 fixture capture done on your phone | **pending — your turn** |
| P2-4 | `scripts/update-platform-tools.sh <revision>` (or `.ps1`) pins the platform-tools download; tell me if the archive URL pattern is wrong | **pending — needs internet access** |
| P2-1…P2-6 | The Android engine works against a real phone (device list, dump, screenshot, wireless pairing) | **not yet verified** — only fakes and fixtures were available; Round 1 covers it once the UI exists |
| P2-5 | Early check 1a (Android capture from the Inspect screen) | **pending — needs a display for `./gradlew :app:run`**; the real Inspect screen (P4-3) has replaced the temporary developer window: same *Capture now* button, the adb line, the device list and the element count in the status bar |
| P1-5 | One generated snippet per framework you use actually runs (Maestro especially: `id:` with the full `pkg:id/name`) | **not yet verified** — nothing could be run against the real frameworks where this was written |
| P4-5 | Canvas interaction is smooth on a 3k-node capture (zoom/pan/hover) | **not yet verified** — needs a real display (CI has no eyes) |
| P4-5 | Windows captures align pixel-perfect at 100–200 % display scaling (Appendix K, Round 2) | **not yet verified** — needs your Windows PC at 125–150 % scaling |

## Round 0 — Fixture capture (at the very start, about 10 minutes)

Needs: your Android phone, a USB data cable and `adb`. Do step 1 of Round 1 (USB debugging), then run `scripts/capture-fixtures.sh`
(macOS/Linux) or `scripts/capture-fixtures.ps1` (Windows) and follow its prompts to save five different screens (Settings, a Jetpack
Compose app if you have one, a web page in a WebView, a long list, a dialog over a screen). The scripts print a privacy reminder: they
record what is on screen, so use screens without personal data. Hand the output folder over.

## Round 1 — Android (after Phase 4, the first time the real UI exists)

Needs: your Android phone and a USB data cable.

**Early check 1a (available now, before the real UI exists):** connect the phone with USB debugging on, run `./gradlew :app:run`, accept the
*Allow USB debugging?* prompt on the phone, then press *Capture now* in the developer window. **Pass if** the window names the adb it found,
lists your phone, reports a non-zero element count for the phone's current screen, and *Save screenshot* writes a PNG that looks like the
phone's screen. If it fails, copy the window's text and the newest file in the `logs` folder of the data folder.

1. On the phone: Settings → About phone → tap *Build number* 7 times → Developer options → turn on *USB debugging*. Plug in and tap *Allow* on the phone.
2. Start UIScope. **Pass if** your phone's model appears within about 5 seconds and one capture happens by itself.
3. Open three different apps (Settings, a game, one of your own) and press Capture each time. **Pass if** the screenshot and tree match the phone and nothing spins for more than about 20 seconds.
4. Click an element on the screenshot. **Pass if** the tree selects the same element and the Selector tab shows a verdict with a match count.
5. Copy code for each target and paste it into a text editor. **Pass if** text containing an apostrophe or quote (for example "Don't allow") still looks like valid code.
6. Turn Live on for one minute and scroll on the phone. **Pass if** UIScope stays responsive, the tree keeps its expansion, and the snapshot count does not grow unless you press Save.
7. Unplug the phone during Live, then plug it back in. **Pass if** you see "Disconnected — showing last capture" and it reconnects afterwards.
8. Open a banking or other secure app screen. **Pass if** you get the "may block capture" hint instead of a mystery blank.

## Round 2 — Windows pick (after Phase 4)

Needs: your Windows PC. Do it once at your normal display scaling and once at 125 % or 150 % (and with a second monitor, if you have
one). *Optional early check 2a, right after P3-4:* run `UIScope.Helper.exe --demo-pick` — **pass if** hovering draws a rectangle that
hugs the element, a click is *not* delivered to the app underneath, and the picked element prints as JSON.

1. Press Pick (or `Ctrl+Alt+P`) and hover Notepad, Calculator, File Explorer and Edge. **Pass if** the highlight rectangle hugs the element you point at and the label shows its type and name.
2. In pick mode, click a *Close*, *Save* or *Delete* button of another app. **Pass if** UIScope captures it and the other app's button is **not** triggered.
3. Start pick mode and press `Esc`; start it again and right-click. **Pass if** pick mode ends and nothing else happens.
4. After picking, compare UIScope's screenshot overlay with the real window. **Pass if** the highlight lines up with no shift at both scalings.
5. Start Task Manager as administrator and try to pick it. **Pass if** you get the "runs as administrator" message.
6. In another app press `R` and `Ctrl+F`. **Pass if** UIScope does nothing.
7. Pick a Chrome or Edge page and a VS Code window. **Pass if** you see a real tree (or the "limited accessibility" hint), not an empty one.

## Round 3 — Everyday use and install (after Phase 5, and again before release)

1. Save a snapshot, close UIScope, reopen it, and open the snapshot from the Snapshots screen. **Pass if** the tree and screenshot are there with no device attached.
2. Export JSON and paste it into any JSON validator. **Pass if** it is valid.
3. In Settings switch between Light, Dark and System. **Pass if** it applies immediately and *Reset* works.
4. Launch UIScope twice. **Pass if** the second launch just brings the first window forward.
5. Shrink the window to its smallest size. **Pass if** nothing is cut off and the toolbar has an overflow menu.
6. Install the MSIX (sideload) on a clean user account or VM, run the core loop, then uninstall. **Pass if** there are no errors and nothing is left behind.
7. In Windows *Installed apps*, check the entry. **Pass if** the name is "UIScope" and the publisher is "TBTechs".

## Round 5 additions — storage, bundles, update check (needs the app wired, or a developer console)

1. **SQLite driver on Windows (first start):** start the app on a clean user account. **Pass if** the first snapshot saves without an error and `%LOCALAPPDATA%\UIScope\db\snapshots.db` exists. (The driver unpacks a native library into `<data>\tmp`; antivirus can interfere.)
2. **Delete while the preview is open (Windows):** open a snapshot's screenshot, then delete that snapshot. **Pass if** it disappears and `%LOCALAPPDATA%\UIScope\snapshots` has no folder for it afterwards (a locked folder is retried at the next start).
3. **Data folder on another drive or a network share:** point nothing there; just confirm saving works when `%LOCALAPPDATA%` is redirected (some company PCs). **Pass if** no `ATOMIC_MOVE` error appears in the log.
4. **Kill during save:** while Live with auto-save is running, end the process in Task Manager, start again. **Pass if** the list opens, no half snapshot is shown, and `snapshots\` holds no `.tmp-*` folder after start-up.
5. **Import a bundle made on another machine** (export one from the Linux dev box or from a second install). **Pass if** tree, screenshot, title and note come back and the device serial is empty.
6. **Update check with no internet** (unplug): **Pass if** nothing is shown and the log has one INFO line "update check failed".
7. **Update check against the real repository** once the repo URL in `app.properties` is real: **Pass if** a newer release shows "What's new" linking to the release page, and an older or equal one shows nothing.
