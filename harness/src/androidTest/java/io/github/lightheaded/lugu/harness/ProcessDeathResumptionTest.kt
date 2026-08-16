package io.github.lightheaded.lugu.harness

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.lightheaded.lugu.harness.MediaSessionDump.PlaybackSnapshot
import kotlin.math.abs
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole-process kill, which nothing inside lugu can perform on itself.
 *
 * M0's promise, in one sentence: the app is killed in the middle of a book, play is pressed
 * on a headset, and the right book resumes at the right position and at the speed it was
 * being listened to. Every part of that except the kill was already covered by
 * `PlaybackResumptionTest` in `:app`, which destroys the playback *service* instead —
 * genuinely the same code under test, and genuinely not the same kill: the service being
 * destroyed leaves the process, its statics and its Hilt graph standing.
 *
 * This runs in a different application id, so lugu's process can be ended outright and
 * there is still something alive to watch what happens next.
 *
 * ### What "killed" means here
 *
 * Two different endings, because the platform treats them differently and only one of them
 * is the case the resumption path exists for:
 *
 *  - **The process dies.** Android reclaiming memory, or the process crashing. The package
 *    is untouched, the media session's pending intent survives, and a media button is
 *    expected to bring the book back. This is the strict test.
 *  - **`am force-stop`.** A person going to Settings and pressing Force stop. That
 *    additionally puts the package into the *stopped state*, which the platform holds until
 *    the app is launched again — and from Android 15 it cancels every pending intent the
 *    app owns, the media button one included. The system is then refusing to wake lugu, by
 *    design, and no amount of correctness in lugu changes it. So the force-stop test asserts
 *    the only thing that would be lugu's fault: if something *does* come back, it is the
 *    right book at the right place.
 *
 * ### Everything here needs lugu installed
 *
 * The module under test is this one, so nothing about running these tests would put lugu on
 * the device. `harness/build.gradle.kts` says so instead — it depends on `:app:installDebug`
 * and orders it after `:app`'s own connected tests, which uninstall the app when they
 * finish. If lugu is missing anyway, every test here skips rather than fails.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ProcessDeathResumptionTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** The speed the book was on before the harness changed it, so it can be put back. */
    private var speedToRestore: Float? = null

    @Before
    fun luguIsInstalled() {
        assumeTrue(HarnessConfig.NO_LUGU, Lugu.isInstalled)

        // Granted from outside because the usual GrantPermissionRule grants to the package
        // under test, which here is the harness rather than lugu. Without it the system's
        // permission dialog comes up over lugu on first launch and every tap after it lands
        // on the wrong window. It exists only from Android 13; below that this does nothing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Shell.run("pm grant ${Lugu.PACKAGE} android.permission.POST_NOTIFICATIONS")
        }
    }

    /**
     * Leaves the device roughly as it was found: not playing, and at the speed the listener
     * had chosen for this book.
     *
     * Best effort by design — this runs after a test that may have just killed the app, and
     * a cleanup that threw would replace a real failure with its own.
     */
    @After
    fun stopPlayingAndPutTheSpeedBack() {
        runCatching {
            speedToRestore?.let { speed ->
                // The app has to be running to be told anything, and the test before this may
                // have killed it or left it in the stopped state. Opening it is also what
                // takes it out of that state, which nothing else here is allowed to do.
                if (Lugu.pid() == null) Lugu.launch()
                Lugu.broadcast("SET_SPEED") { putExtra("speed", speed) }
                SLEEP_SHORT.sleep()
            }
            Lugu.broadcast("PAUSE")
        }.onFailure { Log.w(TAG, "cleanup did not complete", it) }
    }

    /**
     * The claim the repository said could not be tested: lugu's process can be ended, and
     * the thing that ended it is still running afterwards to say so.
     *
     * No server needed, which makes this the one test here that a bare CI emulator actually
     * runs. It proves both mechanisms the other two depend on — the kill, and the reading of
     * `dumpsys media_session` — so that when they skip for want of a server, the harness
     * itself is still known to work. A dump this cannot parse reads exactly like an app that
     * is holding no session, which is the one misreading that would make every other test
     * here lie.
     */
    @Test
    fun the_harness_outlives_a_force_stop_of_lugu() {
        Lugu.launch()
        val luguPid = Await.notNull(LAUNCH_TIMEOUT_MS) { Lugu.pid() }
        assertWithMessage("lugu never started").that(luguPid).isNotNull()

        assertWithMessage(
            "no readable media session for an open lugu. Either the app no longer holds one " +
                "while it is in the foreground, or this Android writes dumpsys media_session " +
                "in a shape MediaSessionDump does not read.",
        ).that(Await.notNull(SESSION_TIMEOUT_MS) { Lugu.session() })
            .isNotNull()

        Lugu.forceStop()

        assertWithMessage("lugu's process survived am force-stop")
            .that(Await.until(KILL_TIMEOUT_MS) { Lugu.pid() == null })
            .isTrue()
        assertWithMessage("the media session outlived the process it belonged to")
            .that(Await.until(KILL_TIMEOUT_MS) { Lugu.session() == null })
            .isTrue()

        // The point of the module, asserted rather than assumed: the runner is somewhere
        // else. Inside lugu's own androidTest this line could not be reached at all.
        assertThat(Process.myPid()).isNotEqualTo(luguPid)
        assertThat(InstrumentationRegistry.getInstrumentation().targetContext.packageName)
            .isNotEqualTo(Lugu.PACKAGE)
    }

    /**
     * The sentence from the plan, end to end.
     *
     * Kill the process while a book is playing, press play on a headset, and land in the
     * same book, within a few seconds of where it stopped, at the speed it was being
     * listened to.
     */
    @Test
    fun a_media_button_resumes_the_same_book_after_the_process_is_killed() {
        assumeTrue(HarnessConfig.NO_SERVER, HarnessConfig.hasServer)
        assumeTrue(HarnessConfig.NO_QUERY, HarnessConfig.canPlay)

        val before = startPlaying()

        Lugu.killProcess()
        assertWithMessage("the media session outlived the process it belonged to")
            .that(Await.until(KILL_TIMEOUT_MS) { Lugu.session() == null })
            .isTrue()

        Lugu.pressPlay()

        val resumed = Await.notNull(RESUME_TIMEOUT_MS) {
            Lugu.session()?.takeIf { it.isPlaying }
        }
        assertWithMessage(
            "nothing was playing ${RESUME_TIMEOUT_MS}ms after the media button. This is the " +
                "failure the whole app exists to avoid: a headset press with the app gone " +
                "has to reach onPlaybackResumption and rebuild the book from Room.",
        ).that(resumed).isNotNull()

        assertResumed(before, resumed!!)
    }

    /**
     * Force stop, then the media button — the literal recipe from docs/qa/instrumented.md.
     *
     * What it asserts is narrower than it looks, and deliberately so. A force-stopped package
     * is in the stopped state, and the platform's rule is that only a person launching the
     * app takes it out again; from Android 15 the pending intent the media session gave the
     * system is cancelled outright at that moment. So "nothing happened" is the platform
     * doing its job and cannot be an assertion failure here.
     *
     * What would be lugu's fault is coming back *wrong*: a different book, the beginning of
     * the right one, or the wrong speed. That is asserted, on whatever does come back.
     */
    @Test
    fun a_force_stop_never_resumes_the_wrong_thing() {
        assumeTrue(HarnessConfig.NO_SERVER, HarnessConfig.hasServer)
        assumeTrue(HarnessConfig.NO_QUERY, HarnessConfig.canPlay)

        val before = startPlaying()

        Lugu.forceStop()
        assertWithMessage("lugu's process survived am force-stop")
            .that(Await.until(KILL_TIMEOUT_MS) { Lugu.pid() == null })
            .isTrue()

        Lugu.pressPlay()

        val resumed = Await.notNull(RESUME_TIMEOUT_MS) {
            Lugu.session()?.takeIf { it.isPlaying }
        }
        if (resumed == null) {
            Log.i(
                TAG,
                "a media button did not wake a force-stopped lugu after ${RESUME_TIMEOUT_MS}ms. " +
                    "That is the platform's stopped state, not a defect: see the class comment.",
            )
            return
        }

        Log.i(TAG, "a media button did wake a force-stopped lugu on API ${Build.VERSION.SDK_INT}")
        assertResumed(before, resumed)
    }

    // -----------------------------------------------------------------------------------

    /**
     * Gets a book playing, well into it, at a speed that is worth remembering.
     *
     * Each step is a surface somebody outside lugu really uses: the launcher, the sign-in
     * screen, and the documented automation broadcasts. Nothing here reaches into the app.
     */
    private fun startPlaying(): PlaybackSnapshot {
        Lugu.launch()
        signInIfAsked()

        // Repeated rather than sent once: PLAY_SEARCH answers from the offline index, which
        // on a fresh install is empty until the first sync lands. A single broadcast would
        // make this a test of how fast a library syncs.
        val playing = Await.notNull(PLAY_TIMEOUT_MS) {
            Lugu.broadcast("PLAY_SEARCH") { putExtra("query", HarnessConfig.playQuery) }
            Await.notNull(PLAY_ATTEMPT_MS) {
                Lugu.session()?.takeIf { it.isPlaying }
            }
        }
        assertWithMessage(
            "nothing started playing within ${PLAY_TIMEOUT_MS}ms of asking for the title in " +
                "lugu.test.playQuery. Check the title is in the library and that the sign-in " +
                "screen was prefilled.",
        ).that(playing).isNotNull()

        val started = playing!!
        speedToRestore = started.speed

        // Deep into the book, so that "resumed from zero" and "resumed where it was" are far
        // enough apart to tell apart. Ten minutes in, a resumption that started over is
        // nine minutes wrong, which no tolerance can excuse.
        Lugu.broadcast("SKIP_FORWARD") { putExtra("seconds", SKIP_SECONDS) }
        Await.until(SEEK_TIMEOUT_MS) {
            val now = Lugu.session() ?: return@until false
            now.isPlaying && now.positionMs > started.positionMs + SKIP_PROOF_MS
        }

        val target = speedTarget(started.speed)
        Lugu.broadcast("SET_SPEED") { putExtra("speed", target) }
        assertWithMessage("lugu never reported the speed the harness asked for")
            .that(
                Await.until(SPEED_TIMEOUT_MS) {
                    val now = Lugu.session()
                    now != null && now.isPlaying && abs(now.speed - target) < SPEED_EPSILON
                },
            )
            .isTrue()

        val settled = requireNotNull(Lugu.session()) {
            "the session disappeared while the book was being set up"
        }
        assertWithMessage(
            "the test item is only ${settled.positionMs}ms in after a skip, which is too " +
                "short for the position assertion to mean anything. Point lugu.test.playQuery " +
                "at something longer.",
        ).that(settled.positionMs).isAtLeast(MIN_POSITION_MS)
        return settled
    }

    /**
     * The same book, near the same place, at the same speed.
     *
     * Titles are compared as digests and never printed: a failure message goes into a CI log,
     * and nothing in this repository may name what is on somebody's shelf. See
     * [MediaSessionDump].
     */
    private fun assertResumed(before: PlaybackSnapshot, after: PlaybackSnapshot) {
        assertWithMessage(
            "a different item resumed: was ${before.identity}, came back as ${after.identity}",
        ).that(after.identity).isEqualTo(before.identity)

        // Behind is forgiven up to a point: the position is written to Room on a five-second
        // tick, and lugu deliberately rewinds a few seconds after a gap so that a sentence is
        // not lost. Far behind is the failure that matters — it is what starting the book
        // over looks like.
        assertWithMessage("the book resumed ${before.positionMs - after.positionMs}ms behind")
            .that(after.positionMs)
            .isAtLeast(before.positionMs - BEHIND_TOLERANCE_MS)
        assertWithMessage("the book resumed ${after.positionMs - before.positionMs}ms ahead")
            .that(after.positionMs)
            .isAtMost(before.positionMs + AHEAD_TOLERANCE_MS)

        // The half of the promise that has never been checked anywhere: a book that starts
        // by itself at the wrong speed is a worse first impression than one that does not
        // start at all.
        assertWithMessage("the remembered speed was lost")
            .that(after.speed)
            .isWithin(SPEED_EPSILON)
            .of(before.speed)
    }

    /**
     * Taps the sign-in button if lugu is asking, and does nothing if it is not.
     *
     * The fields are already filled in on a debug build, from the same local.properties the
     * app module reads — which is the whole reason the harness needs no credentials of its
     * own. It taps a button; it never learns what is in the fields.
     */
    private fun signInIfAsked() {
        device.wait(Until.hasObject(By.pkg(Lugu.PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS)
        device.wait(Until.findObject(By.text("Sign in")), SIGN_IN_TIMEOUT_MS)?.click()
        device.wait(Until.gone(By.text("Sign in")), SIGN_IN_TIMEOUT_MS)
    }

    /** A speed that is not the one already in force, so that remembering it proves something. */
    private fun speedTarget(current: Float): Float =
        if (abs(current - PRIMARY_SPEED) < SPEED_EPSILON) ALTERNATE_SPEED else PRIMARY_SPEED

    private fun Long.sleep() = SystemClock.sleep(this)

    private companion object {
        const val TAG = "LuguHarness"

        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val SIGN_IN_TIMEOUT_MS = 15_000L
        const val KILL_TIMEOUT_MS = 15_000L
        const val SESSION_TIMEOUT_MS = 20_000L
        const val SEEK_TIMEOUT_MS = 15_000L
        const val SPEED_TIMEOUT_MS = 15_000L
        const val SLEEP_SHORT = 1_000L

        /** A whole sync and a first buffer, on an emulator, over whatever network CI has. */
        const val PLAY_TIMEOUT_MS = 120_000L

        /** How long one PLAY_SEARCH is given before it is sent again. */
        const val PLAY_ATTEMPT_MS = 15_000L

        /**
         * A cold start of the process, the service, Hilt's graph, a Room read and the first
         * bytes of audio — all of it after a media button, with no UI to speed it along.
         */
        const val RESUME_TIMEOUT_MS = 60_000L

        const val SKIP_SECONDS = 600
        const val SKIP_PROOF_MS = 300_000L
        const val MIN_POSITION_MS = 60_000L

        const val BEHIND_TOLERANCE_MS = 45_000L

        /**
         * Ahead is tolerated far less. The position is extrapolated to the moment it is read,
         * so a resumption that landed in the right place reads as the right place — anything
         * much beyond it is a book that came back somewhere it was never left.
         */
        const val AHEAD_TOLERANCE_MS = 20_000L

        const val PRIMARY_SPEED = 1.5f
        const val ALTERNATE_SPEED = 1.2f
        const val SPEED_EPSILON = 0.05f
    }
}
