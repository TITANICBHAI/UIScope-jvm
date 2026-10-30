# UIScope

**UIScope: Desktop & Android UI Inspector** — by TBTechs (developer: TITANICBHAI).

> **Status: under rebuild toward 1.0.** The previous implementation was set aside (it is kept, unused, in `legacy/`). The application
> window is a temporary **developer harness**: it can capture an Android phone's UI tree and screenshot and show the element count, but
> there is no inspector, selector or code panel yet. This README describes what is true of the code today.

## What is planned for 1.0 (not implemented yet)

Inspect the UI tree of an Android phone over `adb`, and of Windows application windows (Windows only), with a screenshot overlay, a
selector workbench and code generation. The full plan is in `plan.md`; progress is tracked in [docs/PROGRESS.md](docs/PROGRESS.md).

## What exists today

- Multi-module Gradle build (`core`, `engine`, `ui`, `app`) on Kotlin 2.0 / Compose Multiplatform Desktop.
- `core`: the capture data model (element tree, stable structural ids, element lookup index, viewport math), XML/outline export with
  redaction, the selector engine and code generation for ten test frameworks, error and result types, app identity/version information.
  None of this is visible in the app yet (the window is a temporary developer harness).
- `engine`: the Android layer (finding adb, devices, UI-tree capture, wireless debugging, verified platform-tools download), a process runner (kills the whole process tree on timeout/cancel), a long-lived process wrapper, the single HTTP gateway,
  data-folder locations and rotating logs.
- Test harness: fakes for processes and HTTP, golden-file and fixture helpers, synthetic Android dump samples.
- `scripts/capture-fixtures.sh|.ps1`: records raw `adb` dumps from your phone for parser tests.

## Requirements

- **JDK 21** ([Adoptium](https://adoptium.net)). Gradle can download one automatically if none is installed.
- Windows 10/11 is the 1.0 target. macOS and Linux builds (Android inspection only) are planned for 1.1; they are not tested or packaged for those systems yet.

## Build and run

```bash
./gradlew check        # compile, run all tests, run detekt
./gradlew :app:run     # opens the temporary developer window (Android capture check)
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
