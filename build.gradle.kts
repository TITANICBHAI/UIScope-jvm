import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.desktop) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        // ── Tests: JUnit Platform (kotlin-test picks the JUnit 5 binding automatically) ──
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            maxHeapSize = "1g"
            testLogging {
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }

        // ── detekt: only the two rules the plan requires (no empty catch, no swallowed exceptions).
        // Strict for all code in the build. The old code lives in legacy/, outside the build, so no baseline is needed.
        apply(plugin = "io.gitlab.arturbosch.detekt")
        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = false
            allRules = false
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            source.setFrom("src/main/kotlin", "src/test/kotlin", "src/testFixtures/kotlin")
        }
        // detekt 1.23.x must analyse with the Kotlin version it was built for (documented workaround for Kotlin 2.x projects).
        configurations.matching { it.name == "detekt" }.all {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.kotlin") {
                    useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
                }
            }
        }
    }
}
