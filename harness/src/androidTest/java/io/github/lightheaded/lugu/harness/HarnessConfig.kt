package io.github.lightheaded.lugu.harness

/**
 * What the harness is allowed to do, and what it is missing when it is not.
 *
 * The same arrangement as `TestServerConfig` in `:app`, and the same reason for it: a test
 * that cannot run must skip rather than fail. A red run that nobody can fix teaches
 * everyone to ignore the colour.
 *
 * What is different here is how little crosses the boundary. The harness never learns the
 * server address, the username or the password — only whether all three are set. lugu
 * signs itself in from its own `BuildConfig`, and the harness taps the button that is
 * already filled in. There is nothing in this process to leak.
 */
internal object HarnessConfig {

    /** Whether the *app* has a server configured. Not what it is. */
    val hasServer: Boolean get() = BuildConfig.HAS_SERVER

    /**
     * The title the playback tests ask lugu to play.
     *
     * Separate from the credentials on purpose: having a server is not the same as agreeing
     * that a test may play something on it, and whoever sets `lugu.test.playQuery` chooses
     * what.
     */
    val playQuery: String get() = BuildConfig.PLAY_QUERY

    val canPlay: Boolean get() = hasServer && playQuery.isNotBlank()

    const val NO_LUGU =
        "lugu is not installed. This module tests another app, so that app has to be on " +
            "the device: ./gradlew :app:installDebug"

    const val NO_SERVER =
        "No test server. Set lugu.dev.serverUrl, lugu.dev.user and lugu.dev.pass in " +
            "local.properties to run this."

    const val NO_QUERY =
        "No test title. Set lugu.test.playQuery in local.properties to something on the " +
            "server that is safe for a test to play."
}
