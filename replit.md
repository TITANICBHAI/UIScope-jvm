# UIScope

Native desktop UI inspector — *"See what your UI is made of."* Kotlin Compose Multiplatform app that live-inspects UI element trees for any app on the same PC (Windows/macOS/Linux via accessibility APIs) or any connected Android device (via ADB). No accounts, no telemetry, fully offline.

## Run & Operate

- **Run the app (VNC):** Start the `Run UIScope (VNC)` workflow — opens the desktop window in the VNC panel
- **Push to GitHub:** Start the `Push to UIScope-jvm` workflow — requires `GITHUB_PERSONAL_ACCESS_TOKEN` secret
- **Build only:** `cd uiscope && gradle :app:jar --no-daemon`
- **Package native installer:** `cd uiscope && gradle :app:packageDeb` (Linux), `:app:packageMsi` (Windows), `:app:packageDmg` (macOS)

## Stack

- **Language:** Kotlin 2.0.0, JVM target 19 (GraalVM CE 22.3.1)
- **UI:** Jetpack Compose Multiplatform Desktop 1.6.11
- **PC inspection:** Windows UIAutomation + macOS AX API + Linux AT-SPI2, all via JNA 5.14.0
- **Android inspection:** ADB via ProcessBuilder — no on-device agent needed
- **Storage:** SQLDelight 2.0.2 (SQLite) at `~/.uiscope/uiscope.db`
- **Global hotkeys:** jnativehook 2.2.2
- **Build:** Gradle 8.x, Compose JB packaging (produces .exe/.msi/.dmg/.deb/.AppImage)

## Where things live

```
uiscope/
├── app/       → Main.kt (entry point, window management, tray, menu bar, multi-window)
├── core/      → models, SQLDelight schema, export, codegen, repositories
├── engine/    → ADB engine, PC accessibility engines (JNA), global hotkey manager
└── ui/        → All Compose screens (Launcher, Inspector, History, Settings, Diff, Watch)

scripts/
├── run-uiscope.sh      → VNC launcher (locates GraalVM, launches Gradle :app:run)
├── push-to-github.sh   → Pushes workspace to GitHub repo UIScope-jvm
└── post-merge.sh       → pnpm install after merges (for Node.js workspace)
```

- **DB schema source of truth:** `uiscope/core/src/main/sqldelight/com/titanicbhai/uiscope/db/`
- **Theme/colours:** `uiscope/ui/src/main/kotlin/com/titanicbhai/uiscope/theme/Color.kt`
- **Gradle version catalog:** `uiscope/gradle/libs.versions.toml`

## Architecture decisions

- **Fresh KMP Desktop project** — the original NodeSpy Android repo is the data-model reference only; no Android Gradle/Compose APIs were ported.
- **ADB via ProcessBuilder** — no on-device agent, no Appium server. UIAutomator dump piped direct to stdout (`adb exec-out uiautomator dump /dev/tty`).
- **JNA for all native OS APIs** — no C++ code; Windows UIAutomation.dll, macOS ApplicationServices.framework, Linux AT-SPI2 all called via JNA from pure Kotlin/JVM.
- **SQLDelight over Room** — multiplatform SQLite, no Android runtime dependency.
- **Software renderer forced on Linux** (`-Dskiko.renderApi=SOFTWARE` in run script) — VNC/headless environments don't provide an OpenGL context; Skiko's software fallback is stable.

## Product

UIScope has four modes accessible from a single install:

| Mode | What it does |
|---|---|
| **PC Inspector** | Hover any window → Alt+Shift+P pick lock → tree + properties + code gen |
| **Android Inspector** | Connect via USB or wireless ADB → live screencap + bidirectional node selection |
| **Diff Mode** | Pick two saved sessions → see added / removed / changed elements side by side |
| **Watch Mode** | Define a condition (element appears/disappears/text matches) → monitor on device |

Session history, bookmarks, export (JSON/XML/outline), and code generation (AHK, pywinauto, C#, PowerShell, uiautomator2, Appium, Maestro, XPath) are available in both PC and Android modes.

Multi-window: File → New Window (Ctrl+Shift+N) opens a fully independent second inspector window.

## User preferences

_Populate as you build — explicit user instructions worth remembering across sessions._

## Gotchas

- **Java must be in PATH** — the `java-graalvm22.3` Replit module must be installed (currently is). Without it, Gradle can't start.
- **`gradle.properties` hardcodes the GraalVM nix store path** — `org.gradle.java.installations.paths=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1`. Don't change this.
- **VNC only** — this is a native desktop app; the browser preview pane shows nothing. Use the VNC panel to see the running app.
- **First run downloads ~400 MB** of Compose/Kotlin Gradle dependencies; subsequent starts are fast (UP-TO-DATE).
- **SQLDelight `upsertSetting`** — use positional args, not named args (`value_` is the generated param name).
- **Smart casts across modules** — extract to a local `val` before using in cross-module lambdas.
