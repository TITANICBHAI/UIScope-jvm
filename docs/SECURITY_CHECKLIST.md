# Security checklist (plan P7-3)

Every line is either enforced by CI or audited with the evidence in brackets. Last full pass: 2026-10-31 (commit `5bbe1b3`).

## Parsers

- [x] **The XML parser is hardened.** `engine/.../adb/UiAutomatorParser.kt` (the only XML reader — it parses UI Automator dumps, which come from a device) sets `FEATURE_SECURE_PROCESSING`, disallows DTDs entirely, disables external general and parameter entities, XInclude and entity expansion. Hostile XML becomes a typed `PARSE_FAILED`, never an entity resolution. [P2-3 audit + features in code; the parse tests cover malformed dumps]

## Import and download guards

- [x] **ZIP-slip on `.uiscope` import is impossible by construction.** `BundleCodec.read` never extracts entries to disk: capture.json and the PNG are read into memory (compressed cap 100 MB, inflated cap 100 MB, at most 32 entries, no duplicates — `TooLarge`/`too many files` typed rejections), and the only file ever written uses the store's own `import-<UUID>.png` name in the store's temp dir. No zip-controlled path ever reaches `Files.write`. [BundleCodec.kt caps + SnapshotBundles.import]
- [x] **ZIP-slip on the platform-tools download.** The ADB downloader validates every entry's resolved path against the target directory before writing (P2-4). [AdbDownloader + LegacyDataTest-era tests]
- [x] **Size caps on imports.** File size checked before the stream is opened, compressed and inflated caps inside the codec. [BundleCodec.MAX_BYTES; BundleCodecTest]
- [x] **Import validation.** Wrong `schemaVersion` → typed rejection; missing `capture.json` → typed rejection; path-traversal entry names cannot be expressed (no extraction). [BundleCodecTest]

## Processes

- [x] **Argument lists only, never a host shell.** The `host-shell` guardrail rule (`sh -c`, `bash -c`, `cmd /c` anywhere, including string literals) fails the build. [GuardrailsTest "rule host-shell"]
- [x] **The user-supplied adb path is never interpreted by a shell.** It is `argv[0]` of a `ProcessBuilder` argument list built by `AdbLocator`/`EngineAdbPort`. `adb shell …` is adb's own device-side subcommand — not a host shell. [no shell strings in the repo (grep) + host-shell rule]
- [x] **ProcessBuilder exists only in engine/process.** [guardrail `process-builder`, closes B10]
- [x] **The helper restart policy is the only auto-spawn** and is capped at 3 restarts/minute (P3-2). Relauncher (crash dialog) spawns only the app's own command. [WindowsHelperClientTest, RelauncherTest]

## Files

- [x] **Writes go only to the data folder, the OS temp dir, or a user-chosen path.** Snapshots/settings/logs: `<data>`; helper screenshots: `%TEMP%`; exports: the user's native-dialog choice; bundle temp files: next to the user's chosen target, atomically moved. [audit 2026-10-31]

## Network

- [x] **Exactly two network actions exist**, both through the NetworkGateway; the guardrail fails the build on any third. [docs/network.md (P7-6)]

## Supply chain

- [x] **Dependency vulnerability scanning.** `github/dependency-review-action` runs on every pull request (fail on moderate+), and `.github/dependabot.yml` watches Gradle + the Actions versions weekly so alerts surface in the repository's Security tab. Push-only development still gets Dependabot alerts; PRs additionally get the review gate. [.github/workflows/ci.yml `dependency-review` job + .github/dependabot.yml]
