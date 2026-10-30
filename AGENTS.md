# Project memory (ZCode)

- **Our repo:** https://github.com/TITANICBHAI/UIScope-jvm (owner: TITANICBHAI)
- **Local copy:** `C:\Users\DELL\Downloads\UIScope-jvm-main` — a handoff zip **with `.git` included** (per the repo's agent workflow in `AGENT_PROMPT.md`).
- **What this is:** UIScope, a desktop + Android UI inspector being rebuilt from an unreliable 1.0.0. Gradle multi-module JVM project (`core`, `engine`, `ui`, `app`; plus `config`, `docs`, `scripts`, `legacy`).
- **Read in this order before working:** `docs/HANDOFF.md`, `plan.md` (authority for what to build), `docs/PROGRESS.md`, `docs/MANUAL_QA.md`. Onboarding prompt is `AGENT_PROMPT.md`.
- **Owner's rules (from AGENT_PROMPT.md):** one commit per task; tests first for `[REGRESSION]` items; no features outside `plan.md` (ideas → `docs/proposals.md`); no silent failures (detekt enforced); no process/file/network/DB work on the UI thread; never log element text, window titles, tree contents, full serials or command args; keep `docs/PROGRESS.md` and `docs/MANUAL_QA.md` current; honest verification reporting only.
- **Commit identity (repo-local):** `UIScope implementation agent <agent@uiscope.invalid>`.

## Environment notes (Windows PC, verified 2026-10-30)

- Shell is Windows cmd (no `ls`, `grep`, `head` — use `dir`, `findstr`, or PowerShell). PowerShell is available for JSON parsing.
- **Git 2.56** installed via `winget install Git.Git` at `C:\Program Files\Git\cmd\git.exe` (not on this shell's PATH — use the full path).
- **Push works.** A GitHub credential is stored in Windows Credential Manager (`git:https://github.com`); Git Credential Manager picks it up automatically on `git push origin main`. Repo is **public**; unauthenticated reads (fetch, API) work fine.
- **Watching CI** (verified working): PowerShell + GitHub API, e.g. `(Invoke-RestMethod 'https://api.github.com/repos/TITANICBHAI/UIScope-jvm/actions/runs?branch=main')`. For job logs/artifacts (need a token), get the stored credential without prompting: `"protocol=https`nhost=github.com`n`n" | & 'C:\Program Files\Git\cmd\git.exe' credential fill` and use the `password=` line as a Bearer token. Keep the token in memory only; never print it.
- **CI** (`.github/workflows/ci.yml`): on push to `main` and PRs, runs `./gradlew check` on `windows-latest` + `ubuntu-latest`, uploads test/detekt reports as artifacts. A "pages build and deployment" workflow also runs on every push (GitHub Pages is enabled).
- **CI status 2026-10-30: RED (pre-existing, not caused by us).** Both legs fail at `./gradlew check`: `:core:detekt` — "Analysis failed with 1 weighted issues" — detekt `SwallowedException` at `core/src/main/kotlin/com/titanicbhai/uiscope/snapshot/BundleCodec.kt:109:18`. Fixing this is effectively AGENT_PROMPT Step 1 (`./gradlew check` must pass).
