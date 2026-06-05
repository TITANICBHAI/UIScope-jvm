import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.desktop)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
}

// ─── Resolve version from env (injected by CI) or fall back to file/default ───
val appVersion: String = System.getenv("APP_VERSION")
    ?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) }
    ?: run {
        val vFile = rootProject.file("version.txt")
        if (vFile.exists()) vFile.readText().trim() else "1.0.0"
    }

compose.desktop {
    application {
        mainClass = "com.titanicbhai.uiscope.MainKt"

        jvmArgs(
            "-Xms64m",
            "-Xmx512m",
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "-Dskiko.renderApi=SOFTWARE"
        )

        nativeDistributions {
            targetFormats(
                TargetFormat.Exe,       // Windows — NSIS self-extracting installer
                TargetFormat.Msi,       // Windows — MSI (also used as MSIX source)
                TargetFormat.Dmg,       // macOS — drag-and-drop disk image
                TargetFormat.Pkg,       // macOS — flat package installer
                TargetFormat.Deb,       // Linux — Debian / Ubuntu
                TargetFormat.AppImage   // Linux — universal portable bundle
            )

            packageName    = "UIScope"
            packageVersion = appVersion
            description    = "See what your UI is made of. " +
                             "Live inspector for Android device UI trees and Windows/macOS/Linux app accessibility trees."
            vendor         = "TITANICBHAI"
            copyright      = "© 2026 TITANICBHAI. MIT License."
            licenseFile.set(rootProject.file("LICENSE"))

            // ── Windows ───────────────────────────────────────────────────────
            windows {
                // Drop icon.ico into src/main/resources/ (see DISTRIBUTION.md)
                iconFile.set(project.file("src/main/resources/icon.ico"))

                // Stable GUID — NEVER change this after first release or upgrades break
                upgradeUuid   = "C7B5A9D2-3F1E-4A8B-9C6D-0E2F7B4A5C3D"

                menuGroup     = "UIScope"
                perUserInstall = true   // install to %LOCALAPPDATA%; no admin needed
                dirChooser    = true    // let user pick install directory in MSI
                shortcut      = true    // create Start-menu shortcut
            }

            // ── macOS ────────────────────────────────────────────────────────
            macOS {
                iconFile.set(project.file("src/main/resources/icon.icns"))
                bundleID      = "com.titanicbhai.uiscope"
                appStore      = false   // flip to true + add entitlements for MAS
                signing {
                    // Set APPLE_SIGNING_IDENTITY and APPLE_NOTARIZATION_* in GitHub secrets
                    sign.set(System.getenv("APPLE_SIGNING_IDENTITY") != null)
                    identity.set(System.getenv("APPLE_SIGNING_IDENTITY") ?: "")
                }
                notarization {
                    appleID.set(System.getenv("APPLE_ID") ?: "")
                    password.set(System.getenv("APPLE_APP_PASSWORD") ?: "")
                    teamID.set(System.getenv("APPLE_TEAM_ID") ?: "")
                }
            }

            // ── Linux ─────────────────────────────────────────────────────────
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
                menuGroup     = "Development"
                shortcut      = true
                appRelease    = "1"
                appCategory   = "Development"
                debMaintainer = "uiscope@titanicbhai.dev"
                rpmLicenseType = "MIT"
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.foundation)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.swing)
}
