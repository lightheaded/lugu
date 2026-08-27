package io.github.lightheaded.lugu.harness

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
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
 *    is untouched, what the platform holds for the media button survives, and a media button
 *    is expected to bring the book back. This is the strict test, and it passes: measured on
 *    an API 36 emulator, the same item came back at 45817ms against 45378ms, at the 1.5x it
 *    was on. Taking the key press out makes it fail, which is what says the press is doing
 *    the work rather than a service the system restarted by itself.
 *  - **`am force-stop`.** A person going to Settings and pressing Force stop. That
 *    additionally puts the package into the *stopped state*, which the platform holds until
 *    the app is launched again — and from Android 15 it cancels every pending intent the
 *    app owns as it enters that state. Whether a media button gets through anyway is not
 *    lugu's to decide, and it is not even stable: on one emulator, within the same hour, it
 *    woke lugu twice and did not wake it on a third run against a fresh install. So this
 *    test asserts only what would be lugu's fault — that if something *does* come back, it
 *    is the right book at the right place and speed.
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

        val killed = Lugu.killProcess()
        Log.i(TAG, "killed lugu's process $killed while it was playing")
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
     * What it asserts is narrower than it looks, and measurement is why. A force-stopped
     * package is in the stopped state, which the platform holds until a person launches the
     * app, and from Android 15 it cancels the app's pending intents as it enters it. Whether
     * a media button gets through anyway turned out not to be stable: on one API 36 emulator
     * within the same hour, it woke lugu on two runs and not on a third. Asserting either
     * outcome would be asserting something lugu does not control.
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
     * Gets a book playing, far enough in to be worth resuming, at a remembered speed.
     *
     * Each step is a surface somebody outside lugu really uses: the launcher, the sign-in
     * screen, the Library tab, and the documented automation broadcasts. Nothing here
     * reaches into the app.
     *
     * It always starts the book from the beginning and walks forward to the same place, so
     * that a run does not depend on where the run before it stopped. That determinism is
     * paid for: **the position lugu holds for the title in `lugu.test.playQuery` is
     * overwritten**, which is one more reason that key is separate from the credentials.
     *
     * Note what it waits for after asking for the title: an item to be *loaded*, not one to
     * be playing. A book that was left at its end — by the test before, or by a run
     * yesterday, since progress lives on the server and comes back down with a fresh
     * install — loads, plays for whatever is left of it, and stops. Waiting for "playing"
     * there is a coin toss, and it came up tails: the first version of this test reported
     * that nothing had started playing when what had happened was that everything had
     * already finished.
     */
    private fun startPlaying(): PlaybackSnapshot {
        Lugu.launch()
        LuguUi.signInIfAsked()
        awaitTheLibraryMirrored()

        Lugu.broadcast("PLAY_SEARCH") { putExtra("query", HarnessConfig.playQuery) }
        val loaded = Await.notNull(PLAY_TIMEOUT_MS) { Lugu.session()?.takeIf { it.hasItem } }
        assertWithMessage(
            "no book was loaded within ${PLAY_TIMEOUT_MS}ms of asking for the title in " +
                "lugu.test.playQuery. The library had arrived by then, so this is playback " +
                "failing rather than the search finding nothing.",
        ).that(loaded).isNotNull()

        val started = seekSomewhereWorthResuming()
        speedToRestore = started.speed
        rememberASpeed(started.speed)
        return requireNotNull(Lugu.session()?.takeIf { it.isPlaying }) {
            "the book stopped playing while it was being set up"
        }
    }

    /**
     * Puts the book at a known place: back to the beginning, then forward to [TARGET_MS].
     *
     * Both halves were learnt from one run. The first version skipped ten minutes forward in
     * a single broadcast, which is past the end of anything short — the seeded CI library is
     * a ninety-second book — so playback ran off the end, the session stopped, and the speed
     * that was set next was never reported by a session that was no longer playing. The test
     * after it then found the item stored at its end, resumed there, stopped immediately, and
     * reported that nothing had started playing. One bad constant, two failures that looked
     * unrelated.
     *
     * So: rewind first, because the previous test left the position wherever it left it; then
     * walk forward in short steps, checking after each one that the book is still playing.
     * A book too short to reach the target says so, rather than ending and blaming the speed.
     *
     * The `PLAY` after the rewind is for the case where the book had already run to its end
     * before this test touched it: seeking back off the end leaves a player that is ready and
     * not moving. `PLAY` resumes what is loaded and never pauses, so sending it when playback
     * is already running costs nothing.
     */
    private fun seekSomewhereWorthResuming(): PlaybackSnapshot {
        Lugu.broadcast("SKIP_BACK") { putExtra("seconds", REWIND_SECONDS) }
        SLEEP_SHORT.sleep()
        Lugu.broadcast("PLAY")

        val start = Await.notNull(PLAY_TIMEOUT_MS) {
            Lugu.session()?.takeIf { it.isPlaying && it.reportedPositionMs < NEAR_THE_START_MS }
        }
        assertWithMessage(
            "the book would not play from its beginning. It was asked to go back " +
                "${REWIND_SECONDS}s and then to play.",
        ).that(start).isNotNull()

        var latest = start!!
        repeat(MAX_SKIPS) {
            if (latest.reportedPositionMs >= TARGET_MS) return latest
            val from = latest.reportedPositionMs
            Lugu.broadcast("SKIP_FORWARD") { putExtra("seconds", SKIP_SECONDS) }
            // Against what the session *published*, not against an estimate of where the book
            // must be by now. A seek is a discontinuity and always publishes; an estimate
            // rises on its own, so a loop that read one could walk itself to the target
            // without a single seek having landed — which is exactly what happened once.
            latest = Await.notNull(SEEK_TIMEOUT_MS) {
                Lugu.session()?.takeIf { it.isPlaying && it.reportedPositionMs > from + SKIP_PROOF_MS }
            } ?: return@repeat
        }

        val here = requireNotNull(Lugu.session()) { "the session went away mid-seek" }
        assertWithMessage(
            "the book is ${here.reportedPositionMs}ms in and ${if (here.isPlaying) "playing" else "no " +
                "longer playing"}, which is not far enough from the start for a resumption to " +
                "be told apart from starting over. Point lugu.test.playQuery at something " +
                "longer than ${TARGET_MS / 1000}s.",
        ).that(here.isPlaying && here.reportedPositionMs >= MIN_POSITION_MS).isTrue()
        return here
    }

    /** Sets a speed that is not the one already in force, so remembering it proves something. */
    private fun rememberASpeed(current: Float) {
        val target = speedTarget(current)
        Lugu.broadcast("SET_SPEED") { putExtra("speed", target) }
        assertWithMessage("lugu never reported the speed the harness asked for")
            .that(
                Await.until(SPEED_TIMEOUT_MS) {
                    val now = Lugu.session()
                    now != null && now.isPlaying && abs(now.speed - target) < SPEED_EPSILON
                },
            )
            .isTrue()
    }

    /**
     * Waits for the title to be somewhere a person could see it before asking for it by name.
     *
     * `PLAY_SEARCH` answers from the offline index, which is Room — so between signing in and
     * the first sync landing, the correct answer to "play Lighthouse Wakes" is that there is
     * no such book. `:app`'s `PlaybackResumptionTest` waits on Room directly; the harness
     * cannot, because Room belongs to another app, so it waits on the screen instead. Same
     * race, same reasoning: waiting rather than retrying the broadcast keeps "the library
     * never arrived" a different failure from "playback never started", and they are
     * different bugs.
     *
     * The Library tab is opened on the way, which is what a person does — and until recently
     * was the only thing that started the first sync at all.
     */
    private fun awaitTheLibraryMirrored() {
        LuguUi.openLibraryTab()

        val title = HarnessConfig.playQuery
        val arrived = Await.until(LIBRARY_TIMEOUT_MS) { LuguUi.shows(title) }
        assertWithMessage(
            "the title in lugu.test.playQuery was not on screen ${LIBRARY_TIMEOUT_MS}ms after " +
                "signing in. Either it is not in this server's library, or the first sync " +
                "after sign-in did not mirror any items.",
        ).that(arrived).isTrue()
    }

    /**
     * The same book, near the same place, at the same speed.
     *
     * Titles are compared as digests and never printed: a failure message goes into a CI log,
     * and nothing in this repository may name what is on somebody's shelf. See
     * [MediaSessionDump].
     *
     * Both positions are what the platform *said*, not what it can be calculated to be by
     * now — see [MediaSessionDump.PlaybackSnapshot.positionMs] for the run that settled that.
     * A stale reading errs towards an earlier position, which loosens the "behind" check and
     * tightens the "ahead" one, and those are the safe directions for both.
     */
    private fun assertResumed(before: PlaybackSnapshot, after: PlaybackSnapshot) {
        // Written down on a pass as well as a failure. A green run of this test is a claim
        // about someone's place in a book, and the numbers behind it are the difference
        // between "it resumed" and "it resumed where it was". Identities are digests and
        // positions are numbers, so this names nothing that is on anybody's shelf.
        Log.i(
            TAG,
            "resumed ${after.identity} at ${after.reportedPositionMs}ms x${after.speed}; " +
                "was ${before.identity} at ${before.reportedPositionMs}ms x${before.speed}",
        )

        assertWithMessage(
            "a different item resumed: was ${before.identity}, came back as ${after.identity}",
        ).that(after.identity).isEqualTo(before.identity)

        // Behind is forgiven up to a point — see BEHIND_TOLERANCE_MS. Far behind is the
        // failure that matters, because it is what starting the book over looks like.
        assertWithMessage("the book resumed ${before.reportedPositionMs - after.reportedPositionMs}ms behind")
            .that(after.reportedPositionMs)
            .isAtLeast(before.reportedPositionMs - BEHIND_TOLERANCE_MS)
        assertWithMessage("the book resumed ${after.reportedPositionMs - before.reportedPositionMs}ms ahead")
            .that(after.reportedPositionMs)
            .isAtMost(before.reportedPositionMs + AHEAD_TOLERANCE_MS)

        // The half of the promise that has never been checked anywhere: a book that starts
        // by itself at the wrong speed is a worse first impression than one that does not
        // start at all.
        assertWithMessage("the remembered speed was lost")
            .that(after.speed)
            .isWithin(SPEED_EPSILON)
            .of(before.speed)
    }

    /** A speed that is not the one already in force, so that remembering it proves something. */
    private fun speedTarget(current: Float): Float =
        if (abs(current - PRIMARY_SPEED) < SPEED_EPSILON) ALTERNATE_SPEED else PRIMARY_SPEED

    private fun Long.sleep() = SystemClock.sleep(this)

    private companion object {
        const val TAG = "LuguHarness"

        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val KILL_TIMEOUT_MS = 15_000L
        const val SESSION_TIMEOUT_MS = 20_000L
        const val SEEK_TIMEOUT_MS = 8_000L
        const val SPEED_TIMEOUT_MS = 15_000L
        const val SLEEP_SHORT = 1_000L

        /** A first sync, over whatever network CI has, on an emulator that has just booted. */
        const val LIBRARY_TIMEOUT_MS = 90_000L

        /** The search has already been proved to have something to find, so: a first buffer. */
        const val PLAY_TIMEOUT_MS = 45_000L

        /**
         * A cold start of the process, the service, Hilt's graph, a Room read and the first
         * bytes of audio — all of it after a media button, with no UI to speed it along.
         */
        const val RESUME_TIMEOUT_MS = 60_000L

        /**
         * Where the book is put before it is killed, and how it gets there.
         *
         * Forty-five seconds is chosen against two things at once. It has to be comfortably
         * further from zero than [BEHIND_TOLERANCE_MS], or "resumed where it was" and "started
         * the book over" would both pass. And it has to be reachable in a *short* item,
         * because the library CI seeds is ninety seconds of sine wave — a target of ten
         * minutes ran off the end of it, which is how the first version of this test managed
         * to fail twice for one reason.
         *
         * The steps are fifteen seconds because the proof that a seek happened is that the
         * position moved further than ordinary playback could have moved it in the same
         * window, and a large step in one broadcast cannot be walked back if the book is
         * shorter than it.
         */
        const val TARGET_MS = 45_000L
        const val SKIP_SECONDS = 15
        const val SKIP_PROOF_MS = 10_000L
        const val MAX_SKIPS = 6

        /** Bigger than any book, so the book goes back to its start whatever it is. */
        const val REWIND_SECONDS = 3_600
        const val NEAR_THE_START_MS = 10_000L

        /** Far enough in that starting over cannot be mistaken for resuming. */
        const val MIN_POSITION_MS = 35_000L

        /**
         * How far behind the resumed position may be.
         *
         * Only two things put it behind, and neither is large: the position is written to Room
         * on a five-second tick, and the last write before a kill is the seek that put it
         * there. Nothing on this path applies a rewind — `SmartRewind` is sized from how long
         * playback was *paused*, which a process that has died no longer knows.
         */
        const val BEHIND_TOLERANCE_MS = 20_000L

        /**
         * Ahead is a stranger failure and gets its own room: the position is extrapolated to
         * the moment it is read, so a resumption that landed in the right place reads as the
         * right place, and anything much past it came back somewhere it was never left.
         */
        const val AHEAD_TOLERANCE_MS = 30_000L

        const val PRIMARY_SPEED = 1.5f
        const val ALTERNATE_SPEED = 1.2f
        const val SPEED_EPSILON = 0.05f
    }
}
