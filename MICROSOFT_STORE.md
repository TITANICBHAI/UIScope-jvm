# UIScope — Microsoft Store Publishing Guide

> **App:** UIScope: Desktop and Android UI Inspector  
> **Store ID:** `9MWH310XHGQ5`  
> **Store URL:** https://apps.microsoft.com/detail/9MWH310XHGQ5  
> **Publisher:** TBTechs  
> **Package Name:** `TBTechs.UIScopeDesktopAndroidUIInspector`  
> **Category:** Developer Tools → Testing Tools  
> **Price:** Free  
> **Last updated:** June 2026

---

## Table of Contents

1. [App Identity & Store Metadata](#1-app-identity--store-metadata)
2. [SEO-Optimised Store Listing Copy](#2-seo-optimised-store-listing-copy)
3. [Keywords & Search Tags](#3-keywords--search-tags)
4. [Age Rating & Content Declarations](#4-age-rating--content-declarations)
5. [Screenshot & Graphic Asset Requirements](#5-screenshot--graphic-asset-requirements)
6. [MSIX Package Details](#6-msix-package-details)
7. [Building the MSIX](#7-building-the-msix)
8. [Submitting to Partner Center — Step-by-Step](#8-submitting-to-partner-center--step-by-step)
9. [AppxManifest.xml Reference](#9-appxmanifestxml-reference)
10. [Secrets & CI Variables](#10-secrets--ci-variables)
11. [Post-Publish Checklist](#11-post-publish-checklist)

---

## 1. App Identity & Store Metadata

| Field | Value |
|-------|-------|
| **Display name** | UIScope: Desktop and Android UI Inspector |
| **Short name** (Start menu) | UIScope |
| **Store product ID** | `9MWH310XHGQ5` |
| **Store URL** | https://apps.microsoft.com/detail/9MWH310XHGQ5 |
| **Package / Identity name** | `TBTechs.UIScopeDesktopAndroidUIInspector` |
| **Publisher CN** | `CN=E08824C8-6F22-4DC2-8025-DD8C707E2BE9` |
| **Publisher display name** | TBTechs |
| **Developer / Author** | TITANICBHAI |
| **Bundle / Java package ID** | `com.titanicbhai.uiscope` |
| **Upgrade UUID (MSI)** | `C7B5A9D2-3F1E-4A8B-9C6D-0E2F7B4A5C3D` — **never change** |
| **Version (current)** | 1.0.0 (MSIX version: `1.0.0.0`) |
| **License** | MIT — open source |
| **Support / contact** | uiscope@titanicbhai.dev |
| **Website** | https://titanicbhai.github.io/UIScope-jvm/ |
| **GitHub repo** | https://github.com/TITANICBHAI/UIScope-jvm |
| **Release notes URL** | https://github.com/TITANICBHAI/UIScope-jvm/releases |
| **Category** | Developer Tools |
| **Sub-category** | Testing & QA |
| **Min OS** | Windows 10 version 1809 (build 17763) |
| **Max OS tested** | Windows 11 (build 22621) |
| **Architecture** | x64 |
| **Price** | Free (no in-app purchases, no ads) |
| **Age rating** | Everyone |

---

## 2. SEO-Optimised Store Listing Copy

> **ASO strategy:** lead every field with the highest-intent keyword phrase,
> front-load benefits in the long description, name specific competitor tools
> developers already search for, and close with trust + privacy signals.

### App name (≤ 256 characters)

```
UIScope: Android & PC UI Inspector — ADB, Element Tree, XPath, Code Gen
```

> Keyword breakdown: "Android UI Inspector" (primary, high commercial intent) +
> "PC UI Inspector" (Windows automation audience) + "ADB" (differentiating) +
> "Element Tree" (technical query) + "XPath" (power-user intent) + "Code Gen"
> (conversion signal — shows immediate utility).

### Short description (≤ 100 characters — the single most-read line in Store search results)

```
Inspect any Android or Windows app UI live. XPath, code gen, no root needed.
```

> 77 characters. Leads with the primary use case ("inspect"), names both
> platforms, calls out the two highest-value features (XPath, code gen),
> and closes with the #1 Android inspector objection ("no root").

### Long description (Microsoft Store — ≤ 10 000 characters)

```
Finding the right element selector is the hardest part of UI test automation.
UIScope solves it — instantly.

Connect your Android device or hover over any Windows app, and UIScope shows
you the full live accessibility tree, every node property, and ready-to-run
automation code for uiautomator2, Appium, Maestro, pywinauto and more.
No Appium server. No Node.js. No configuration. No account. No root.

It is the Appium Inspector alternative that also works on your Windows desktop.

────────────────────────────────────────────────────────
ANDROID INSPECTOR — Live element tree via ADB in seconds
────────────────────────────────────────────────────────

Connect any Android device via USB or wireless ADB. UIScope uses the built-in
UIAutomator accessibility API — no on-device agent, no app modification, no
root access required.

  • Full UIAutomator node tree — handles screens with 1 000+ nodes smoothly
  • Live screenshot in a zoomable, pannable canvas with pixel-accurate highlights
  • Bidirectional selection: click a tree node → highlights on the screenshot
    instantly; tap the canvas → the exact tree node is selected automatically
  • Every node property: resourceId, text, contentDescription, className,
    packageName, bounds, enabled, clickable, scrollable, focused, checked, depth
  • Wireless ADB: legacy IP connect (Android ≤ 10) and six-digit pairing code
    (Android 11+) — connect without a USB cable
  • Works with any Android app: native, Flutter, React Native, Xamarin, Cordova

The only Android UI inspector that runs as a native desktop app — no browser,
no server process, no driver setup.

────────────────────────────────────────────────────────
PC INSPECTOR — Inspect any Windows app via UIAutomation
────────────────────────────────────────────────────────

Hover your cursor over any window, press Alt+Shift+P to lock pick mode, and
UIScope captures the full Windows UIAutomation accessibility tree for that
process.

  • Every element: Name, AutomationId, ClassName, ControlType, BoundingRect,
    IsEnabled, IsKeyboardFocusable, NativeWindowHandle
  • Live hover-highlight overlay drawn directly on the desktop
  • One-click code generation for AutoHotKey v2, Python pywinauto, C# FlaUI,
    and PowerShell UI Automation
  • Full tree virtualisation — trees with 5 000+ nodes scroll at 60 fps
  • Works with any app: Win32, WPF, WinForms, UWP, Electron, Qt, Java and more
  • Same inspector also works on Linux (AT-SPI2) and macOS (Accessibility API)

────────────────────────────────────────────────────────
CODE GENERATION — Copy. Paste. Automate.
────────────────────────────────────────────────────────

UIScope generates ready-to-run automation code for eight frameworks:

  Android apps: Python uiautomator2 · Kotlin UIAutomator2 · Appium Java
                Appium Python · Maestro YAML · XPath
  PC apps:      AutoHotKey v2 · Python pywinauto · C# FlaUI · PowerShell

Every generated selector includes a stability score. When a selector is
fragile — obfuscated resource ID, raw coordinates, non-unique class match —
UIScope shows an amber "Fragile Selector" badge and explains exactly why it
may break between app versions, before you ever paste it into a test suite.

Stop guessing which selector will survive a UI update. UIScope tells you.

────────────────────────────────────────────────────────
XPATH — One-click copy with breadcrumb navigation
────────────────────────────────────────────────────────

  • Absolute XPath: full document path from root to the selected element
  • Relative XPath: shortest stable path using unique attributes
  • Attribute-based XPath: targets by resource-id, text, content-desc
  • Stability score per strategy — pick the selector least likely to break
  • Interactive breadcrumb bar shows the element's full position in the tree
  • Click any crumb to navigate up the ancestor chain instantly

────────────────────────────────────────────────────────
DIFF MODE — See exactly what changed between two builds
────────────────────────────────────────────────────────

Pick any two saved inspection sessions and UIScope instantly produces a
three-column diff:

  • Added elements   (green)  — new nodes that appeared
  • Removed elements (red)    — nodes that were deleted
  • Changed elements (amber)  — nodes with property-level changes,
                                 showing before/after for each changed field

Perfect for QA sign-off: verify that a UI change did exactly what was
intended and that no unexpected regressions were introduced.
Works across Android builds and across Windows app versions.

────────────────────────────────────────────────────────
WATCH MODE — Automated condition monitoring
────────────────────────────────────────────────────────

Define a rule — element appears, element disappears, text matches pattern,
text changes — and UIScope polls the connected device continuously.
A Windows desktop notification fires the moment the condition is met.

Use Watch Mode for:
  • Flaky-test investigation (catch transient states you cannot reproduce)
  • Timing analysis (measure how long a loading state lasts)
  • Automated QA gate workflows (wait for a condition before proceeding)
  • Monitoring production devices on a test rack

────────────────────────────────────────────────────────
EXPORT — Interoperable with your existing toolchain
────────────────────────────────────────────────────────

Export the full element tree or any selected subtree as:

  • JSON    — machine-readable, script-friendly, diffable with standard tools
  • XML     — UIAutomator-compatible format; drop directly into Appium or
              UIAutomator2 test suites
  • Outline — human-readable indented tree; paste directly into a bug report,
              Jira ticket, or design review document

────────────────────────────────────────────────────────
SESSION HISTORY & BOOKMARKS
────────────────────────────────────────────────────────

Every capture is saved automatically to a local SQLite database
(~/.uiscope/uiscope.db) — full screenshot + complete element tree:

  • Browse, search, and filter all past inspections in the History screen
  • Re-open any session without reconnecting the device
  • Bookmark important nodes and compare them across sessions
  • Use Ctrl+F to fuzzy-search any property in the live tree

────────────────────────────────────────────────────────
PRIVACY — 100% offline. GDPR-safe by design.
────────────────────────────────────────────────────────

UIScope was built for developers who handle sensitive app data. It never
sends any information anywhere:

  • Zero analytics · Zero telemetry · Zero crash reporting
  • No account required · No sign-in · No email
  • No network requests except one optional, silent GitHub Releases version
    check at startup (disable in Settings → Updates)
  • All session data, screenshots, and element trees stay exclusively on
    your local machine — no cloud sync, no third-party storage

Fully compliant with GDPR, CCPA, and enterprise security policies.

────────────────────────────────────────────────────────
MULTI-WINDOW & PRODUCTIVITY
────────────────────────────────────────────────────────

  • File → New Window (Ctrl+Shift+N) — open two inspectors side-by-side,
    one for Android and one for a PC app simultaneously
  • System tray icon with quick-launch menu for instant access
  • Global hotkeys: Alt+Shift+P (pick lock), Alt+Shift+R (refresh),
    Ctrl+F (search), Ctrl+Shift+N (new window)
  • Dark theme by default; Light theme available per-session in View menu
  • Native menu bar on every window — keyboard accessible throughout

────────────────────────────────────────────────────────
WHY DEVELOPERS CHOOSE UISCOPE
────────────────────────────────────────────────────────

vs. Appium Inspector:
  UIScope requires zero server setup. No Node.js, no Appium server, no
  driver configuration. Open the app, connect your device, start inspecting.
  UIScope also inspects Windows, macOS, and Linux desktop apps — something
  Appium Inspector cannot do.

vs. Android Studio Layout Inspector:
  UIScope works with any app — not just apps you have the source code for.
  No Android Studio, no Gradle project, no build system required.
  Inspect competitor apps, production builds, and third-party SDKs.

vs. Accessibility Insights (Windows):
  UIScope adds Android inspection, code generation, diff mode, watch mode,
  session history, and XPath stability scoring on top of element tree viewing.

────────────────────────────────────────────────────────
OPEN SOURCE · MIT LICENSE
────────────────────────────────────────────────────────

UIScope is fully open source under the MIT licence.
Source code: https://github.com/TITANICBHAI/UIScope-jvm
Built with Kotlin 2.0 + Jetpack Compose Multiplatform Desktop.
Contributions, bug reports, and feature requests are welcome.
```

---

## 3. Keywords & Search Tags

Microsoft Store allows up to **7 custom search terms** (each ≤ 40 characters).  
Selected for maximum search-volume coverage with minimum overlap:

| # | Tag (≤ 40 chars) | Chars | Rationale |
|---|-----------------|-------|-----------|
| 1 | `Android UI inspector ADB` | 26 | Highest-intent primary keyword |
| 2 | `appium inspector alternative` | 29 | Comparison search — very high volume |
| 3 | `uiautomator2 code generator` | 28 | Framework-specific, unique to UIScope |
| 4 | `Windows UIAutomation viewer` | 28 | PC inspector audience |
| 5 | `android layout inspector free` | 30 | Competes with Google's deprecated tool |
| 6 | `XPath generator UI testing` | 27 | Power-user / QA engineer intent |
| 7 | `UI element tree debugger` | 25 | Catch-all for general inspector queries |

> **Rotation strategy:** swap tag 5 (`android layout inspector free`) with
> `accessibility tree inspector` after first 90 days to capture the broader
> accessibility-tools audience once the app has initial reviews.

### Full keyword list for the Store listing description body

Include these terms naturally in the long description (already embedded above).  
Listed here for reference and completeness:

```
Android UI inspector, ADB inspector, UIAutomation, accessibility tree viewer,
uiautomator2, uiautomator inspector, Appium, Appium inspector, Appium locator,
Maestro, pywinauto, FlaUI, AutoHotKey, C# UI automation, PowerShell automation,
XPath generator, element selector, resource ID, contentDescription, bounds,
UI test code generation, fragile selector, selector stability, element debugger,
UI hierarchy viewer, accessibility node, screen inspector, layout inspector,
Android developer tools, QA engineer tools, mobile test automation, SDET tools,
Windows UI testing, Win32 automation, WPF inspector, Electron app inspector,
no root Android, wireless ADB, ADB pairing code, USB debugging, Android 11 ADB,
UI diff, UI regression testing, session history, element bookmarks, JSON export,
XML export, UIAutomator XML, Compose Multiplatform, Kotlin desktop, JVM desktop,
offline developer tool, no telemetry, GDPR developer tool, privacy first tools,
free developer tool Windows, open source developer utility, MIT license app
```

### GEO-targeted keyword notes

| Market | High-priority additional terms to emphasise in regional copy |
|--------|-------------------------------------------------------------|
| **India (en-IN)** | uiautomator2, Appium, Maestro, ADB, mobile QA, SDET |
| **USA / Canada** | pywinauto, FlaUI, enterprise Windows automation, accessibility compliance |
| **UK / EU** | GDPR-safe, offline, no telemetry, accessibility audit tool |
| **Germany (de)** | barrierefreiheit, UI-Testautomatisierung (use in translated listing) |
| **Japan (ja)** | uiautomator2, Appium (dominant frameworks in JP mobile QA) |
| **Australia** | Appium, Maestro, Android ADB inspector |

---

## 4. Age Rating & Content Declarations

Complete the **Microsoft Store age rating questionnaire** with these answers:

| Question | Answer |
|----------|--------|
| Does your app contain alcohol, tobacco, or drug references? | No |
| Does your app contain gambling content? | No |
| Does your app contain violence? | No |
| Does your app contain sexual content? | No |
| Does your app allow users to communicate with other users? | No |
| Does your app share user location? | No |
| Does your app display ads? | No |
| Does your app collect / transmit personal data? | No |
| Does your app allow in-app purchases? | No |

**Expected rating:** Everyone (ESRB) / PEGI 3 (Europe)

---

## 5. Screenshot & Graphic Asset Requirements

### Store graphic specifications

| Asset | Dimensions | Format | Notes |
|-------|-----------|--------|-------|
| Store Logo | 50 × 50 px | PNG | Square icon for search results |
| Square 44×44 Logo | 44 × 44 px | PNG | Taskbar / Start menu small |
| Square 150×150 Logo | 150 × 150 px | PNG | Start menu medium tile — **required** |
| Square 310×310 Logo | 310 × 310 px | PNG | Start menu large tile |
| Wide 310×150 Logo | 310 × 150 px | PNG | Start menu wide tile |
| Splash Screen | 620 × 300 px | PNG | Cold-start splash (dark bg `#0D1117`) |
| Badge Logo | 24 × 24 px | PNG | Lock-screen badge (white monochrome) |

All icon assets are generated automatically by `scripts/build-msix.ps1` from  
`uiscope/app/src/main/resources/icon.png` at build time.

### Screenshots (required — minimum 1, maximum 10)

Minimum size: **1366 × 768 px** (landscape). PNG or JPEG, no borders.

| # | Suggested screenshot | Why it converts |
|---|---------------------|-----------------|
| 1 | Full 3-panel inspector with a real Android device tree + screenshot | Hero — shows main value prop |
| 2 | PC Inspector with a Windows app tree + hover highlight overlay | Shows Windows UIAutomation value |
| 3 | Code generation panel — Python uiautomator2 code with fragile-selector badge | Concrete developer utility |
| 4 | Diff mode — three-column added/removed/changed view | Unique differentiator |
| 5 | Watch mode — rule definition + notification toast | Shows automation depth |
| 6 | Breadcrumb bar + XPath one-click copy | Quick value, easy to understand |
| 7 | History / session browser screen | Persistence feature |
| 8 | Launcher (mode picker) + onboarding screen | Shows ease of entry |
| 9 | Export dialog showing JSON/XML/outline options | Interoperability |
| 10 | Settings screen — shows offline/no-telemetry guarantee | Privacy trust signal |

**Recommended caption for screenshots (shown under each in the Store):**

1. "Inspect any Android app UI tree live — no agent, no root"
2. "Inspect any Windows app via UIAutomation — hover any window, press Alt+Shift+P"
3. "Generate automation code for 8 frameworks with selector quality warnings"
4. "Diff two UI snapshots — see exactly what was added, removed, or changed"
5. "Watch Mode — get notified the moment a UI condition is met"
6. "Breadcrumb path + one-click XPath copy"
7. "Full session history saved locally — no cloud required"
8. "Four modes, one install — pick your workflow from the launcher"
9. "Export the full element tree as JSON, XML, or plain outline"
10. "Zero telemetry, zero accounts — all data stays on your machine"

---

## 6. MSIX Package Details

### AppxManifest.xml key fields

| Field | Value |
|-------|-------|
| `Identity Name` | `TBTechs.UIScopeDesktopAndroidUIInspector` |
| `Identity Publisher` | `CN=E08824C8-6F22-4DC2-8025-DD8C707E2BE9` |
| `Identity Version` | `1.0.0.0` (format: `MAJOR.MINOR.PATCH.0`) |
| `ProcessorArchitecture` | `x64` |
| `DisplayName` | `UIScope: Desktop and Android UI Inspector` |
| `PublisherDisplayName` | `TBTechs` |
| `Application Executable` | `UIScope.exe` |
| `EntryPoint` | `Windows.FullTrustApplication` |
| `TargetDeviceFamily` | `Windows.Desktop` |
| `MinVersion` | `10.0.17763.0` (Windows 10 1809) |
| `Capability` | `runFullTrust` (required for JVM desktop apps) |

### MSIX CI build secrets (GitHub repo → Settings → Secrets)

| Secret | Default fallback | Description |
|--------|-----------------|-------------|
| `MSIX_PACKAGE_NAME` | `TBTechs.UIScopeDesktopAndroidUIInspector` | Identity Name in manifest |
| `MSIX_PUBLISHER` | `CN=E08824C8-6F22-4DC2-8025-DD8C707E2BE9` | Exact Publisher CN string from Partner Center |
| `MSIX_PUBLISHER_DISPLAY` | `TBTechs` | Human-readable publisher name |

---

## 7. Building the MSIX

### Automated (recommended) — GitHub Actions

Push a semver tag to trigger the full CI pipeline:

```bash
git tag v1.0.0
git push origin main --tags
```

The `build-windows` job in `.github/workflows/release.yml` runs  
`scripts/build-msix.ps1` automatically and uploads `UIScope-1.0.0-x64.msix`  
as a release artifact.

### Manual build (Windows machine required)

```powershell
# 1. Build the JVM distributable
cd uiscope
.\gradlew.bat :app:packageMsi --no-daemon

# 2. Build the MSIX package
cd ..
.\scripts\build-msix.ps1 `
  -Version "1.0.0" `
  -PackageName "TBTechs.UIScopeDesktopAndroidUIInspector" `
  -Publisher "CN=E08824C8-6F22-4DC2-8025-DD8C707E2BE9" `
  -PublisherDisplay "TBTechs"

# Output: msix-out\UIScope-1.0.0-x64.msix
```

### Sideload for local testing (before Store submission)

```powershell
# Enable Developer Mode in Windows Settings → Privacy & Security → For developers
Add-AppxPackage -Path "msix-out\UIScope-1.0.0-x64.msix"
```

To test with a self-signed certificate:

```powershell
# 1. Generate self-signed cert (one-time)
New-SelfSignedCertificate `
  -Subject "CN=E08824C8-6F22-4DC2-8025-DD8C707E2BE9" `
  -CertStoreLocation "Cert:\CurrentUser\My" `
  -Type CodeSigningCert

# 2. Export and trust it
$cert = Get-ChildItem "Cert:\CurrentUser\My" | Where-Object { $_.Subject -match "E08824C8" }
Export-Certificate -Cert $cert -FilePath uiscope-dev.cer
Import-Certificate -FilePath uiscope-dev.cer -CertStoreLocation "Cert:\LocalMachine\Root"

# 3. Install
Add-AppxPackage -Path "msix-out\UIScope-1.0.0-x64.msix"
```

---

## 8. Submitting to Partner Center — Step-by-Step

### One-time setup

1. **Register** at https://partner.microsoft.com/dashboard  
   — Individual or company account; one-time **$19 USD** registration fee  
   — Use the same Microsoft account you want to publish under

2. **Reserve the app name**  
   → Apps and games → Create a new app → Type **"UIScope: Desktop and Android UI Inspector"**  
   → Reserve it (holds the name for 1 year)

3. **Note your Publisher identity**  
   → Account settings → Organization profile → **Publisher ID** and **Publisher display name**  
   → The `Publisher` CN string must exactly match `AppxManifest.xml`

### Per-release submission flow

```
Partner Center Dashboard
└── Apps and games
    └── UIScope: Desktop and Android UI Inspector
        └── Start a submission
            ├── Pricing and availability  →  Free · All markets
            ├── Properties               →  Category: Developer Tools / Testing Tools
            │                               Age rating: fill questionnaire (see §4)
            ├── Store listing (English)  →  Paste copy from §2, keywords from §3,
            │                               upload screenshots from §5
            ├── Packages                 →  Upload UIScope-X.Y.Z-x64.msix
            ├── Notes for certification  →  "Desktop developer tool. Requires
            │                               Windows UIAutomation (built into Windows).
            │                               No special permissions beyond
            │                               runFullTrust for JVM desktop apps."
            └── Submit to the Store
```

### Certification notes template

```
UIScope is a developer debugging tool. It uses Windows UIAutomation (a built-in 
Windows accessibility framework, no additional drivers required) to inspect the 
UI element trees of other running applications. This is the same API used by 
accessibility tools like Narrator and Magnifier.

The app runs fully offline — no network calls are made except an optional 
version-check GET request to the GitHub Releases API at startup (can be 
disabled in Settings).

The runFullTrust capability is required because the app is a JVM/Compose Desktop 
application and must launch the JVM runtime process.

No user-generated content, no ads, no in-app purchases, no accounts.
```

### Expected review timeline

| Stage | Typical wait |
|-------|-------------|
| Automated policy checks | Minutes |
| Manual certification review | 1–3 business days |
| Publishing after approval | 15 minutes – 24 hours |

---

## 9. AppxManifest.xml Reference

The manifest is generated by `scripts/build-msix.ps1` at build time.  
Full reference copy (version `1.0.0.0`):

```xml
<?xml version="1.0" encoding="utf-8"?>
<Package
  xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
  xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities">

  <Identity
    Name="TBTechs.UIScopeDesktopAndroidUIInspector"
    Publisher="CN=E08824C8-6F22-4DC2-8025-DD8C707E2BE9"
    Version="1.0.0.0"
    ProcessorArchitecture="x64" />

  <Properties>
    <DisplayName>UIScope: Desktop and Android UI Inspector</DisplayName>
    <PublisherDisplayName>TBTechs</PublisherDisplayName>
    <Logo>Assets\StoreLogo.png</Logo>
  </Properties>

  <Dependencies>
    <TargetDeviceFamily
      Name="Windows.Desktop"
      MinVersion="10.0.17763.0"
      MaxVersionTested="10.0.22621.0" />
  </Dependencies>

  <Resources>
    <Resource Language="en-us" />
  </Resources>

  <Applications>
    <Application
      Id="UIScope"
      Executable="UIScope.exe"
      EntryPoint="Windows.FullTrustApplication">
      <uap:VisualElements
        DisplayName="UIScope: Desktop and Android UI Inspector"
        Description="See what your UI is made of. Live inspection of Android and Windows UI trees."
        BackgroundColor="transparent"
        Square150x150Logo="Assets\Square150x150Logo.png"
        Square44x44Logo="Assets\Square44x44Logo.png" />
    </Application>
  </Applications>

  <Capabilities>
    <rescap:Capability Name="runFullTrust" />
  </Capabilities>

</Package>
```

---

## 10. Secrets & CI Variables

### GitHub repository secrets required for MSIX CI

> Repository → Settings → Secrets and variables → Actions → New repository secret

| Secret name | Value source | Required? |
|-------------|-------------|----------|
| `MSIX_PACKAGE_NAME` | Partner Center → App identity | Optional (has default) |
| `MSIX_PUBLISHER` | Partner Center → Account settings → Publisher CN | Optional (has default) |
| `MSIX_PUBLISHER_DISPLAY` | Your publisher display name | Optional (has default) |

The CI workflow (`release.yml`) falls back to the default values baked into  
`scripts/build-msix.ps1` if these secrets are not set — the defaults match  
the current live Store submission.

### Updating Publisher CN after Store registration

If Partner Center gives you a different Publisher CN than the current default:

1. Update the secret `MSIX_PUBLISHER` in GitHub  
2. Re-run the `build-windows` CI job  
3. Sideload and verify before submitting to the Store

---

## 11. Post-Publish Checklist

- [ ] Store listing is live at https://apps.microsoft.com/detail/9MWH310XHGQ5
- [ ] Download and install from the Store on a clean Windows 10 VM — verify launch
- [ ] Download and install from the Store on Windows 11 — verify launch
- [ ] Update `docs/index.html` Store link if product ID changes
- [ ] Update `release.yml` release body Store link if product ID changes
- [ ] Tag the release on GitHub (`git tag v1.0.0 && git push --tags`)
- [ ] Update `uiscope/CHANGELOG.md` with release notes
- [ ] Update `uiscope/DISTRIBUTION.md` § 10 store listing copy if descriptions changed
- [ ] Post release announcement linking https://apps.microsoft.com/detail/9MWH310XHGQ5
- [ ] Monitor Partner Center → Reports → Ratings & reviews for user feedback
- [ ] Set up Partner Center → Notifications for certification failure alerts

---

*Built with Kotlin 2.0 + Compose Multiplatform Desktop · MIT License*  
*© TBTechs — https://github.com/TITANICBHAI*
