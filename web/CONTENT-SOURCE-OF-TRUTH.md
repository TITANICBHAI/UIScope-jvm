# Content source of truth

When site copy and repository source disagree, the repository source wins. Recheck these files before changing public product claims:

- `README.md` — current description, supported host, workflows and build instructions: https://github.com/TITANICBHAI/UIScope-jvm/blob/main/README.md
- `CHANGELOG.md` — release state and whether a version is actually released: https://github.com/TITANICBHAI/UIScope-jvm/blob/main/CHANGELOG.md
- `DISTRIBUTION.md` — packaging and release requirements: https://github.com/TITANICBHAI/UIScope-jvm/blob/main/DISTRIBUTION.md
- `PRIVACY.md` and `docs/network.md` — local data handling and network actions: https://github.com/TITANICBHAI/UIScope-jvm/blob/main/PRIVACY.md
- `docs/troubleshooting.md` — current troubleshooting steps: https://github.com/TITANICBHAI/UIScope-jvm/blob/main/docs/troubleshooting.md

## Claims this website intentionally makes

- UIScope is a Windows desktop application.
- It inspects Windows application windows using Windows UI Automation.
- It inspects connected Android devices using ADB from that Windows desktop application; it is not an Android app.
- The current source documents Windows 10 version 1809 or later and Windows 11.
- The current source version is 1.0.2. The site links to GitHub Releases for the installer and never hosts or links an older binary as current.
- Ten code generation targets are listed in the README: uiautomator2, UI Automator (Kotlin), Appium Java, Appium Python, Maestro, XPath, pywinauto, FlaUI, AutoHotkey v2 and PowerShell.
- Privacy wording must be checked against `PRIVACY.md` and `docs/network.md` before being changed.

Last audited against the public repository pages on 2 November 2026. Re-audit on every public release.
