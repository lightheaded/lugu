import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Collects every dependency's license at build time and generates the raw resource
    // the licenses screen renders. Applied here because only the app module sees the
    // whole dependency graph.
    alias(libs.plugins.aboutlibraries.android)
}

// Dev-only convenience: prefill the login screen from a gitignored local.properties.
// Keys: lugu.dev.serverUrl, lugu.dev.user, lugu.dev.pass. Never committed.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun devProp(key: String): String = localProps.getProperty(key).orEmpty()

// CI stamps a monotonic build number so every build is a distinct version; local
// builds keep the bare base version. Obtainium compares the installed versionName
// against the version it reads from the release tag, and when the two cannot be
// reconciled it stops detecting versions and reinstalls on every check — so the tag
// CI publishes must be exactly "v$versionBase.$buildNumber".
val versionBase = "0.2.0-alpha01"
val buildNumber: Int? = System.getenv("LUGU_BUILD_NUMBER")?.toIntOrNull()

// Sentry ingest key. Canonical copy is the SOPS-encrypted secrets.enc.yaml; CI passes
// it in, and a local release build can set lugu.sentry.dsn in local.properties. Empty
// is the normal case and means crash reporting cannot start at all — the SDK is never
// initialised without a DSN, which is also what keeps a fork's builds from reporting
// into this project's Sentry.
//
// Note this ends up inside the APK, so it is recoverable by anyone with a build. It is
// kept out of the source to keep the org id out of a public repo, not as an anti-abuse
// measure — that is a rate limit and spike protection on the Sentry side.
val sentryDsn: String = System.getenv("LUGU_SENTRY_DSN")
    ?: localProps.getProperty("lugu.sentry.dsn").orEmpty()

android {
    namespace = "io.github.lightheaded.lugu"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.lightheaded.lugu"
        minSdk = 26
        targetSdk = 37
        versionCode = 2 + (buildNumber ?: 0)
        versionName = buildNumber?.let { "$versionBase.$it" } ?: versionBase
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")

        // The instrumented tests need a device to run on and, for the ones that play
        // something, a server to play from. The server comes from the same gitignored
        // local.properties keys the login screen is prefilled from — see the debug build
        // type below — and a test that cannot find one skips rather than fails, so a CI
        // emulator with no server stays green.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Populated in CI from repository secrets; absent locally.
            val storePath = System.getenv("LUGU_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank() && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = System.getenv("LUGU_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LUGU_KEY_ALIAS")
                keyPassword = System.getenv("LUGU_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "DEV_SERVER_URL", "\"${devProp("lugu.dev.serverUrl")}\"")
            buildConfigField("String", "DEV_USER", "\"${devProp("lugu.dev.user")}\"")
            buildConfigField("String", "DEV_PASS", "\"${devProp("lugu.dev.pass")}\"")
            // What the instrumented playback tests ask the library for. Not a secret and
            // not a credential — a title, which is why it is a separate key: a developer
            // who has a server still has to say what on it is safe to play.
            buildConfigField("String", "TEST_PLAY_QUERY", "\"${devProp("lugu.test.playQuery")}\"")
        }
        release {
            // R8 on, with the keep rules in proguard-rules.pro. Note that a clean build
            // proves only that nothing is missing at compile time: the paths this can
            // break — Room's generated code, Hilt's graph, kotlinx-serialization's
            // reflectively resolved serializers, Media3's service — all fail at runtime
            // and only on a release build, so a device pass is part of the change rather
            // than a follow-up.
            //
            // Stack traces from this build are obfuscated. Sentry's Gradle plugin would
            // upload the mapping file, but it fails the build without an auth token, so
            // the mapping has to reach Sentry another way — see the backlog.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "DEV_SERVER_URL", "\"\"")
            buildConfigField("String", "DEV_USER", "\"\"")
            buildConfigField("String", "DEV_PASS", "\"\"")
            buildConfigField("String", "TEST_PLAY_QUERY", "\"\"")
            if (System.getenv("LUGU_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Roborazzi draws the real thing, so the real resources have to be there.
            // Without this a screenshot test renders a screen with no theme attributes
            // and every baseline is a picture of the failure.
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            // pickFirst rather than exclude: several dependencies bundle the same-named
            // license files, which collide — but Apache-2.0 wants its text distributed
            // with the binary, so one copy stays in the APK.
            pickFirsts += "/META-INF/AL2.0"
            pickFirsts += "/META-INF/LGPL2.1"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:api"))
    implementation(project(":core:db"))
    implementation(project(":core:sync"))
    implementation(project(":core:download"))
    implementation(project(":playback"))
    implementation(project(":feature:library"))
    implementation(project(":feature:player"))
    implementation(project(":feature:settings"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.aboutlibraries.compose.m3)

    // Initialised only after consent; see CrashReporting and the manifest's
    // io.sentry.auto-init=false. MIT licensed, so GPL-compatible.
    implementation(libs.sentry.android)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)

    // Screenshot tests. Roborazzi renders Compose on the JVM through Robolectric's native
    // graphics, so these run on every push without an emulator.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    debugImplementation(libs.compose.ui.test.manifest)

    // Instrumented tests. These are the only tests that exercise the parts of lugu that
    // exist because Android kills processes: resumption after the app is gone, and the
    // media button arriving at a service nothing is bound to.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.media3.session)
    androidTestImplementation(libs.kotlinx.coroutines.guava)
    androidTestImplementation(project(":playback"))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
