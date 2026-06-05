import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.desktop)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(19)
}

compose.desktop {
    application {
        mainClass = "com.titanicbhai.uiscope.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "UIScope"
            packageVersion = "1.0.0"
            description = "See what your UI is made of."
            vendor = "TITANICBHAI"
            copyright = "© 2026 TITANICBHAI. MIT License."
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.swing)
}
