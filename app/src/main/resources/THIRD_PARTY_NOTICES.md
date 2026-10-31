# THIRD-PARTY NOTICES

UIScope is MIT-licensed. It bundles and depends on the following third-party software at runtime; all licences
are compatible with MIT distribution. This file is maintained against `gradle/libs.versions.toml` (the single
dependency catalog) and ships inside the app; the About screen opens it.

| Component | Version | Licence | Use |
|---|---|---|---|
| Kotlin stdlib | 2.0.0 | Apache License 2.0 | Language runtime |
| kotlinx.coroutines (core, swing) | 1.8.1 | Apache License 2.0 | Concurrency; AWT Main dispatcher |
| Compose Multiplatform Desktop (incl. Skiko) | 1.7.3 | Apache License 2.0 | UI framework and renderer |
| SQLite JDBC (xerial) | 3.46.1.3 | Apache License 2.0 | Snapshot database (native library unpacked at runtime) |
| Java Native Access (JNA, jna-platform) | 5.14.0 | Apache License 2.0 **or** LGPL 2.1 (dual; Apache applies) | Windows platform bindings for the app's own Win32 calls |
| SnakeYAML | 2.2 | Apache License 2.0 | Configuration parsing |
| OpenJDK (Temurin, bundled by jpackage) | 21 | GPLv2 with the Classpath Exception | Java runtime |

Build- and test-time only (not shipped): JUnit 5 / vintage engine, detekt, the Gradle toolchain.

Windows UI Automation access is performed by the bundled `UIScope.Helper.exe`, a .NET Framework 4.8 component
written for this project and licensed under the same MIT licence. It uses only OS-shipped libraries.

Android, Google Play and the Google USB driver are trademarks of Google LLC. Windows, Visual Studio and related
marks are trademarks of Microsoft Corporation. UIScope is not affiliated with, endorsed by or sponsored by either
company.
