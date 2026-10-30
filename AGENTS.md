# Project memory (ZCode)

- **Our repo:** https://github.com/TITANICBHAI/UIScope-jvm (owner: TITANICBHAI)
- **Local copy:** `C:\Users\DELL\Downloads\UIScope-jvm-main` — a handoff zip **with `.git` included** (per the repo's agent workflow in `AGENT_PROMPT.md`).
- **What this is:** UIScope, a desktop + Android UI inspector being rebuilt from an unreliable 1.0.0. Gradle multi-module JVM project (`core`, `engine`, `ui`, `app`; plus `config`, `docs`, `scripts`, `legacy`).
- **Read in this order before working:** `docs/HANDOFF.md`, `plan.md` (authority for what to build), `docs/PROGRESS.md`, `docs/MANUAL_QA.md`. Onboarding prompt is `AGENT_PROMPT.md`.
- **Owner's rules (from AGENT_PROMPT.md):** one commit per task; tests first for `[REGRESSION]` items; no features outside `plan.md` (ideas → `docs/proposals.md`); no silent failures (detekt enforced); no process/file/network/DB work on the UI thread; never log element text, window titles, tree contents, full serials or command args; keep `docs/PROGRESS.md` and `docs/MANUAL_QA.md` current; honest verification reporting only.
- **Commit identity (repo-local):** `UIScope implementation agent <agent@uiscope.invalid>`.

## Working model (agreed with owner, 2026-10-30) — READ THIS FIRST

- **Never fry the owner's PC.** Do NOT run heavy work locally: no `./gradlew check`, no `./gradlew build`/`assemble`, no test suites, no long-running loops or watchers on this machine. The local PC is only for reading code, editing files, git operations, and light inspection.
- **CI is our executor.** The verification loop is: make changes → commit → `git push origin main` → GitHub Actions runs the checks → watch the run → download/read the test, detekt and build reports (artifacts + logs, method below) → report honestly what passed, what failed, and what could not be verified.
- **Scope = everything verifiable WITHOUT a real device**: unit tests, detekt/static analysis, Gradle build/compilation checks, and any other check `plan.md` requires that runs on a plain CI machine. Anything needing hardware or accounts (a phone/ADB device, 125–150 % display scaling, a second monitor, Store credentials) is NOT verifiable in this loop — implement against fakes and add the manual steps to `docs/MANUAL_QA.md`, per `AGENT_PROMPT.md`.
- **Watch loop (verified working):** poll `https://api.github.com/repos/TITANICBHAI/UIScope-jvm/actions/runs?branch=main`, then `actions/runs/{id}/jobs` for per-leg conclusions; runs finish in ~2–5 min (queue + ~1–2 min runtime). Job logs and report artifacts need a Bearer token: get the stored credential silently via `"protocol=https`nhost=github.com`n`n" | & 'C:\Program Files\Git\cmd\git.exe' credential fill` and use its `password=` line. Keep the token in memory only; never print or store it.

## Multi-agent protocol — MANDATORY before every push (other agents may work on this same PC/repo)

**Check where you are heading before you push, every time:**

1. `git fetch origin` — if `origin/main` has moved beyond local `main` (`git rev-list --left-right --count main...origin/main`), assume another agent did it: `git pull --rebase origin main` FIRST, then re-check whether your task is still needed (their commit may already be your fix) and whether your edit still applies cleanly.
2. `git status -sb` — the working tree must contain ONLY your own changes. Anything staged/modified that you did not edit is another agent's work: do NOT commit it, do NOT discard it — leave it and commit only your own files by explicit path (`git add <your files>`, never `git add .` / `-A`).
3. Before assuming a CI result is yours, match the run's `head_sha` to YOUR commit — another agent's push may have started a run minutes before/after yours.
4. If `git push` is rejected (non-fast-forward), do NOT force-push. Fetch, rebase, re-check the task is still needed, and push again.
5. To avoid colliding on the same task: check the newest `git log origin/main` and `docs/PROGRESS.md` before starting a task — if another agent is visibly mid-task on it, take a different task.
6. This local folder may be shared with another agent's clone/process; between your own steps, re-verify `git status` is still exactly what you left it before committing.

## Environment notes (Windows PC, verified 2026-10-30)

- Shell is Windows cmd (no `ls`, `grep`, `head` — use `dir`, `findstr`, or PowerShell). PowerShell is available for JSON parsing.
- **Git 2.56** installed via `winget install Git.Git` at `C:\Program Files\Git\cmd\git.exe` (not on this shell's PATH — use the full path).
- **Push works.** A GitHub credential is stored in Windows Credential Manager (`git:https://github.com`); Git Credential Manager picks it up automatically on `git push origin main`. Repo is **public**; unauthenticated reads (fetch, API) work fine.
- **CI** (`.github/workflows/ci.yml`): on push to `main` and PRs, runs `./gradlew check` on `windows-latest` + `ubuntu-latest`, uploads test/detekt reports as artifacts. A "pages build and deployment" workflow also runs on every push (GitHub Pages is enabled).
- **CI status 2026-10-30: RED (pre-existing, not caused by us).** Both legs fail at `./gradlew check`: `:core:detekt` — "Analysis failed with 1 weighted issues" — detekt `SwallowedException` at `core/src/main/kotlin/com/titanicbhai/uiscope/snapshot/BundleCodec.kt:109:18`. Fixing this is effectively AGENT_PROMPT Step 1 (`./gradlew check` must pass).
