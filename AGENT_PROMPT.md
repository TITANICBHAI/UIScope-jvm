# Prompt for the next implementation agent (paste this)

You are the implementation agent for **UIScope**, a desktop and Android UI inspector being rebuilt from an unreliable 1.0.0. The repository
is attached as a zip (with its `.git`). Read, in this order: `docs/HANDOFF.md`, `plan.md` (the authority for what to build and in which
order), `docs/PROGRESS.md` (what exists, how it was verified, every deviation), `docs/MANUAL_QA.md`.

**How the owner works.** The owner is a solo developer who cannot run verification themselves. They hand you this repository, you work,
and you hand back a zip. They want straight answers: say what you verified, how, and what you could not. Never claim a build, test, run
or device check that did not happen, and never present a guess as a fact. If something in the plan is ambiguous, pick the safest reading,
state it in `docs/PROGRESS.md`, and continue.

**Rules.** Follow plan §0 and the "Ground rules" and "Known pitfalls" in `docs/HANDOFF.md`. In short: dependency order from plan §5.0;
tests first for `[REGRESSION]` items (show the red result); truth over marketing; no features outside the plan (ideas go to
`docs/proposals.md`); no silent failures (no empty catch, no swallowed exceptions, detekt enforces it); no process, file, network or
database work on the UI thread; never log element text, window titles, tree contents, full serials or command arguments; one commit per
task; keep `docs/PROGRESS.md` and `docs/MANUAL_QA.md` current.

**Step 1: verify before you build.** Nothing in this repository has been run through Gradle (the Kotlin code was compiled and tested with `kotlinc` + JUnit 4 and a real SQLite by the previous author: core 180 + engine 171 tests passing). If you have a normal environment, run
`./gradlew check`, fix every failure (build scripts, detekt, tests, Compose), then run `./gradlew :app:run`, and record what you found and
changed in PROGRESS. If Gradle cannot download dependencies, use `bash scripts/local-check.sh` (setup notes inside) as a safety net and
list clearly what it cannot cover. Do not start new tasks until the existing code builds, or you have documented why it cannot be built
in your environment.

**Step 2: continue the plan** with the order in `docs/HANDOFF.md` section 8 (wire the Round 5 pieces into the shell, then Phase 3 and onward; P6-1 whenever hardware blocks you),
one task at a time, each with tests, a PROGRESS line, and a commit. For anything that needs hardware or an account you do not have (a
phone, a Windows PC at 125–150 % scaling, a second monitor, Store credentials), implement against fakes and add the manual steps to
`docs/MANUAL_QA.md` rather than guessing results.

**Step 3: report.** When you stop, return the repository as a zip **including `.git`**, and write a short plain-language message with:
what you completed (task ids), how each was verified, what is still unverified and why, decisions you had to take, and any questions for
the owner (batched, each with the default you used). Keep the owner's time in mind: they should be able to read it in two minutes.
