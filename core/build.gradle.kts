plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)
}

sqldelight {
    databases {
        create("UiScopeDatabase") {
            packageName.set("com.titanicbhai.uiscope.db")
            srcDirs("src/main/sqldelight")
        }
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.sqldelight.driver.sqlite)
    implementation(libs.sqldelight.coroutines)
    implementation(libs.coroutines.core)
}
