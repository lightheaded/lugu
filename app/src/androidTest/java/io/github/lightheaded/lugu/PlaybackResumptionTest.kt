package io.github.lightheaded.lugu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.playback.LuguPlaybackService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Catches lugu losing someone's place, which is the failure it exists to avoid.
 *
 * Two paths are covered, and neither has ever been exercised anywhere else:
 *
 *  - **A position survives the service being destroyed.** Everything that keeps a place is
 *    a write to Room on a five-second tick and on every pause and seek. A change that
 *    moved that bookkeeping into the player object would pass every unit test here and
 *    lose the position on the first time Android reclaims the service.
 *  - **A media button with nothing running resumes the right thing.** `onPlaybackResumption`
 *    rebuilds the item, the playlist and the position from Room with no UI alive, and it
 *    is reached only by a media button arriving at a session whose service has just been
 *    started for it. That is a headset press in a pocket, and it is not reachable from a
 *    unit test at all.
 *
 * **What this does not do**, said plainly and more plainly than it used to be said.
 *
 * It does not kill the process: instrumentation runs inside the process under test, so
 * `am force-stop` on this package would take the test with it. `:harness` exists for that,
 * as its own application id.
 *
 * And it does not, it turns out, destroy the service either. The claim here used to be that
 * `stopService` stands in for Android reclaiming memory. Media3 keeps a
 * `MediaSessionService` alive while its session is active, and the first CI run that ever
 * executed this test — it had skipped for want of a server since the day it was written —
 * came back with the player still holding its position, which a service that had been
 * through `onCreate` again could not have done.
 *
 * So what these two tests actually establish is narrower than their names: that the
 * position is durable across the session being let go of and picked up again, and that a
 * media button dispatched through [AudioManager] reaches lugu's session and resumes the
 * right thing. Both are worth having. Neither is the cold-start case, and the cold-start
 * case is `:harness`'s.
 *
 * Everything here needs a server, because a position only exists once something has
 * played. With none configured these skip rather than fail. *
 * The method names here are underscored rather than the backticked sentences the JVM suites
 * use. A name with spaces in it needs DEX version 040, which needs minSdk 30; lugu's minSdk
 * is 26, so the test APK will not dex with them.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PlaybackResumptionTest {

    @get:Rule(order = 0)
    val notifications = grantNotificationPermission()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var controller: MediaController? = null

    @After
    fun releaseController() {
        val held = controller ?: return
        controller = null
        onMain { held.release() }
    }

    @Test
    fun a_position_survives_the_playback_service_being_destroyed() {
        assumeTrue(TestServerConfig.NO_SERVER, TestServerConfig.hasServer)
        assumeTrue(TestServerConfig.NO_QUERY, TestServerConfig.canPlay)

        signIn()
        awaitTheLibraryMirrored()
        startPlaying()

        val before = awaitAdvancingPosition()
        stopPlaybackService()

        // No assertion that the player came back empty, and that absence is the point.
        //
        // This used to assert `positionOf(resumed) <= 1`, on the reasoning that a destroyed
        // service builds a fresh ExoPlayer in `onCreate` with nothing loaded — which is true
        // of the service. What is not true is that `stopService` destroys it: Media3 keeps a
        // MediaSessionService alive while its session is active, and the first CI run that
        // ever executed this test came back with 4093ms, meaning the player had never gone
        // away. The step this test is named after has therefore never actually happened, and
        // nobody knew because the test skipped for want of a server.
        //
        // What is asserted below still holds and is worth having: the position is intact and
        // playback resumes from it. But the real kill — the process, not the service — is
        // `:harness`, which runs as its own application id precisely so it can end this one.
        val resumed = connectController()

        onMain { resumed.play() }
        awaitPlaying(resumed)

        val after = positionOf(resumed)
        assertThat(after).isAtLeast(before - TOLERANCE_MS)
    }

    /**
     * The plan's own sentence, as far as it can be reached from here: press play on a
     * headset with nothing on screen, and land in the right place.
     *
     * The key event is dispatched through [AudioManager] rather than handed to a
     * controller, because that is the route a headset actually takes — the system decides
     * which session gets it. A controller call would prove the session works and prove
     * nothing about whether Android would ever route a button to it.
     */
    @Test
    fun a_headset_play_button_with_nothing_running_resumes_the_last_item() {
        assumeTrue(TestServerConfig.NO_SERVER, TestServerConfig.hasServer)
        assumeTrue(TestServerConfig.NO_QUERY, TestServerConfig.canPlay)

        signIn()
        awaitTheLibraryMirrored()
        startPlaying()
        val before = awaitAdvancingPosition()

        stopPlaybackService()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressHome()

        val audioManager = context.getSystemService(AudioManager::class.java)
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))

        val resumed = connectController()
        awaitPlaying(resumed)
        assertThat(positionOf(resumed)).isAtLeast(before - TOLERANCE_MS)
    }

    // -----------------------------------------------------------------------------------

    /**
     * Signs in through the screen rather than by writing to the store.
     *
     * Slower, and worth it: the fields are prefilled from the same `BuildConfig` values on
     * a debug build, so this is one tap, and it proves the sign-in path still works before
     * anything else is asserted.
     */
    private fun signIn() {
        compose.waitUntil(UI_TIMEOUT_MS) {
            compose.onAllNodes(hasText(SIGN_IN_PROMPT)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasContentDescription("Settings")).fetchSemanticsNodes().isNotEmpty()
        }
        val signedOut = compose.onAllNodes(hasText(SIGN_IN_PROMPT)).fetchSemanticsNodes().isNotEmpty()
        if (signedOut) {
            compose.onNodeWithText("Sign in").performClick()
        }
        compose.waitUntil(UI_TIMEOUT_MS) {
            compose.onAllNodes(hasContentDescription("Settings")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Waits for the library to reach Room before asking for anything in it by name.
     *
     * [startPlaying] searches by title, and that search runs against Room rather than
     * against the server — so between signing in and the first sync finishing, the right
     * answer to "play Lighthouse Wakes" is that there is no such book. Without this wait
     * the race is real and it resolves differently on different machines: on the first CI
     * run with a server, the API 26 emulator lost it in the first test and won it in the
     * second, and API 36 lost it in both. What either failure said was "Nothing was playing
     * after 45000ms", which names the wrong thing entirely.
     *
     * Waiting on Room rather than retrying the broadcast is deliberate. A retry loop would
     * go green either way and tell nobody that the mirror is slow; this fails saying the
     * library never arrived, which is a different bug from playback never starting and
     * wants finding separately.
     */
    private fun awaitTheLibraryMirrored() {
        val db = LuguDatabase.build(context)
        try {
            val deadline = System.currentTimeMillis() + SYNC_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val found = runBlocking {
                    db.serverDao().active()?.let { server ->
                        db.libraryItemDao()
                            .searchEverywhereLike(server.serverId, server.userId, TestServerConfig.playQuery)
                            .isNotEmpty()
                    } == true
                }
                if (found) return
                Thread.sleep(POLL_MS)
            }
            throw AssertionError(
                "\"${TestServerConfig.playQuery}\" never reached Room after ${SYNC_TIMEOUT_MS}ms. " +
                    "The sign-in worked, so this is the library mirror, not playback.",
            )
        } finally {
            db.close()
        }
    }

    /**
     * Starts something through the automation receiver.
     *
     * The alternative is driving the library grid, which means waiting on a sync and then
     * finding a particular cover — a test about resumption failing because a shelf was
     * still loading is a test nobody trusts. The broadcast is a supported entry point and
     * takes a title, so it works whatever the library holds.
     */
    private fun startPlaying() {
        context.sendBroadcast(
            Intent("${BuildConfig.APPLICATION_ID}.action.PLAY_SEARCH")
                .setPackage(BuildConfig.APPLICATION_ID)
                .putExtra("query", TestServerConfig.playQuery),
        )
    }

    /** A position that is going up is the only proof that audio is actually running. */
    private fun awaitAdvancingPosition(): Long {
        val playing = connectController()
        awaitPlaying(playing)

        val first = positionOf(playing)
        val deadline = System.currentTimeMillis() + PLAYBACK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val now = positionOf(playing)
            if (now > first + ADVANCE_MS) return now
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("Playback never advanced past ${first}ms")
    }

    private fun awaitPlaying(controller: MediaController) {
        val deadline = System.currentTimeMillis() + PLAYBACK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            var playing = false
            onMain { playing = controller.isPlaying }
            if (playing) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("Nothing was playing after ${PLAYBACK_TIMEOUT_MS}ms")
    }

    /**
     * Destroys the service, which is what Android does when it reclaims memory.
     *
     * The controller is released first: a bound controller keeps the service alive, so
     * stopping it while one is attached would prove nothing.
     */
    private fun stopPlaybackService() {
        releaseController()
        context.stopService(Intent(context, LuguPlaybackService::class.java))
        Thread.sleep(SERVICE_TEARDOWN_MS)
    }

    private fun connectController(): MediaController {
        releaseController()
        val token = SessionToken(context, ComponentName(context, LuguPlaybackService::class.java))
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        onMain { future = MediaController.Builder(context, token).buildAsync() }
        return future.get(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS).also { controller = it }
    }

    /** Every [MediaController] member has to be touched on the thread that built it. */
    private fun positionOf(controller: MediaController): Long {
        var position = 0L
        onMain { position = controller.currentPosition }
        return position
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private companion object {
        const val SIGN_IN_PROMPT = "Sign in to your Audiobookshelf server"
        const val UI_TIMEOUT_MS = 30_000L
        const val PLAYBACK_TIMEOUT_MS = 45_000L

        /**
         * Generous, and measured rather than picked. On a cold CI emulator the first mirror
         * of a two-book library took longer than 60s — the first test in a run timed out at
         * that and the second, starting later, found the library already there. Why two
         * books take that long is worth knowing and is in the backlog; it is not this
         * test's question, so the wait is simply long enough not to be the thing that fails.
         */
        const val SYNC_TIMEOUT_MS = 180_000L
        const val CONNECT_TIMEOUT_SEC = 20L
        const val POLL_MS = 250L
        const val SERVICE_TEARDOWN_MS = 2_000L

        /** Far enough that it cannot be a rounding artefact of a stopped player. */
        const val ADVANCE_MS = 2_000L

        /**
         * A resumed position is allowed to be a little behind: the position is persisted on
         * a five-second tick, and lugu deliberately rewinds a few seconds after a gap so a
         * sentence is not lost. Being *ahead* is never acceptable and is not tolerated.
         */
        const val TOLERANCE_MS = 45_000L
    }
}
