# Project memory (ZCode)

- **Our repo:** https://github.com/TITANICBHAI/UIScope-jvm (owner: TITANICBHAI)
- **Local copy:** `C:\Users\DELL\Downloads\UIScope-jvm-main` — originally a GitHub zip download of `main` (no `.git` at first).
- **What this is:** UIScope, a desktop + Android UI inspector being rebuilt from an unreliable 1.0.0. Gradle multi-module JVM project (`core`, `engine`, `ui`, `app`; plus `config`, `docs`, `scripts`, `legacy`).
- **Read in this order before working:** `docs/HANDOFF.md`, `plan.md` (authority for what to build), `docs/PROGRESS.md`, `docs/MANUAL_QA.md`. Onboarding prompt is `AGENT_PROMPT.md`.
- **Owner's rules (from AGENT_PROMPT.md):** one commit per task; tests first for `[REGRESSION]` items; no features outside `plan.md` (ideas → `docs/proposals.md`); no silent failures (detekt enforced); no process/file/network/DB work on the UI thread; never log element text, window titles, tree contents, full serials or command args; keep `docs/PROGRESS.md` and `docs/MANUAL_QA.md` current; honest verification reporting only.

## Environment notes (Windows PC)

- Shell is Windows cmd (no `ls`, `grep` etc. — use `dir`, `findstr` or full paths).
- winget is available for installing tooling.
