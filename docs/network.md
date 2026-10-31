# Network inventory (plan P7-6)

UIScope is an offline inspector. Everything on this list is the complete set of network actions the app can ever
perform; anything else fails the build (see "Enforcement" below). There is no telemetry, no crash upload, no usage
statistics, and nothing is ever downloaded without a deliberate user action.

## The two allowed actions

| # | What | When | Where | Host |
|---|---|---|---|---|
| 1 | **Update check** — a GET of the GitHub releases API for the repository's `latest` release. Reads JSON only; **never downloads anything** (P7-5). | Only when *Check for updates* is on (opt-out) **and** the build is not a Store build (`AppInfo.isStoreBuild`, P0-4); on demand from Help → *Check for updates*. | `engine/.../update/UpdateChecker.kt` through the `NetworkGateway` (P0-5) | `api.github.com` (repo URL from `AppInfo`) |
| 2 | **ADB platform-tools download** — a GET of the platform-tools ZIP, streamed to the data folder with the ZIP-slip guard (P2-4). | Only on the user's explicit click (first-run or Settings → *Download platform-tools*). | `engine/.../adb/AdbDownloader.kt` through the `NetworkGateway` | `dl.google.com` |

## Enforcement

- The P3-9 guardrails (enforced by `:engine:test` on every CI run) fail the build for `URL(`, `HttpClient` and
  `openConnection` anywhere outside the `NetworkGateway`, so a third network path cannot be added silently.
- The Store build additionally makes **no** update-check call at all — covered by `UpdateCheckerTest`
  ("the Store build and the opt-out make no network call at all").

## Reviewed call sites

- `JdkNetworkGateway` (`engine/.../process/JdkNetworkGateway.kt`) is the only class that opens connections.
- Grep check (2026-10-31): `URL(`, `HttpClient`, `openConnection` appear only in the gateway, its tests and the
  guardrail's own rule definitions.
