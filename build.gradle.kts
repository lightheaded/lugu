plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.aboutlibraries.android) apply false
}

/*
 * Screenshot tests run as ordinary unit tests, and check by default.
 *
 * Roborazzi ships a Gradle plugin that adds recordRoborazzi/verifyRoborazzi tasks, but
 * 1.46.1 of it reaches for AGP's `TestedExtension`, which AGP 9 removed — applying it
 * fails configuration outright. All the plugin does is set these two system properties on
 * the test task, so they are set here instead and the plugin is left out.
 *
 * Verifying is the default rather than something CI opts into. Without it a plain
 * `./gradlew build` would silently rewrite every baseline, which turns a regression into
 * a diff nobody reads. Re-record after an intentional change with
 * `./gradlew testDebugUnitTest -Proborazzi.record` — see docs/qa/screenshots.md.
 */
subprojects {
    tasks.withType<Test>().configureEach {
        val recording = providers.gradleProperty("roborazzi.record").isPresent
        systemProperty("roborazzi.test.record", recording)
        systemProperty("roborazzi.test.verify", !recording)
    }
}
