# UIScope

**UIScope: Desktop & Android UI Inspector** — by TBTechs (developer: TITANICBHAI).

> **Status: under rebuild toward 1.0.** The previous implementation was set aside (it is kept, unused, in `legacy/`). The application
> window is currently a placeholder that says "rebuild in progress": **you cannot inspect anything with it yet.** This README
> describes what is true of the code today.

## What is planned for 1.0 (not implemented yet)

Inspect the UI tree of an Android phone over `adb`, and of Windows application windows, with a screenshot overlay, a selector
workbench and code generation. The full plan is in `plan.md`; progress is tracked in [docs/PROGRESS.md](docs/PROGRESS.md).

## What exists today

- Multi-module Gradle build (`core`, `engine`, `ui`, `app`) on Kotlin 2.0 / Compose Multiplatform Desktop.
- `core`: the capture data model (element tree, stable structural ids, element lookup index, viewport math), error and result types,
  app identity/version information.
- `engine`: a process runner (kills the whole process tree on timeout/cancel), a long-lived process wrapper, the single HTTP gateway,
  data-folder locations and rotating logs.
- Test harness: fakes for processes and HTTP, golden-file and fixture helpers, synthetic Android dump samples.
- `scripts/capture-fixtures.sh|.ps1`: records raw `adb` dumps from your phone for parser tests.

## Requirements

- **JDK 21** ([Adoptium](https://adoptium.net)). Gradle can download one automatically if none is installed.
- Windows 10/11 for the Windows-inspection feature when it lands; Android inspection will work from Windows, macOS and Linux.

## Build and run

```bash
./gradlew check        # compile, run all tests, run detekt
./gradlew :app:run     # opens the placeholder window
```

On Windows use `gradlew.bat` instead of `./gradlew`.

## Layout

| Path | Purpose |
|---|---|
| `core/` | Pure Kotlin: model, errors, identity. No UI, no process or network code. |
| `engine/` | Talks to the outside world: processes, HTTP, files, logs. |
| `ui/` | Compose components (empty until Phase 4). |
| `app/` | Entry point, packaging, `app.properties` (identity and links). |
| `legacy/` | Old code, read-only reference, not built. |
| `scripts/` | Developer scripts. |
| `docs/` | Progress, manual QA checklist, proposals. |

## License

MIT — see [LICENSE](LICENSE).
