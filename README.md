# UIScope

**UIScope: Desktop & Android UI Inspector** — by TBTechs (developer: TITANICBHAI). MIT licensed.

UIScope captures the UI of a Windows application window (Windows 10 1809+) or an Android device: the element
tree, a screenshot with live element highlighting, per-element properties, selector suggestions for ten test
frameworks, and snapshots you can re-open offline or export.

> **Status: 1.0.0.** The app was rebuilt from the ground up; the previous implementation is gone
> (`git log` keeps it). Microsoft Store submission and the first tagged release are the remaining owner steps.

## Install

- **Microsoft Store** (planned — the listing is the owner's step, P8-5): Microsoft signs the package.
- **GitHub releases** (from the first `v*` tag on): `UIScope-<version>-x64.msi` / `.exe` (per-user install, no
  admin), an unsigned `.msix` for sideloading, and `SHA256SUMS`. These downloads are **unsigned by design**;
  Windows SmartScreen may warn on first run — *More info → Run anyway*. Verify the checksum you downloaded
  against `SHA256SUMS`.
- **From source**: JDK 21, `./gradlew run`.

macOS and Linux (Android inspection only) — **planned for 1.1**.

## 60-second quick start

**Windows app inspection** (Windows only):

1. Launch UIScope. The helper self-test runs; if it says unavailable, the Windows section of
   [docs/troubleshooting.md](docs/troubleshooting.md) covers the causes.
2. Pick a window from *Choose window...*, or press **Pick element** (or `Ctrl+Alt+P` once enabled) and click any
   element in any app — the pick click is swallowed, never delivered to the other app.
3. Explore: tree | screenshot canvas | Selector/Properties/Path. `Ctrl+S` saves a snapshot; *Export...* writes
   JSON/XML/Outline/PNG (redaction included).

**Android inspection**:

1. Enable USB debugging on the phone. If `adb` is missing, Settings → *Download platform-tools* installs it.
2. Pick the device, press **Capture now** (or `F5` to refresh).
3. Everything else works the same way as the Windows target.

**Snapshots**: the Snapshots screen lists, searches and pins them; *Open in Inspector* replays one offline; a
snapshot exports as a `.uiscope` bundle and imports back on any machine.

## Supported OS

- Windows 10 (1809+) and 11: both targets, full features.
- macOS/Linux: Android inspection only, planned for 1.1 (not packaged for 1.0).
- For Android inspection you need a phone with USB debugging; Windows app inspection needs a desktop session.

## FAQ

- **"That window runs as administrator"** — Windows stops normal apps from reading elevated windows. Close
  UIScope, right-click it, *Run as administrator*.
- **"This app exposes little accessibility information"** — Chromium/Electron: start the app with
  `--force-renderer-accessibility`. Qt: `QT_ACCESSIBILITY=1`. Java: enable the Java Access Bridge. Details in
  [docs/troubleshooting.md](docs/troubleshooting.md).
- **Where is my data?** Everything stays in `%LOCALAPPDATA%\UIScope`. **Privacy**: [PRIVACY.md](PRIVACY.md) —
  no telemetry, no accounts, exactly two optional network actions.
- **Is my serial number sent anywhere?** No. Full serials are never displayed or logged; the UI shows the last
  four characters only.
- **Hotkey taken?** The pick hotkey (`Ctrl+Alt+P`) can be disabled in Settings; UIScope reports it when another
  app owns it.

## Build from source

```bash
./gradlew check        # compile, all tests, detekt, the guardrails
./gradlew :app:run     # launch the app
./gradlew :app:packageMsi :app:packageExe :app:createDistributable   # installers + MSIX app image
```

On Windows use `gradlew.bat` instead of `./gradlew`. The Windows helper builds automatically with the MSBuild
that ships with Windows — no .NET SDK, no NuGet.

## Layout

| Path | Purpose |
|---|---|
| `core/` | Pure Kotlin: model, errors, identity, exports, the selector engine. No UI, no process or network code. |
| `engine/` | Talks to the outside world: adb, the Windows helper client, processes, HTTP, files, logs. |
| `ui/` | Compose screens and components (Inspect, Snapshots, Settings, First run, Help). |
| `app/` | Entry point, crash handling, packaging, `app.properties` (identity and links). |
| `native/` | The Windows helper (C#, .NET Framework 4.8, built by the MSBuild on every Windows machine). |
| `packaging/` | MSIX manifest template; `scripts/build-msix.ps1` builds the package. |
| `branding/` | Icons and tiles (source of the ICO/ICNS and the MSIX assets). |
| `scripts/` | Developer scripts. |
| `docs/` | Progress, manual QA, plan process, guardrails, network inventory, security checklist, perf. |

## License

MIT — see [LICENSE](LICENSE). Bundled third-party components are listed in
[app/src/main/resources/THIRD_PARTY_NOTICES.md](app/src/main/resources/THIRD_PARTY_NOTICES.md) and viewable in
the app under Help → About.
