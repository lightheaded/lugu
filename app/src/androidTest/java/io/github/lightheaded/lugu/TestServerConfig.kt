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

    /**
     * The series the next-in-series test walks.
     *
     * A second consent rather than a second use of [playQuery]. That test plays one volume
     * to its end and lets the next one begin, so it moves the position of two books rather
     * than one, and whoever has a server says which series may be treated that way.
     *
     * The series needs at least two volumes with numbers on them. `scripts/seed-test-server.sh`
     * builds one.
     */
    val seriesQuery: String get() = BuildConfig.TEST_SERIES_QUERY

    val canPlay: Boolean get() = hasServer && playQuery.isNotBlank()

    /** The reason a skipped test gives, written for whoever reads the run and wonders. */
    const val NO_SERVER =
        "No test server. Set lugu.dev.serverUrl, lugu.dev.user and lugu.dev.pass in " +
            "local.properties to run this."

    const val NO_QUERY =
        "No test title. Set lugu.test.playQuery in local.properties to something on the " +
            "server that is safe for a test to play."

    /**
     * Said as a failure, never as a skip.
     *
     * The next-in-series check exists because the join it watches had never run anywhere,
     * and a missing fixture is exactly how it stayed that way. So the test fails here and
     * names what is absent, rather than skipping and reporting green.
     */
    const val NO_SERIES =
        "No test series. Set lugu.test.seriesQuery in local.properties to a series on the " +
            "server with at least two numbered volumes, or run scripts/seed-test-server.sh, " +
            "which builds one."
}
