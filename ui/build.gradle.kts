plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.desktop)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.foundation)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.swing)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(compose.desktop.uiTestJUnit4)
    // Compose UI test rules are JUnit 4 rules; the vintage engine runs them on the JUnit 5 platform alongside the
    // kotlin.test (Jupiter) suites. Planned for in Round 4 (docs/PROGRESS.md, "Notes for later tasks").
    testRuntimeOnly(libs.junit.vintage.engine)
}
