# Troubleshooting

## Windows app inspection

**"Windows inspection isn't available" / the helper self-test fails**
- Press *Retry*, then *Copy details*: it ends with what the helper wrote on stderr and whether it exited.
- Run the helper's own check from a terminal — it needs no UIScope and says what fails:
  `"%LOCALAPPDATA%\UIScope\helper\UIScope.Helper.exe" --selftest`
- The helper (`UIScope.Helper.exe`, staged under the data folder) may be quarantined by security software.
  Restore it or add an exclusion for the UIScope data folder.
- It needs the .NET Framework 4.8 runtime, which every supported Windows 10 (1809+) and 11 install has.
- Re-run the start-up self-test with the *Look again* item in the window picker.

**"The window is minimized"** — a minimized window has nothing to capture; restore it and press `F5`.

**"The tree was cut at the limits"** — a very large window is read for up to 25 s or 10,000 elements and then shown as far as it got.

**"That window runs as administrator"** — Windows stops normal apps from reading elevated windows. Close
UIScope and restart it with *Run as administrator*.

**"The window was closed" / the highlight is stale** — the window you captured is gone or moved; pick it again
or press `F5` to refresh.

**The highlight doesn't line up with the element** — most likely a multi-monitor setup with different DPI.
Check your display scaling is applied per-monitor (Settings → Display); if it is still wrong, please report it
with *Copy diagnostics* output.

**"This app exposes little accessibility information"** — the app you are inspecting publishes little UI
Automation content (common for Chromium/Electron/Qt/Java apps):
- Chromium / Electron (Chrome, Edge, VS Code, Slack): start the app with `--force-renderer-accessibility`.
- Qt: set the environment variable `QT_ACCESSIBILITY=1` before starting it.
- Java / Swing: enable the Java Access Bridge (`c:\Windows\System32\JABSwitch.exe /enable`).

**The pick hotkey does nothing** — it is on by default and can be switched in *Settings → Windows*. If UIScope says another
program already uses `Ctrl+Alt+P`, press *Pick element* instead, free the combination, or set a different `pickHotkey`
(for example `"Ctrl+Shift+F9"`) in `settings.json` while UIScope is closed.

**Two adb versions fighting** — if you already have platform-tools on PATH, point UIScope at that exact
`adb.exe` (Settings → *Browse...*) so only one adb server runs. Symptoms of a fight: devices flap between
`device` and `offline`, or "adb server version doesn't match this client" appears in logs.

## Android inspection

**ADB not found** — Settings → *Download platform-tools* looks up the current release in Google's SDK package list,
downloads the official archive, checks its size and checksum and installs it into the UIScope data folder; or *Browse...* to an existing adb.
If the download reports a network error, check your connection or proxy and try again.

**Google USB driver / OEM driver missing (Windows)** — the phone shows as an unknown device in Device Manager.
Install the OEM USB driver (or Google's driver) for your model; adb then appears.

**"Device is not authorized"** — the phone is waiting for the "Allow USB debugging?" dialog. Unlock it and
accept. If it never asks: revoke USB debugging authorizations in Developer options and reconnect.

**Wireless debugging** — Developer options → Wireless debugging → pair with the code shown on the phone, then
connect. Both ends need the same network; corporate networks with client isolation break it.

**"No permissions" / udev rule (Linux)** — create a udev rule for your vendor id (e.g.
`SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0666", GROUP="plugdev"`), then
`sudo udevadm control --reload-rules` and replug.

**Live refresh shows a stale tree** — Live re-captures on an interval; heavy apps can take a moment. If a
refresh fails, the last capture stays on screen with the reason in a banner.

## Snapshots and export

**"The bundle was rejected" on import** — the `.uiscope` file is from a newer UIScope, damaged, or larger than
100 MB. Re-export it from the version that made it.

**SQLite driver unpacks on first save** — the native library is unpacked into the data folder at first use;
antivirus can slow or block it. The first save retries.

**The storage meter is red / snapshots vanish** — retention (Settings → Snapshots) deletes the oldest unpinned
snapshots past the count or byte cap. Pin what you want to keep.

## General

**UIScope hit an unexpected problem** — the crash dialog's *Copy diagnostics* puts build info and the stack on
the clipboard; paste it into a bug report. *Restart* relaunches the app.

**A second launch does nothing new** — that is the single-instance guard: it brings the first window forward
instead.

## The messages added in 1.0.2

**The phone's screen is shown but there are no elements** (banner: "The elements can't be read right now", "The screen keeps animating", "Another
automation tool is using the device", "Timed out reading the elements"). The screenshot and the element dump are separate requests; the dump
fails on video, games, anything that never goes idle, apps that block accessibility, and while Appium, uiautomator2 or Maestro holds the device.
Pause the animation or stop the other tool and press *Capture now*. The screen itself is never withheld because of this.

**The picture is a single colour.** The app marks its window as secure (FLAG_SECURE) and Android refuses to capture it.

**"Couldn't take a screenshot".** UIScope tries two routes (straight through adb, then through a file on the phone). If both fail, unlock the
phone, keep the screen on and retry; on a Wi-Fi connection a slow link can need more than the 20 s UIScope allows, so try a cable.

**A Windows window did not come to the front** (banner: "Couldn't bring the window to the front"). Windows sometimes stops one program from
taking over another's focus. The capture was still made; click the window yourself or use *Show window*. You can turn the behaviour off in
Settings -> Windows ("Bring a window to the front when I choose it").

**"The window is minimized"** appears only when that setting is off or the window would not restore: click it in the taskbar and capture again.
