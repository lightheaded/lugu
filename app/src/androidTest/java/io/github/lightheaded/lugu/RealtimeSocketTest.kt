package io.github.lightheaded.lugu

import android.content.Context
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import io.github.lightheaded.lugu.core.sync.Realtime
import io.github.lightheaded.lugu.core.sync.RealtimeEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first time lugu's Socket.IO client meets a live Audiobookshelf server.
 *
 * `Realtime` was written against the server source, and its unit tests cover the event
 * names, the payload parsing, the backoff and the URL split — all without a socket. Three
 * things no unit test could reach, and which the backlog row "Socket.IO deltas are written
 * but unproven" lists: that engine.io-client works on OkHttp 5 at runtime, that the
 * handshake succeeds and `init` arrives, and that `/socket.io` is the right path. Each test
 * here watches the app's own singleton, the one `LuguApplication` started, so what is
 * proved is the production wiring and not a copy of it built for the test.
 *
 * ### What it does not prove
 *
 * - **Token rotation.** The socket re-emits `auth` every fifteen minutes so that it keeps
 *   delivering after its access token has expired. That takes fifteen minutes of wall clock
 *   and a token the server has rotated, so it stays a manual check.
 * - **A reverse proxy on a subpath.** CI reaches the container directly, so only the root
 *   form of the path is proved here. The subpath split is covered by `RealtimeUrl`'s unit test.
 * - **Which transport carried it.** `Realtime` does not expose whether the connection is
 *   still long-polling or upgraded to WebSocket, and either one delivers events.
 *
 * ### What it costs the server
 *
 * The event test moves the position of the title in `lugu.test.playQuery`, which is the
 * consent [PlaybackResumptionTest] already relies on, and puts it back afterwards.
 *
 * The method names are underscored rather than backticked for the same reason as in
 * [PlaybackResumptionTest]: a name with spaces needs DEX 040, which needs minSdk 30.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RealtimeSocketTest {

    @get:Rule(order = 0)
    val notifications = grantNotificationPermission()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val app: LuguApplication
        get() = context.applicationContext as LuguApplication

    /** The server-side progress this test changed, so that it can be put back. */
    private var progressItemId: String? = null
    private var progressBefore: JSONObject? = null
    private var progressChanged = false

    @After
    fun putEverythingBack() {
        // The playback flag is handed back to what the player says, whichever way the
        // reconnection test left it. Nothing else in the process sets it until the player
        // changes, so this is the only way it returns to the truth.
        runCatching { app.realtime.setPlaybackActive(app.playbackStateHolder.nowPlaying.value != null) }

        val itemId = progressItemId
        if (itemId != null && progressChanged) {
            runCatching {
                SeededServer.restoreProgress(SeededServer.login(), itemId, progressBefore)
            }
        }
    }

    /**
     * The handshake: connect, emit `auth` with the access token, and hear `init` back.
     *
     * `connected` turns true only in the `init` listener, so reaching it means every step
     * before it worked: engine.io-client opened a transport through the app's OkHttp 5
     * client, the request reached `/socket.io` on the server, the token was accepted, and
     * the reply was routed to the listener by its name.
     */
    @Test
    fun the_socket_connects_and_the_server_answers_auth_with_init() {
        assumeTrue(TestServerConfig.NO_SERVER, TestServerConfig.hasServer)
        compose.signInThroughTheScreen(UI_TIMEOUT_MS)

        awaitConnected(app.realtime, "after signing in with the app on screen")
    }

    /**
     * An event the app subscribes to reaches its listener, and is parsed into a hint.
     *
     * The trigger is a progress write through the REST API, which is what another device
     * does when it moves a position. The server tells the user's sockets about it. Which
     * name it uses has changed between server versions: `user_item_progress_updated` names
     * the item, and `user_updated` carries the whole user. lugu subscribes to both and
     * re-reads progress for either, so either one is a pass, and the log line says which
     * one this server sent.
     */
    @Test
    fun a_progress_change_made_elsewhere_arrives_as_an_event() {
        assumeTrue(TestServerConfig.NO_SERVER, TestServerConfig.hasServer)
        assumeTrue(TestServerConfig.NO_QUERY, TestServerConfig.canPlay)
        compose.signInThroughTheScreen(UI_TIMEOUT_MS)

        val realtime = app.realtime
        awaitConnected(realtime, "before the progress change")

        val token = SeededServer.login()
        val itemId = SeededServer.bookIdByTitle(token, TestServerConfig.playQuery)
            ?: throw AssertionError("The title in lugu.test.playQuery is not on the test server.")
        progressItemId = itemId
        progressBefore = SeededServer.progress(token, itemId)

        // A position no earlier run leaves behind, so the write is a change and the server
        // has something to announce.
        val target = (progressBefore?.optDouble("currentTime", 0.0) ?: 0.0).let {
            if (it in MOVED_TO_SEC - 1.0..MOVED_TO_SEC + 1.0) MOVED_TO_SEC + 4.0 else MOVED_TO_SEC
        }

        val event = runBlocking {
            val subscribed = CompletableDeferred<Unit>()
            val heard = async(Dispatchers.Default) {
                withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                    realtime.events
                        .onSubscription { subscribed.complete(Unit) }
                        .first { it.isAbout(itemId) }
                }
            }
            // The flow keeps no history, so a write sent before the listener exists would
            // be announced to nobody and fail this test for a reason that is not the app's.
            withTimeout(SUBSCRIBE_TIMEOUT_MS) { subscribed.await() }
            SeededServer.setProgress(token, itemId, target)
            progressChanged = true
            heard.await()
        }

        assertWithMessage(
            "No progress event reached Realtime.events within ${EVENT_TIMEOUT_MS}ms of a " +
                "progress write, while the socket was authenticated. Either the server " +
                "announces it under a name lugu does not subscribe to, or the payload no " +
                "longer parses.",
        ).that(event).isNotNull()
        Log.i(TAG, "The server announced a progress write as ${event!!::class.simpleName}.")
    }

    /**
     * The socket closes when the app leaves the screen, and a new one authenticates when it
     * comes back.
     *
     * This is the app's own reconnection loop, not Socket.IO's, which is switched off. The
     * second `init` proves that a fresh socket, with a token fetched again, is accepted: the
     * same path a dropped connection takes after its backoff.
     *
     * The playback flag is set to false by hand. Every instrumented test shares one process,
     * and a test that played something earlier leaves the player loaded, which by design
     * keeps the socket open off screen. That is correct for a listener and wrong for this
     * test, which needs the off-screen case alone.
     */
    @Test
    fun the_socket_closes_off_screen_and_authenticates_again_on_return() {
        assumeTrue(TestServerConfig.NO_SERVER, TestServerConfig.hasServer)
        compose.signInThroughTheScreen(UI_TIMEOUT_MS)

        val realtime = app.realtime
        awaitConnected(realtime, "before leaving the screen")

        realtime.setPlaybackActive(false)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        awaitState(realtime, connected = false, timeoutMs = DISCONNECT_TIMEOUT_MS) {
            "The socket was still connected ${DISCONNECT_TIMEOUT_MS}ms after the app left the " +
                "screen with nothing loaded. The grace period is ten seconds."
        }

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitConnected(realtime, "after the app came back on screen")
    }

    private fun awaitConnected(realtime: Realtime, moment: String) =
        awaitState(realtime, connected = true, timeoutMs = CONNECT_TIMEOUT_MS) {
            "Realtime never reported connected $moment, within ${CONNECT_TIMEOUT_MS}ms. " +
                "Connected means the server answered `auth` with `init`, so the handshake, " +
                "the transport or the token failed. Nothing else in the app reports it: the " +
                "socket fails silently by design."
        }

    private fun awaitState(realtime: Realtime, connected: Boolean, timeoutMs: Long, message: () -> String) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (realtime.connected.value == connected) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(message())
    }

    private fun RealtimeEvent.isAbout(itemId: String): Boolean = when (this) {
        is RealtimeEvent.ProgressChanged -> libraryItemId == itemId
        RealtimeEvent.UserChanged -> true
        else -> false
    }

    private companion object {
        const val TAG = "LuguRealtime"

        const val UI_TIMEOUT_MS = 30_000L
        const val POLL_MS = 250L

        /** The socket's own connect timeout is twenty seconds, and the first try may fail. */
        const val CONNECT_TIMEOUT_MS = 45_000L

        /** The ten-second grace period, then the close, with room for a slow emulator. */
        const val DISCONNECT_TIMEOUT_MS = 30_000L

        const val EVENT_TIMEOUT_MS = 20_000L
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L

        /** Where the event test moves the book to. Inside the seeded book's ninety seconds. */
        const val MOVED_TO_SEC = 12.0
    }
}
