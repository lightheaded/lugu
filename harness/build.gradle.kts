import java.util.Properties

/*
 * The second process.
 *
 * Everything else that runs on a device runs inside lugu, which is why nothing else can
 * kill lugu: instrumentation is loaded into the process of the package it targets, so
 * `am force-stop` on that package takes the test runner with it. This module is an
 * application of its own, with its own application id, and its instrumented tests target
 * *it* rather than lugu. The runner therefore lives in a process the kill does not touch,
 * and can watch what happens to lugu from outside.
 *
 * It deliberately depends on nothing of lugu's — not `:app`, not `:playback`, not even a
 * shared constant. Every conversation it has with lugu goes through a surface that is
 * already public: the documented automation broadcasts, a media button, and
 * `dumpsys media_session`. A harness that imported the code under test could pass while
 * the thing a headset does still failed.
 */
plugins {
    alias(libs.plugins.android.application)
}

// The same gitignored local.properties the app module reads. What crosses into this
// module is deliberately less than what the app takes: a boolean saying whether a server
// exists at all, and the title the playback tests are allowed to play. The address, the
// username and the password never reach here — lugu signs itself in from its own
// BuildConfig, and the harness only taps the button.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Same precedence as :app — the environment wins, so CI's seeded container reaches here
// too. Without this the harness read local.properties alone, which a runner does not have,
// and its two playback tests skipped on every CI run: green, and testing nothing. That is
// the exact failure the seeded server was added to end, so it is not repeated here.
fun devProp(key: String, env: String): String =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(key).orEmpty()

fun quoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val hasServer = listOf(
    "lugu.dev.serverUrl" to "LUGU_DEV_SERVER_URL",
    "lugu.dev.user" to "LUGU_DEV_USER",
    "lugu.dev.pass" to "LUGU_DEV_PASS",
).all { (key, env) -> devProp(key, env).isNotBlank() }

android {
    namespace = "io.github.lightheaded.lugu.harness"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.lightheaded.lugu.harness"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1"

        // Self-instrumenting: the runner's target is this module, not lugu. That one fact
        // is what the whole module exists for.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "HAS_SERVER", hasServer.toString())
        buildConfigField("String", "PLAY_QUERY", quoted(devProp("lugu.test.playQuery", "LUGU_TEST_PLAY_QUERY")))
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/*
 * The app under test is not a dependency of this module, so nothing would otherwise put it
 * on the device: `connectedDebugAndroidTest` installs the module it belongs to, and this
 * module is the harness. Without this the tests here would install, run and skip on every
 * CI emulator, reporting green while testing nothing.
 *
 * Declared here rather than in the workflow on purpose. `.github/workflows/ci.yml` asks the
 * whole build for `connectedDebugAndroidTest` precisely so that a module which grows tests
 * later is picked up without anyone remembering to edit it; a module that needs another
 * module installed should say so in the same way.
 */
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(":app:installDebug")

    /*
     * And never at the same time as lugu's own instrumented tests. Gradle builds modules in
     * parallel — `org.gradle.parallel=true` — and the two suites share one device: a
     * `force-stop` issued here while `LaunchSmokeTest` is halfway through would kill that
     * test's process and report it as a failure of the app, which is the worst kind of
     * flake, because it is a real failure of something that was not broken.
     */
    mustRunAfter(":app:connectedDebugAndroidTest")
}

/*
 * And the install has to come after those tests, not before them.
 *
 * AGP uninstalls both APKs when a connected-test run finishes. So in a whole-build
 * `connectedDebugAndroidTest`, `:app:installDebug` would run first, `:app`'s own tests would
 * run and then remove lugu, and every test here would skip for want of an app to test —
 * green, and having tested nothing at all. That is precisely the failure the skip-when-
 * unconfigured rule is supposed to make visible rather than hide, so the ordering is stated
 * instead of trusted.
 *
 * Declared from here rather than in `:app`, because it is this module's requirement.
 * `evaluationDependsOn` is what makes it safe to reach for the task: without it the rule
 * would depend on `:app` happening to be configured first, which is true today only because
 * of the order of the `include` lines in settings.gradle.kts.
 */
evaluationDependsOn(":app")
project(":app").tasks.named("installDebug").configure {
    mustRunAfter(":app:connectedDebugAndroidTest")
}

dependencies {
    // The dumpsys parser is held to a fixture on the JVM, so a format change is caught by
    // `./gradlew build` on every push rather than by a device run nobody has a server for.
    testImplementation(libs.junit)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.truth)
}
