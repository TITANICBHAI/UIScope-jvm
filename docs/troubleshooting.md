# Troubleshooting

## Windows app inspection

**"Windows inspection isn't available" / the helper self-test fails**
- The helper (`UIScope.Helper.exe`, staged next to the data folder) may be quarantined by security software.
  Restore it or add an exclusion for the UIScope data folder.
- It needs the .NET Framework 4.8 runtime, which every supported Windows 10 (1809+) and 11 install has.
- Re-run the self-test with the *Look again* item in the window picker.

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

**The pick hotkey does nothing** — enable it in Settings; if it reports *that shortcut is taken*, another app
owns `Ctrl+Alt+P`. Press *Pick element* instead, or free the combination.

**Two adb versions fighting** — if you already have platform-tools on PATH, point UIScope at that exact
`adb.exe` (Settings → *Browse...*) so only one adb server runs. Symptoms of a fight: devices flap between
`device` and `offline`, or "adb server version doesn't match this client" appears in logs.

## Android inspection

**ADB not found** — Settings → *Download platform-tools* installs a verified copy into the UIScope data folder,
or *Browse...* to an existing one.

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
