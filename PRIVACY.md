# Privacy

**UIScope collects nothing. There is no telemetry, no analytics, no crash upload and no account.**

## What UIScope reads

UIScope inspects the UI of the machine (or Android device) you point it at, only while you ask it to:
element trees, screenshots, window titles and process names of the applications you capture. It also reads the
Windows UI Automation tree through its bundled helper process.

## What is stored, and where

Everything UIScope keeps is on your machine, under `%LOCALAPPDATA%\UIScope` (Windows):

- `snapshots/` and `db/` — the snapshots you save on purpose (tree + screenshot + notes)
- `logs/` — rotating log files; **element text, window titles, tree contents and device serials are never written to the log**
- `settings.json`, the helper exe and its lock files

Deleting a snapshot removes its files. *Clear all* in Settings removes everything. Nothing is synced anywhere.

## What leaves your machine

Exactly two network actions exist, both through one gateway and both optional:

1. **Update check** — a request to the GitHub releases API for your version's release notes. Off by default in
   the Microsoft Store build; opt-out in Settings; never downloads anything.
2. **ADB download** — `dl.google.com`'s platform-tools ZIP, only when you click *Download platform-tools*.

Nothing else. If UIScope is offline, everything except those two opt-ins works.

## Third parties

None. See [THIRD_PARTY_NOTICES](app/src/main/resources/THIRD_PARTY_NOTICES.md) for bundled components.

*Android is a trademark of Google LLC. Windows is a trademark of Microsoft Corporation. UIScope is not
affiliated with, endorsed by or sponsored by either company.*
