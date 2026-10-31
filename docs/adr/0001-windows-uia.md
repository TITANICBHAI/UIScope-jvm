# ADR 0001 — Windows UI Automation: persistent helper process

Status: accepted (architecture decision D-WIN-1 of the plan). Task: P3-0. Date: 2026-10-31.

## Decision

Windows inspection runs in a **persistent native helper process** (`UIScope.Helper.exe`, C#, net48) that owns
everything touching Win32/UI Automation: element queries, the pick overlay, the mouse hook while picking, the
hotkey, window enumeration and window screenshots. UIScope talks to it over stdin/stdout with the version-1
JSON protocol (Appendix J) via `WindowsHelperClient` (P3-2). The Kotlin side never calls UIA itself.

## Why a helper

1. **Hangs are survivable.** UIA calls into a misbehaving application can hang. A hung helper is killed and
   restarted (the client does this automatically, at most three times a minute); a hung UIA call inside the
   JVM would freeze the whole app with no recovery.
2. **DPI is solved once.** The helper's manifest declares PerMonitorV2, so every rectangle it reports is in
   physical pixels — no JVM/AWT scaling traps, no per-monitor math in Kotlin (B3).
3. **No shell-outs.** The 1.0.0 code shelled `powershell.exe` per query (the "JNA, no scripts" claim in its
   README was false). There are no temp scripts, no `-ExecutionPolicy Bypass`, no per-call process spawns.
4. **Fast after warm-up.** One process, one UIA session; no per-query startup cost.

## The alternative (fallback) and why it was not chosen

In-process JNA COM calls were the documented fallback **only if** the helper could not meet the performance
budgets (P3-0). It was not chosen because it gives up points 1–3 at once: a hung COM call cannot be killed,
the JVM's DPI awareness fights WPF rects, and the P3-9 guardrail forbids the pattern (`ProcessBuilder`
outside `engine/process` is banned, in-process JNA would make UIA unkillable by design).

## Measured / verified so far (CI, windows-latest)

- The helper builds with the Framework MSBuild present on the runner (classic net48 csproj, no SDK/NuGet).
- The protocol works end-to-end over real stdio: handshake, version refusal, correlated requests, timeouts
  with kill-and-restart, malformed lines ignored, events in order (`WindowsHelperClientTest`).
- The full port path is verified against the fake helper: window list, capture mapping (Appendix B nodes →
  model types), pick-event translation, and the origin round-trip through `CaptureViewport` (`EngineWindowsPortTest`).

## Budgets and what still needs a real desktop (docs/MANUAL_QA.md, Round 2)

| Budget | Status |
|---|---|
| Hover query p95 ≤ 100 ms | **needs manual verification** — UIA timing against real apps (Edge/VS Code/Calculator) requires a real display |
| 3,000-node window ≤ 2 s | **needs manual verification** — same |
| Helper cold start ≤ 500 ms | **needs manual verification** (the JVM-based fake boots in ~1–2 s on CI, but the real helper is a small native exe — expected comfortably under budget) |
| Helper RSS ≤ 80 MB | **needs manual verification** |
| Rectangles land within 1 px at 100–200 % scaling | **needs manual verification** (Appendix K Round 2) |

The ADR stays accepted unless a real-desktop measurement shows a budget miss; the fallback (in-process JNA)
would then be reconsidered with the numbers attached.
