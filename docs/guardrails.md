# Guardrails (P3-9)

`GuardrailsTest` (in `engine/src/test`, so it runs in `./gradlew check`) scans the repository for patterns that must not come back.
It reads `src/main` of `core`, `engine`, `ui` and `app`, the `build.gradle.kts` files and `gradle/libs.versions.toml`. **`legacy/` and
`build/` are never scanned**, and test code is not scanned (tests may start processes to test the process runner).
The scanner is `engine/src/test/.../guardrails/Guardrails.kt`; to change a rule, change it there and add a test line in `GuardrailsTest`.

Comments are always ignored. Rules marked *code* also ignore the contents of string literals (so generated code text such as a Java
template containing `new URL(` is fine); rules marked *code and strings* look inside string literals too.

| Rule id | Forbidden | Looks in | Allowed only in | Why |
|---|---|---|---|---|
| `process-builder` | `ProcessBuilder`, `Runtime.getRuntime().exec` | code | `engine/.../process/` | One place starts processes, with a timeout and kill-on-cancel (B10). |
| `shell-and-automation-tools` | `powershell.exe`, `"powershell"`, `"pwsh"`, `osascript`, `xdotool`, `pyatspi`, `ExecutionPolicy` | code and strings | `core/.../codegen/` (the text of generated PowerShell snippets, which tells the user to run `powershell.exe -STA ...`) | Windows inspection goes through the UI Automation helper (B2); no AppleScript/xdotool/AT-SPI scraping (M8). Generating PowerShell *text* for the user is not affected. |
| `global-input-hooks` | `jnativehook` | code, strings, build scripts, version catalog | nowhere | The pick hotkey uses `RegisterHotKey` inside the helper; no global hooks. |
| `awt-pointer-input` | `MouseInfo`, `java.awt.Robot` | code | nowhere | AWT sees only the primary monitor and breaks under DPI scaling (B8). |
| `network-outside-gateway` | `URL(`, `HttpClient`, `openConnection`, `HttpURLConnection` | code | `NetworkGateway.kt`, `JdkNetworkGateway.kt` | All HTTP in one place, so proxy, TLS and timeouts are decided once (P7-6). |

## When a rule fires

The failure message names the rule, file and line. Do not add an exception for convenience: move the code into the allowed place or
use the port that wraps it (`ProcessRunner`/`ManagedProcess`, `NetworkGateway`). A genuinely new capability needs an entry in
`docs/proposals.md` first.

## Not covered by this scan

Gradle's own `check` wiring for this test was never run (see `docs/PROGRESS.md`). The rule "no `sh -c` / `cmd /c`" from P7-3 is a
manual review item until a rule for it is added.
