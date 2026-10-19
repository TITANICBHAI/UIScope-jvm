# Distribution — what is true today

**Nothing has been published.** There is no release workflow, no signed installer and no Microsoft Store submission yet.
This file is rewritten completely in task P8-7.

## Identity

| Field | Value |
|---|---|
| Product name | UIScope |
| Store display name | UIScope: Desktop & Android UI Inspector |
| Publisher / vendor | TBTechs |
| Developer | TITANICBHAI |
| Version | the single line in `version.txt` (read by the build, `BuildInfo` and the installers) |

The MSIX manifest is `app/src/main/msix/AppxManifest.xml`. Its package identity and publisher string must be replaced with the
exact values Partner Center shows under *Product identity* before a Store build. It has not been built or validated yet.

## Channel plan (not yet done)

1. **Microsoft Store** (MSIX) — primary channel.
2. **Direct download** from GitHub Releases (MSI) — secondary.

## Building locally today

```bash
./gradlew :app:packageMsi   # Windows, needs the WiX toolset on PATH
./gradlew :app:packageExe   # Windows
./gradlew :app:run          # run the placeholder window
```

These packaging tasks are configured but **have not been verified** in this rebuild; task P8-1 hardens and verifies them.
