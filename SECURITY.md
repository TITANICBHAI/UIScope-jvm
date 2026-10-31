# Security Policy

## Reporting a vulnerability

Please open a GitHub issue with the label `security` describing the problem, or include steps to reproduce.
UIScope is developed in the open; anything you can reproduce from the repository is fair game to describe
publicly. For anything you would rather not post, mention it in an issue without details and a maintainer will
follow up.

## What UIScope can and cannot do (by design)

- It starts processes only with argument lists, never through a host shell — enforced by a build-failing
  guardrail (`docs/SECURITY_CHECKLIST.md`).
- The only network actions are the opt-in update check and the user-clicked ADB download, both through one
  gateway (`docs/network.md`); any third path fails the build.
- `.uiscope` bundle imports are size-capped and validated; zip entries never become file paths
  (`docs/SECURITY_CHECKLIST.md`).
- The user-supplied `adb` path is executed directly as `argv[0]`, never interpreted by a shell.
- Writes go only to the data folder, the OS temp dir, or a path you picked.

The full list, with the enforcement evidence for every line: [docs/SECURITY_CHECKLIST.md](docs/SECURITY_CHECKLIST.md).

## Supported versions

The latest tagged release. The project is pre-1.0; fixes land on `main` and ship with the next tag.
