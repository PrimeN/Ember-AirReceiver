// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
/**
 * Ember AirReceiver JVM Test Runner
 *
 * Standalone Gradle project that runs all JVM unit tests WITHOUT the Android
 * Gradle Plugin (AGP). This is necessary in environments where maven.google.com
 * is not accessible (e.g., restricted CI sandboxes).
 *
 * Usage:
 *   cd test-runner && gradle test
 *
 * All dependencies are resolved from Maven Central only.
 *
 * Design: production + test sources are compiled together as one Kotlin module
 * so that `internal` members in production code are accessible from test code
 * without any friend-module workarounds.  The `test` task is configured to
 * scan the main output directory for @Test classes.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        kotlin {
            // Production sources + test sources in one compilation unit
            srcDirs(
                "../app/src/main/kotlin",
                "../app/src/firetv/kotlin",
                "../app/src/test/kotlin",
                "src/stubs"               // stub files shadow Android-heavy excluded files
            )
            // Exclude files that depend on AndroidX (DataStore, Leanback, NotificationCompat, R)
            // or the full Android Activity lifecycle
            exclude(
                "**/ui/**",
                "**/AirReceiverActivity.kt",
                "**/AirReceiverApplication.kt",
                "**/settings/SettingsRepository.kt",
                "**/service/AirReceiverService.kt",
                "**/service/BootReceiver.kt",
                // Split out of AirReceiverService.kt: the foreground notification needs
                // NotificationCompat + R, the supervisor needs the notification manager.
                "**/service/ServiceNotificationManager.kt",
                "**/service/ReceiverSupervisor.kt",
                "**/AirReceiverActivityTest.kt",
                // This test exercises the actual MediaCodec submission path, not the JVM stub.
                "**/airplay/mirror/VideoSubmissionTest.kt",
                // MdnsServiceTest mocks NsdManager whose static initializer calls
                // VMRuntime.newUnpaddedArray (JNI) — not available in the JVM.
                // This test must run as an Android instrumented test on a real device.
                "**/MdnsServiceTest.kt",
                // ServiceControllerTest triggers android.os.Build.<clinit> which calls
                // SystemProperties.native_get (JNI) — not available in the JVM.
                "**/ServiceControllerTest.kt",
                // NetworkUtilsTest mocks ContentResolver which triggers android.os.Build
                // via ContentResolver.<clinit> → SystemProperties.native_get (JNI).
                "**/NetworkUtilsTest.kt",
                // VideoDecoder is shadowed by src/stubs/VideoDecoder.kt which has no
                // MediaCodec/Surface dependencies but exposes the companion-object
                // members (parseSpsResolution, SpsBitReader) needed by VideoDecoderSpsTest.
                "**/airplay/mirror/VideoDecoder.kt"
            )
        }
    }
}

// The standalone JVM runner uses the same Timber classes as the Android app.
val timberAar by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val timberClassesDirectory = layout.buildDirectory.dir("timber-classes")
val extractTimberClasses by tasks.registering(Sync::class) {
    from(timberAar.elements.map { zipTree(it.single().asFile) }) {
        include("classes.jar")
    }
    into(timberClassesDirectory)
}

dependencies {
    // ── Android API stubs ──────────────────────────────────────────────────────
    // Robolectric's android-all JAR provides all Android class definitions
    // (MediaCodec, AudioTrack, NsdManager, etc.) for compile-time and runtime.
    compileOnly("org.robolectric:android-all:14-robolectric-10818077")
    runtimeOnly("org.robolectric:android-all:14-robolectric-10818077")

    // ── Kotlin ─────────────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.23")

    // ── Coroutines ─────────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // ── Bouncy Castle (AES-128-CTR for AudioPlayer) ────────────────────────────
    // bcprov-jdk15on was renamed to bcprov-jdk18on starting with version 1.71
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // ── dd-plist (binary plist encode/decode for the AirPlay 2 handshake) ──────
    // PlistCodec (compiled here from app/src/main) imports com.dd.plist.*; on Maven Central.
    implementation("com.googlecode.plist:dd-plist:1.28")

    timberAar(libs.timber.map { "${it.module}:${it.versionConstraint.requiredVersion}@aar" })
    implementation(files(timberClassesDirectory.map { it.file("classes.jar") }).builtBy(extractTimberClasses))

    // ── Test dependencies (in main scope because sources are merged) ───────────
    implementation("junit:junit:4.13.2")
    implementation("io.mockk:mockk:1.13.10")
}

tasks.test {
    // The test task must scan the main output because test sources are in main
    testClassesDirs = sourceSets.main.get().output.classesDirs
    classpath = sourceSets.main.get().runtimeClasspath
    useJUnit()
    // Allow dynamic agent loading required by MockK's bytecode instrumentation
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
