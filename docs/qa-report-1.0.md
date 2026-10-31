# UIScope 1.0 QA report (plan P9-1, Appendix E matrix)

**Method:** every row that can be verified without a device or a display was executed by the implementation
agent on CI (Windows + Ubuntu runners, `./gradlew check`) and carries the run that proves it. Rows that need
the owner's machine or a phone are listed as **pending** with the exact MANUAL_QA section to execute; this
report is finished when the owner's pass fills them in.

**Last CI-verified commit:** `0331efe` (run 41210737929). 210 tests, detekt clean, both legs green.

## Verified on CI

| Area | Rows | Evidence |
|---|---|---|
| Capture model & identity | structural ids stable across re-captures, index completeness, viewport math (G6, including the origin-subtraction regression B3 pinned absolutely) | unit tests, both legs |
| Android engine | adb discovery, device watch, tree/screenshot capture with fallbacks, wireless pairing, verified platform-tools download | unit tests + fake adb over real stdio |
| Windows engine | protocol handshake/version refusal/timeout-restart, helper crash recovery, pick-event translation, hotkey registration incl. HOTKEY_IN_USE, ELEVATED_TARGET / LIMITED_ACCESSIBILITY / TREE_TRUNCATED surfacing | unit tests over the fake helper process, both legs |
| Selectors & codegen | ten frameworks, escaping, reliability tiers, verdicts; the exported XML round-trips against `javax.xml.xpath` | unit tests |
| Diff (P6-1) | the four-pass matcher over nested trees, duplicate ids, textless nodes, moved nodes, list inserts; field-level diffs | G12 unit tests |
| UI (Compose) | Inspect screens (both targets), Snapshots screen (list/search/pin/delete/open), export dialog (redaction sentinel, subtree scope), Compare screen (filters, locate-on-both, JSON export), Settings, first-run, keyboard shortcuts | Compose UI tests under xvfb (Linux) and the desktop session (Windows) |
| Guardrails | ProcessBuilder/host-shell/HTTP/AWT-input/keyboard-hook rules; the whole repository scans clean | guardrail tests on every CI run |
| Security checklist | every line ticked with enforcement evidence | docs/SECURITY_CHECKLIST.md |
| Packaging config | jpackage module list, MSIX manifest + script, release workflow | compile + workflow on main; execution rows below |

## Pending — the owner's machine (execute with docs/MANUAL_QA.md)

| Area | Rows | MANUAL_QA section |
|---|---|---|
| Windows pick overlay | alignment across DPI/monitors, click swallowing, hover latency p95 ≤ 100 ms, zero hooks after cancel | Round 2, items 1–7 + `--demo-pick` |
| Hotkey | starts a pick without focus; taken-shortcut message | Round 2, items 8–9 |
| Retention | 10 minutes of auto-save stays under the cap; SQLite first-save unpacking | Round 5, items 1–4 |
| Update check | against the real repository releases | Round 5, item 7 |
| Visual alignment | canvas vs real window at 125/150 % scaling | Round 2, item 4; P7-4 Appendix F budgets (record in docs/perf.md) |
| Performance | 3k/10k-node scroll/selection/zoom, cold start | Round 3, item 9 |
| Single instance & crash dialog | second launch focuses the first window; crash dialog copy/restart | Round 3, items 4 and 8 |
| Release pipeline | tag → draft release with MSI/EXE/MSIX + SHA256SUMS; clean-VM installs; MSIX sideload; WACK; the stranger test | Release and packaging, items 1–6 |
| Beta & go-live | P9-2 (5–10 external testers, ≥ 1 week), P9-3 (go/no-go) | owner tasks |

## Verdict

Nothing verified on CI is failing. The release gate is the owner's pending rows above; none of them is known
to be broken.
