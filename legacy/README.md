# legacy/

The pre-rebuild Kotlin sources of `core`, `engine`, `ui` and `app` (UIScope 1.0.0 as received), kept **for reading only**.

- It is **not part of the Gradle build** (`settings.gradle.kts` includes only `:core :engine :ui :app`).
- New code is written fresh in the four modules and must **never import from `legacy/`**.
- Features that were cut from v1 (iOS, Watch, bookmarks, tray, multi-window, Diff, the Launcher screen, the PowerShell/AppleScript/xdotool PC inspectors, the keystroke hook) were deleted, not moved. Git history has them.
- Task P8-1 deletes this folder.
