# UIScope — Distribution Guide

> Everything you need to build, brand, sign, and publish UIScope across every platform.

---

## Table of Contents

1. [App Identity](#1-app-identity)
2. [Logo & Icon System](#2-logo--icon-system)
3. [Building Installers Locally](#3-building-installers-locally)
4. [GitHub Actions CI/CD](#4-github-actions-cicd)
5. [Windows — EXE, MSI, MSIX & Microsoft Store](#5-windows--exe-msi-msix--microsoft-store)
6. [macOS — DMG, PKG & Mac App Store](#6-macos--dmg-pkg--mac-app-store)
7. [Linux — DEB & AppImage](#7-linux--deb--appimage)
8. [Versioning](#8-versioning)
9. [Secrets & Signing Reference](#9-secrets--signing-reference)
10. [Store Listing Copy](#10-store-listing-copy)

---

## 1. App Identity

| Field | Value |
|-------|-------|
| **App name** | UIScope |
| **Tagline** | See what your UI is made of. |
| **Developer / Publisher** | TITANICBHAI |
| **Bundle / Package ID** | `com.titanicbhai.uiscope` |
| **Windows Package Identity** | `TITANICBHAI.UIScope` |
| **Upgrade UUID (Windows MSI)** | `C7B5A9D2-3F1E-4A8B-9C6D-0E2F7B4A5C3D` — **never change this after first release** |
| **License** | MIT |
| **Support email** | uiscope@titanicbhai.dev |
| **Website / docs URL** | https://github.com/TITANICBHAI/UIScope-jvm |
| **Category** | Developer Tools |
| **Min OS — Windows** | Windows 10 1809 (build 17763) or later |
| **Min OS — macOS** | macOS 12 Monterey |
| **Min OS — Linux** | Ubuntu 22.04 / glibc 2.35 |

---

## 2. Logo & Icon System

### 2.1 Master artwork requirements

Start with a single **1024 × 1024 px SVG or PNG** source.

| Design principle | Detail |
|-----------------|--------|
| Shape | Square with rounded corners (radius ≈ 18% of width) — macOS and Windows both clip to this |
| Safe area | Keep all key marks within the inner **80%** (no bleed to edge) |
| Background | Solid dark `#0D1117` or transparent; avoid gradients near the edge |
| Colour palette | Primary `#4F8EF7` (blue), accent `#E53935` (inspection red), surface `#1E1E2E` |
| Style | Flat / semi-flat; a magnifying glass over a bracket `{ }` or phone wireframe is on-brand |
| Dark-mode only | All platforms display the icon on dark backgrounds in task bars / menus — optimise for dark |

### 2.2 Files to create and where to put them

```
uiscope/app/src/main/resources/
  icon.ico      Windows — multi-size ICO (16, 24, 32, 48, 64, 128, 256 px)
  icon.icns     macOS  — Apple Icon Image (generated from iconset)
  icon.png      Linux  — 512×512 or 1024×1024 px PNG

uiscope/app/src/main/msix/assets/
  StoreLogo.png          50×50 px    Microsoft Store listing thumbnail
  Square44x44Logo.png    44×44 px    Taskbar / Start small
  Square71x71Logo.png    71×71 px    Start menu medium (optional but recommended)
  Square150x150Logo.png  150×150 px  Start menu medium tile  ← required
  Square310x310Logo.png  310×310 px  Start menu large tile
  Wide310x150Logo.png    310×150 px  Start menu wide tile
  SplashScreen.png       620×300 px  Cold-start splash  (dark bg #0D1117)
  BadgeLogo.png          24×24 px    Lock-screen badge (white monochrome)
```

### 2.3 Generating ICO (Windows)

**Option A — ImageMagick (CLI, recommended):**
```bash
magick icon.png \
  \( -clone 0 -resize 256x256 \) \
  \( -clone 0 -resize 128x128 \) \
  \( -clone 0 -resize 64x64  \) \
  \( -clone 0 -resize 48x48  \) \
  \( -clone 0 -resize 32x32  \) \
  \( -clone 0 -resize 24x24  \) \
  \( -clone 0 -resize 16x16  \) \
  -delete 0 icon.ico
```

**Option B — Online:** https://convertico.com/

### 2.4 Generating ICNS (macOS — run on macOS)

```bash
mkdir UIScope.iconset
sips -z 16   16   icon.png --out UIScope.iconset/icon_16x16.png
sips -z 32   32   icon.png --out UIScope.iconset/icon_16x16@2x.png
sips -z 32   32   icon.png --out UIScope.iconset/icon_32x32.png
sips -z 64   64   icon.png --out UIScope.iconset/icon_32x32@2x.png
sips -z 128  128  icon.png --out UIScope.iconset/icon_128x128.png
sips -z 256  256  icon.png --out UIScope.iconset/icon_128x128@2x.png
sips -z 256  256  icon.png --out UIScope.iconset/icon_256x256.png
sips -z 512  512  icon.png --out UIScope.iconset/icon_256x256@2x.png
sips -z 512  512  icon.png --out UIScope.iconset/icon_512x512.png
sips -z 1024 1024 icon.png --out UIScope.iconset/icon_512x512@2x.png
iconutil -c icns UIScope.iconset -o icon.icns
cp icon.icns uiscope/app/src/main/resources/icon.icns
```

---

## 3. Building Installers Locally

### Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| JDK | 21 (Temurin recommended) | https://adoptium.net |
| Gradle | auto via `gradlew` | included in repo |
| WiX Toolset *(Windows MSI only)* | 3.11+ | https://wixtoolset.org — auto-downloaded by Compose Desktop on first run |
| Xcode CLI tools *(macOS only)* | latest | `xcode-select --install` |
| `fakeroot` + `binutils` *(Linux DEB)* | latest | `sudo apt install fakeroot binutils` |
| libFUSE *(AppImage)* | 2.x | `sudo apt install libfuse2` |

### Build commands

```bash
cd uiscope

# Windows — run from a Windows machine or GitHub Actions
./gradlew.bat :app:packageExe       # → app/build/compose/binaries/main/exe/
./gradlew.bat :app:packageMsi       # → app/build/compose/binaries/main/msi/

# macOS
./gradlew :app:packageDmg           # → app/build/compose/binaries/main/dmg/
./gradlew :app:packagePkg           # → app/build/compose/binaries/main/pkg/

# Linux
./gradlew :app:packageDeb           # → app/build/compose/binaries/main/deb/
./gradlew :app:packageAppImage      # → app/build/compose/binaries/main/app/

# All formats for current OS (dev shortcut)
./gradlew :app:packageDistributionForCurrentOS
```

### Override version at build time

```bash
APP_VERSION=1.2.3 ./gradlew :app:packageMsi
```

---

## 4. GitHub Actions CI/CD

The workflow at `.github/workflows/release.yml` runs automatically on any `v*.*.*` tag push.

### Publishing a new release

```bash
# 1. Bump version.txt (or pass via tag)
echo "1.2.0" > uiscope/version.txt
git add uiscope/version.txt
git commit -m "chore: bump version to 1.2.0"

# 2. Tag and push — this triggers the workflow
git tag v1.2.0
git push origin main --tags
```

The workflow produces **7 installer files** and publishes them to a GitHub Release automatically.

### Workflow jobs overview

```
build-windows  ──┐
build-macos    ──┤──► release  (creates GitHub Release + uploads all assets)
build-linux    ──┘
```

| Job | Runner | Outputs |
|-----|--------|---------|
| `build-windows` | `windows-latest` | `.exe`, `.msi`, `.msix` |
| `build-macos` | `macos-14` (Apple Silicon) | `.dmg`, `.pkg` |
| `build-linux` | `ubuntu-22.04` | `.deb`, `.AppImage` |
| `release` | `ubuntu-latest` | GitHub Release with all files attached |

### Required GitHub repository secrets

> Settings → Secrets and variables → Actions → New repository secret

| Secret name | When needed | Description |
|-------------|------------|-------------|
| `GITHUB_TOKEN` | Always — auto-provided | Creates GitHub Release |
| `APPLE_SIGNING_IDENTITY` | macOS notarization (optional) | `Developer ID Application: Your Name (TEAMID)` |
| `APPLE_SIGNING_CERT_P12_BASE64` | macOS notarization | `base64 -i cert.p12` |
| `APPLE_SIGNING_CERT_PASSWORD` | macOS notarization | P12 export password |
| `APPLE_ID` | macOS notarization | Apple Developer email |
| `APPLE_APP_PASSWORD` | macOS notarization | App-specific password from appleid.apple.com |
| `APPLE_TEAM_ID` | macOS notarization | 10-char Team ID from developer.apple.com |

---

## 5. Windows — EXE, MSI, MSIX & Microsoft Store

### 5.1 EXE (NSIS installer)

- **File:** `UIScope-Setup-X.Y.Z.exe`
- **What it is:** NSIS self-extracting installer — the friendliest option for most users
- **Silent install:** `UIScope-Setup.exe /S /D=C:\UIScope`
- **No admin required** (configured as per-user install in `build.gradle.kts`)

### 5.2 MSI (Windows Installer)

- **File:** `UIScope-X.Y.Z.msi`
- **What it is:** Microsoft Installer package — preferred for enterprise, Group Policy, MDM (Intune), and SCCM deployment
- **Silent install:** `msiexec /i UIScope.msi /qn ALLUSERS=0`
- **Group Policy deploy:** MSI can be pushed via AD Group Policy for organization-wide deployment
- **Upgrade:** The `upgradeUuid` in `build.gradle.kts` ensures silent in-place upgrades work; **never change that UUID**

### 5.3 MSIX (modern package — Microsoft Store compatible)

- **File:** `UIScope-X.Y.Z-x64.msix`
- **Manifest:** `uiscope/app/src/main/msix/AppxManifest.xml`
- **Assets:** `uiscope/app/src/main/msix/assets/` (see §2.2)

**The MSIX is built by the CI workflow** using `makeappx.exe` (pre-installed on every `windows-latest` runner via the Windows SDK).

#### Sideloading MSIX (testing, no Store)

```powershell
# User must enable "Developer Mode" or "Sideloading" in Windows Settings
Add-AppxPackage -Path UIScope-1.0.0-x64.msix
```

For testing with a self-signed cert:
```powershell
# Trust the cert first (one-time)
Import-Certificate -FilePath uiscope-selfsign.cer -CertStoreLocation Cert:\LocalMachine\Root
# Then install
Add-AppxPackage -Path UIScope-1.0.0-x64.msix
```

#### Submitting to the Microsoft Store

1. **Register** at https://partner.microsoft.com/dashboard (one-time, $19 fee)
2. **Create a new app** → reserve the name "UIScope"
3. **Get your Publisher identity** from Partner Center → `Account settings → Organization profile`
4. **Update `AppxManifest.xml`**:
   ```xml
   <Identity
     Name="TITANICBHAI.UIScope"
     Publisher="CN=Your Name, O=Your Org, ..."   <!-- exact string from Partner Center -->
     Version="1.0.0.0" />
   ```
5. **Package**: run `makeappx pack` (CI does this automatically)
6. **Sign** for Store submission — Microsoft signs during ingestion, so you only need a Store-associated cert OR you can upload an unsigned package
7. **Submit** in Partner Center → Submissions → Upload your `.msix`
8. **Store listing** — fill in copy from §10 below

---

## 6. macOS — DMG, PKG & Mac App Store

### 6.1 DMG (disk image — recommended)

- **File:** `UIScope-X.Y.Z.dmg`
- **What it is:** Drag-to-Applications disk image — the standard macOS distribution format
- **To distribute without Gatekeeper warnings:** code-sign + notarize (see §9)

### 6.2 PKG (flat package)

- **File:** `UIScope-X.Y.Z.pkg`
- **What it is:** macOS flat package — works with `mdm`, Jamf, Munki, and enterprise deployment
- **Silent install:** `sudo installer -pkg UIScope.pkg -target /`

### 6.3 Apple Notarization (required for Gatekeeper)

Without notarization, macOS 13+ shows a warning blocking the app.

The CI workflow notarizes automatically if the Apple secrets are set (see §4 secrets table).

To notarize manually:
```bash
xcrun notarytool submit UIScope.dmg \
  --apple-id "you@example.com" \
  --team-id "ABCDE12345" \
  --password "@keychain:AC_PASSWORD" \
  --wait
xcrun stapler staple UIScope.dmg
```

### 6.4 Mac App Store

Compose Desktop supports MAS distribution — set `appStore = true` in `build.gradle.kts` and add entitlements. This is separate from standard distribution and requires:
- An App Store provisioning profile
- Specific entitlements (no arbitrary code execution, sandbox)
- Review by Apple (~1–7 days)

---

## 7. Linux — DEB & AppImage

### 7.1 DEB (Debian / Ubuntu)

- **File:** `uiscope_X.Y.Z-1_amd64.deb`
- **Install:** `sudo dpkg -i uiscope_*.deb`
- **Uninstall:** `sudo apt remove uiscope`
- **Repo hosting:** Can be hosted on GitHub Releases and added as an `apt` source via `apt-get install` with a PPA or custom apt repo

### 7.2 AppImage (universal Linux)

- **File:** `UIScope-X.Y.Z-x86_64.AppImage`
- **Run:** `chmod +x UIScope-*.AppImage && ./UIScope-*.AppImage`
- **Integration:** Install `appimaged` or `AppImageLauncher` for desktop file auto-registration
- **No root required** — fully portable, runs on any Linux with glibc ≥ 2.35

### 7.3 Snap / Flatpak (future)

Not built by the current CI workflow but straightforward to add. Flatpak requires a `com.titanicbhai.UIScope.yml` manifest. Snap requires a `snapcraft.yaml`. Both wrap the AppImage or the raw JVM distribution.

---

## 8. Versioning

UIScope uses **Semantic Versioning** (`MAJOR.MINOR.PATCH`).

```
version.txt    ← single source of truth for local builds
               (CI overrides with tag name stripped of "v")
```

| Version component | Meaning |
|-------------------|---------|
| `MAJOR` | Breaking change to session format or major UI overhaul |
| `MINOR` | New feature phase complete (Phase 2 → 2.0, Phase 3 → 3.0, etc.) |
| `PATCH` | Bug fixes, dependency updates |

**Windows MSI version** must be `X.Y.Z.0` (four parts). The CI workflow appends `.0` automatically in `AppxManifest.xml`.

### Planned release milestones

| Version | Milestone |
|---------|-----------|
| `1.0.0` | Phase 1 — project skeleton ✅ |
| `1.1.0` | Phase 2 — Android mode live ✅ |
| `1.2.0` | Phase 3 — PC mode live |
| `2.0.0` | Phase 4 — Polish, diff mode, watch mode, store submission |

---

## 9. Secrets & Signing Reference

### Windows code signing (optional but recommended for enterprise)

To avoid Windows SmartScreen warnings on EXE/MSI, buy an **OV or EV Code Signing Certificate**:
- Sectigo, DigiCert, GlobalSign — ~$100–400/year
- EV certificates (hardware token) remove SmartScreen warnings immediately; OV certificates reduce them after enough installs

```powershell
# Sign EXE and MSI with signtool.exe
signtool sign /fd sha256 /tr http://timestamp.sectigo.com /td sha256 `
  /f cert.pfx /p "$env:CERT_PASSWORD" UIScope-Setup.exe UIScope.msi
```

### macOS signing & notarization checklist

- [ ] Apple Developer Program membership ($99/year)
- [ ] Create "Developer ID Application" certificate in Xcode → Accounts → Manage Certificates
- [ ] Export as `.p12` and add to GitHub secrets (`APPLE_SIGNING_CERT_P12_BASE64`)
- [ ] Create an app-specific password at appleid.apple.com → Security
- [ ] Set all 4 Apple secrets in GitHub (see §4)

### MSIX signing options

| Option | Cost | Notes |
|--------|------|-------|
| Microsoft Store submission | Free (one-time $19 registration) | Microsoft signs during ingestion |
| OV code signing cert | ~$100/year | Works for sideloading |
| Self-signed (testing only) | Free | Only works on developer machines with cert trusted |

---

## 10. Store Listing Copy

Use this copy for any app store submission (Microsoft Store, etc.).

### Short description (≤ 100 characters)
```
See what your UI is made of. Live inspector for Android and PC app UI trees.
```

### Long description (Microsoft Store — ≤ 10 000 characters)
```
UIScope is a desktop developer tool that lets you inspect the live UI element tree
of any connected Android device or — coming soon — any Windows app, all without
modifying the target app or adding a single line of instrumentation code.

── ANDROID MODE ──────────────────────────────────────────────────────────────

Connect any Android device via USB or wireless ADB, tap a device row, and UIScope
instantly:

  • Captures a live screenshot and displays it in a zoomable, pannable canvas
  • Dumps the full UIAutomator accessibility tree (even for 1000+ node screens)
  • Shows every node property: resource ID, text, content description, class,
    package, bounds, enabled, clickable, scrollable, focused, checked, depth
  • Highlights the selected element's bounding box on the screenshot in real time
  • Lets you click directly on the screenshot to select the node under your cursor

Click any node in the tree → see it highlighted on the screenshot.
Click anywhere on the screenshot → the matching node is selected in the tree.

── CODE GENERATION ────────────────────────────────────────────────────────────

UIScope generates ready-to-paste automation code for 6 frameworks:
  · Python uiautomator2       · Kotlin UIAutomator2
  · Appium Java               · Appium Python
  · Maestro YAML              · XPath

When a selector is likely to break (obfuscated resource ID, no ID at all),
UIScope shows a yellow "Fragile selector" warning badge and explains why.

── BREADCRUMB & XPATH ─────────────────────────────────────────────────────────

A clickable breadcrumb bar shows the full path from root to the selected element.
Tap any crumb to jump up the tree. One-click "Copy as XPath" puts a working
XPath expression on your clipboard instantly.

── EXPORT ─────────────────────────────────────────────────────────────────────

Export the full tree or just the selected node as:
  · JSON  — machine-readable, for scripts and diffing
  · XML   — UIAutomator-compatible, drop it back into other tools
  · Outline — human-readable indented tree, paste it into your bug report

── WIRELESS ADB ───────────────────────────────────────────────────────────────

No cable? No problem.
  · Pre-Android 11: one-tap IP connect after `adb tcpip 5555`
  · Android 11+:  enter the 6-digit pairing code from Wireless Debugging settings

── SESSIONS ───────────────────────────────────────────────────────────────────

Every successful inspection is saved locally to ~/.uiscope — screenshot + full tree
JSON — so you can review and compare past sessions without reconnecting a device.

── PRIVACY ────────────────────────────────────────────────────────────────────

UIScope is 100% offline. No analytics, no telemetry, no cloud sync. All session
data stays on your machine at ~/.uiscope/.

── COMING SOON ────────────────────────────────────────────────────────────────

  · PC mode — inspect Windows, macOS, and Linux app accessibility trees
  · Diff mode — compare two snapshots and highlight what changed
  · Watch mode — alert when a specific element appears or disappears
  · Session browser — scroll through past inspections
```

### Keywords / tags

```
developer tools, UI inspector, Android, ADB, UIAutomator, accessibility, 
automation, uiautomator2, Appium, Maestro, testing, element tree, xpath,
screen inspector, UI hierarchy, Android debug, UI automation
```

### Category
`Developer Tools` → `Testing Tools`

### Age rating
`Everyone` — no user-generated content, no ads, no in-app purchases

### Screenshots needed (Microsoft Store)
- 1366×768 or larger, PNG or JPEG, minimum 1 screenshot, maximum 10
- Suggested shots:
  1. Full inspector view with a real Android device tree + screenshot
  2. Code generator panel open showing Python uiautomator2 code
  3. Breadcrumb bar + XPath copy
  4. Device selection screen
  5. Export dialog
  6. Launcher screen (mode picker)

---

*Last updated: June 2026 — UIScope Phase 2 release*
