# UIScope

> *See what your UI is made of.*

A Kotlin Compose Multiplatform Desktop app for live inspection of UI element trees — both native PC apps and Android devices via ADB — in one clean, fast, offline tool.

## Quick Start

### Requirements

- JDK 17+ (JDK 19 recommended; download from [adoptium.net](https://adoptium.net))
- Internet access for first build (Gradle downloads ~400 MB of dependencies once)
- ADB (optional, for Android mode — UIScope can download it for you)

### Build & Run

```bash
cd uiscope

# Run in development (downloads deps on first run, ~2–5 min)
./gradlew :app:run

# Package a native installer
./gradlew :app:packageDmg        # macOS
./gradlew :app:packageMsi        # Windows
./gradlew :app:packageDeb        # Debian/Ubuntu
./gradlew :app:packageAppImage   # Linux AppImage
```

### Windows (no bash)

```bat
gradlew.bat :app:run
```

## Project Structure

```
uiscope/
├── core/          — Data models (ElementNode, Session, Bookmark, …)
│                    SQLDelight schema (sessions, bookmarks, app_settings)
│                    Repositories (SessionRepository, BookmarkRepository, SettingsRepository)
├── engine/        — ADB device detection and connection (AdbManager)
├── ui/            — Compose Desktop screens
│   ├── launcher/  — Mode picker (LauncherScreen)
│   ├── inspector/ — 3-panel inspector shell (InspectorScreen, TreePanel, VisualCanvas, PropertiesPanel)
│   ├── onboarding/— First-run onboarding checklist (OnboardingScreen)
│   └── theme/     — Material 3 dark/light theme (UiScopeTheme)
└── app/           — Entry point (Main.kt), Compose Desktop packaging config
```

## Tech Stack

| Layer         | Technology                                     |
|---------------|------------------------------------------------|
| UI            | Kotlin Compose Multiplatform Desktop 1.6.x     |
| Persistence   | SQLDelight 2.x + SQLite (JdbcSqliteDriver)     |
| Android engine| ADB via ProcessBuilder                         |
| Build         | Gradle 8.x + Compose Desktop packaging plugin  |
| Language      | Kotlin 2.0                                     |

## Phase Roadmap

| Phase | What's built                                           | Status     |
|-------|--------------------------------------------------------|------------|
| 1     | Skeleton: project structure, models, DB, UI shell, ADB detect | ✅ Done |
| 2     | Android mode live: UIAutomator tree, screencap, code gen | Planned  |
| 3     | PC mode live: Windows/macOS/Linux UI Automation engine | Planned    |
| 4     | Polish & ship: diff mode, watch mode, CI packaging     | Planned    |

## Data Storage

All data is stored locally in `~/.uiscope/uiscope.db` (SQLite). Nothing leaves the machine.

## License

MIT — fully open source.
