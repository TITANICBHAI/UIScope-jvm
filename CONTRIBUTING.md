# Contributing

Thanks for looking at UIScope.

## The ground rules

1. **`plan.md` is the authority** for what gets built. Ideas outside the plan go to `docs/proposals.md` first.
2. **`./gradlew check` must pass** — that includes detekt (no empty or swallowed catch blocks) and the
   guardrail tests, which fail the build for forbidden patterns (host shells, `ProcessBuilder` outside
   `engine/process`, HTTP outside the gateway, AWT pointer input, keyboard hooks). See `docs/guardrails.md`.
3. **No process, file, network or database work on the UI thread** — the state holders own that off the main
   thread, tested.
4. **Never log element text, window titles, tree contents or device serials.** The log rules are deliberate.
5. **One commit per task, honest verification.** CI runs `check` on Windows and Linux; claims about behaviour
   need a test or a `needs manual verification` note in `docs/MANUAL_QA.md`.
6. Keep `docs/PROGRESS.md` and `docs/MANUAL_QA.md` current with what you changed.

## Building

- JDK 21 (Temurin), `./gradlew run` to launch, `./gradlew check` to verify.
- The Windows helper (native/windows-helper) builds automatically with the MSBuild that ships with Windows;
  no .NET SDK, no NuGet.

## Reporting bugs

Please use the bug issue template and paste the *Copy diagnostics* output from Settings — it contains no
personal data by design.
