package io.github.lightheaded.lugu

/**
 * Where the instrumented tests get a server, if there is one.
 *
 * Nothing is embedded here and nothing ever will be. The values come from the same
 * gitignored `local.properties` that prefills the login screen on a debug build, reach the
 * APK as `BuildConfig` fields, and are empty in every build made without that file — which
 * includes every build CI makes.
 *
 * Tests that need a server call [assumeConfigured] and are skipped when there is none, so
 * an emulator run with no server is green rather than red. A red run that nobody can fix
 * teaches everyone to ignore the colour.
 */
internal object TestServerConfig {

    val url: String get() = BuildConfig.DEV_SERVER_URL

    val username: String get() = BuildConfig.DEV_USER

    val password: String get() = BuildConfig.DEV_PASS

    /**
     * What the playback tests ask the library for.
     *
     * Separate from the credentials because having a server is not the same as consenting
     * to have something on it played by a test: whoever sets this chooses the title.
     */
    val playQuery: String get() = BuildConfig.TEST_PLAY_QUERY

    val hasServer: Boolean
        get() = url.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    val canPlay: Boolean get() = hasServer && playQuery.isNotBlank()

    /** The reason a skipped test gives, written for whoever reads the run and wonders. */
    const val NO_SERVER =
        "No test server. Set lugu.dev.serverUrl, lugu.dev.user and lugu.dev.pass in " +
            "local.properties to run this."

    const val NO_QUERY =
        "No test title. Set lugu.test.playQuery in local.properties to something on the " +
            "server that is safe for a test to play."
}
