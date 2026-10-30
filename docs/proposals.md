# Proposals

Ideas that came up during implementation and are **not** in the plan. Nothing here is built. Add one line per idea; the owner decides.

- (Round 5) Write non-ASCII characters in capture JSON as UTF-8 instead of `\uXXXX` escapes, so exported JSON is readable for people. Needs a writer option; no format change.
- (Round 5) Store the capture time zone offset in `capturedAt` (it is UTC today), so a snapshot list could show the local time of the device.
- (Round 5) A `reindex` button in Settings > Storage that re-reads every snapshot folder (rebuilds the list after manual file changes; the start-up recovery already adopts orphans).
- (Round 5) Optional guardrail for `sh -c` / `cmd /c` string patterns (P7-3 asks for a manual grep today).
