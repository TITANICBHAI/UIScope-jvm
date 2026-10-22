# Manual QA — owner's checks

An AI agent can write and unit-test the code, but it cannot see your screen or touch your phone. These checklists are the parts that
need a human. Run the relevant round when it is announced, note **pass/fail for each step**, and paste *Settings → Advanced → Copy
diagnostics* with any failure (that menu arrives with the Settings screen in Phase 4).

Rounds are seeded from Appendix K of the plan. Every acceptance box tagged `needs manual verification` is closed here.

## Open items right now

| From | Check | Status |
|---|---|---|
| P0-1 | `./gradlew check` is green on a clean clone on **Windows and Linux** (CI) and Gradle fetches JDK 21 by itself | **not yet verified** — Gradle could not be run where this code was written |
| P0-1 | detekt runs inside `check` with Kotlin 2.0 (workaround for detekt 1.23 is in `build.gradle.kts`) | **not yet verified** |
| P0-2 | Application placeholder window opens (`./gradlew :app:run`) | **not yet verified** |
| P0-7 | Round 0 fixture capture done on your phone | **pending — your turn** |
| P1-5 | One generated snippet per framework you use actually runs (Maestro especially: `id:` with the full `pkg:id/name`) | **not yet verified** — nothing could be run against the real frameworks where this was written |

## Round 0 — Fixture capture (at the very start, about 10 minutes)

Needs: your Android phone, a USB data cable and `adb`. Do step 1 of Round 1 (USB debugging), then run `scripts/capture-fixtures.sh`
(macOS/Linux) or `scripts/capture-fixtures.ps1` (Windows) and follow its prompts to save five different screens (Settings, a Jetpack
Compose app if you have one, a web page in a WebView, a long list, a dialog over a screen). The scripts print a privacy reminder: they
record what is on screen, so use screens without personal data. Hand the output folder over.

## Round 1 — Android (after Phase 4, the first time the real UI exists)

Needs: your Android phone and a USB data cable. *Optional early check 1a, after Phase 2:* press *Capture now* in the temporary shell —
**pass if** it reports a non-zero element count for your phone's current screen and saves a screenshot.

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
