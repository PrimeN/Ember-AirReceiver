// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
// Root build file intentionally keeps task wiring minimal.
// Android/Gradle plugin tasks (including `build` and `lint`) are provided by included modules.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    configurations.configureEach {
        resolutionStrategy.force(
            "org.jetbrains.kotlin:kotlin-stdlib:1.9.23",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.23",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.23"
        )
    }
}
