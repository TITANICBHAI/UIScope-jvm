---
name: UIScope build quirks
description: Known gotchas for building the UIScope Kotlin Compose Multiplatform desktop app on Replit
---

**Why:** These caused compile failures in a prior session and are non-obvious from the code.

**How to apply:** Always verify these before running the app or if the build fails.

1. `gradle.properties` MUST contain `org.gradle.java.installations.paths=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1` — without this the Kotlin toolchain detection fails.

2. `ui/build.gradle.kts` dependencies must include `compose.material3`, `compose.materialIconsExtended`, `compose.foundation` — they are not transitively available.

3. `SettingsRepository.set()` uses positional args `queries.upsertSetting(key, value)` — NOT named args (SQLDelight renames `value` → `value_`).

4. Kotlin smart casts across module boundaries (e.g. `selectedNode?.let { it }`) require extracting to a local `val` before using in cross-module lambdas.

5. PcCodegen single-quoted AHK string literals need `"""..."""` triple-quote syntax — single-quoted Kotlin char literals break.

6. VNC workflow (`Run UIScope (VNC)`) is the only way to run the app in this environment; screenshot tool cannot capture VNC output.

7. Gradle may show `UP-TO-DATE` for modules even after source edits — check compiled class files in `build/classes/kotlin/main/` to confirm new files actually compiled.
