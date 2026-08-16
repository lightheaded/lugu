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

    /*
     * A module with no `src/androidTest` still gets an instrumented-test APK built,
     * installed and started. It contains no tests — but it also contains no test runner,
     * because nothing pulled androidx.test.runner onto its classpath, so the run dies with
     * `ClassNotFoundException: androidx.test.runner.AndroidJUnitRunner` and reports itself
     * as "Instrumentation run failed due to Process crashed". An empty module failing
     * loudly on an emulator is noise that hides a real failure.
     *
     * The fix belongs here rather than in the CI workflow. Naming the two modules that do
     * have instrumented tests in the `connectedDebugAndroidTest` command would work today
     * and quietly stop testing the third one somebody adds next year. Asking for every
     * module and building a test APK only where tests exist keeps that discovery automatic.
     */
    plugins.withId("com.android.base") {
        if (!file("src/androidTest").isDirectory) {
            extensions.configure<com.android.build.api.variant.AndroidComponentsExtension<*, *, *>>(
                "androidComponents",
            ) {
                beforeVariants { variant ->
                    (variant as? com.android.build.api.variant.HasDeviceTestsBuilder)
                        ?.deviceTests
                        ?.values
                        ?.forEach { it.enable = false }
                }
            }
        }
    }
}
