plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    test {
        // Android dump fixtures live in core's test resources and are shared with engine tests (plan §3.1).
        resources.srcDir(rootProject.file("core/src/test/resources"))
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
