import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.desktop)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
}

// ─── Version: version.txt is the single source of truth (same file BuildInfo is generated from) ───
val appVersion: String = rootProject.file("version.txt").readText().trim().also {
    require(Regex("""\d+\.\d+\.\d+""").matches(it)) {
        "version.txt must contain MAJOR.MINOR.PATCH (for example 1.0.0), but contains '$it'"
    }
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
            // Bundle the full JRE so the installer never hits "Failed to launch JVM".
            // Replaced by an explicit module list in P8-1.
            includeAllModules = true

            targetFormats(
                TargetFormat.Exe,       // Windows — NSIS self-extracting installer
                TargetFormat.Msi,       // Windows — MSI (also used as MSIX source)
                TargetFormat.Dmg,       // macOS — drag-and-drop disk image
                TargetFormat.Pkg,       // macOS — flat package installer
                TargetFormat.Deb,       // Linux — Debian / Ubuntu
                TargetFormat.AppImage   // Linux — universal portable bundle
            )

            // App display name shown in installer, Add/Remove Programs, taskbar, etc.
            packageName    = "UIScope"
            packageVersion = appVersion
            description    = "UIScope: Desktop & Android UI Inspector"
            vendor         = "TBTechs"
            copyright      = "© 2026 TITANICBHAI. MIT License."
            licenseFile.set(rootProject.file("LICENSE"))

            // ── Windows ───────────────────────────────────────────────────────
            // Icon source: src/main/resources/store/tile-71x71.png (1024×1024)
            // Converted to multi-size ICO (16/24/32/48/64/128/256 px).
            // MSIX tile assets (Square71x71Logo, Square150x150Logo, Square300x300Logo)
            // live in src/main/resources/store/ — reference them in the MSIX manifest
            // when wrapping the MSI with the Windows App Packaging Tool.
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))

                // Stable GUID — NEVER change this after first release or upgrades break
                upgradeUuid   = "C7B5A9D2-3F1E-4A8B-9C6D-0E2F7B4A5C3D"

                menuGroup      = "UIScope"
                perUserInstall = true   // install to %LOCALAPPDATA%; no admin needed
                dirChooser     = true   // let user pick install directory in MSI
                shortcut       = true   // create Start-menu shortcut
            }

            // ── macOS ────────────────────────────────────────────────────────
            // Icon source: same tile-71x71.png converted to ICNS via ImageMagick.
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

// ── Windows helper (P3-1) ─────────────────────────────────────────────────────────
// Builds the C# net472 UIA helper with dotnet on Windows only and puts exe+dll on the app
// classpath under helper/, so Main can extract them next to the data folder at start-up.
val buildWindowsHelper by tasks.registering {
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isWindows }
    doLast {
        val csproj = rootProject.file("native/windows-helper/UIScope.Helper.csproj")
        exec {
            commandLine("dotnet", "build", csproj.absolutePath, "-c", "Release")
            workingDir = csproj.parentFile
        }
        val out = layout.buildDirectory.dir("helper").get().asFile
        out.mkdirs()
        copy {
            from(File(csproj.parentFile, "bin/Release/net48"))
            include("UIScope.Helper.exe", "UIScope.Helper.dll", "*.config")
            into(out)
        }
    }
}

afterEvaluate {
    tasks.named("processResources") { dependsOn(buildWindowsHelper) }
}

sourceSets {
    main {
        resources {
            // helper/ lands here via buildWindowsHelper; extracted at run time by Main.
            srcDir(layout.buildDirectory.dir("helper"))
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
