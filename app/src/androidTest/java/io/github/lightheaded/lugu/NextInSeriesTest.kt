package io.github.lightheaded.lugu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
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
import com.google.common.truth.Truth.assertWithMessage
import io.github.lightheaded.lugu.core.db.LibraryItemEntity
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.db.QueueEntity
import io.github.lightheaded.lugu.core.db.QueueSource
import io.github.lightheaded.lugu.core.db.ServerEntity
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.sync.PlaybackEvent
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
import io.github.lightheaded.lugu.core.sync.QueuePrefs
import io.github.lightheaded.lugu.core.sync.QueueSettings
import io.github.lightheaded.lugu.playback.LuguPlaybackService
import io.github.lightheaded.lugu.playback.MediaResolver
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The join nothing had ever watched: a book reaches its end, and the next volume begins.
 *
 * Every part of this path had a test and the join had none. `nextInSeriesAfter` is proved
 * against Room, the series membership is proved against the migrations, and
 * `DefaultContinuationResolver` is proved to apply the ask-first setting — and none of
 * that says the player ever reaches `STATE_ENDED`, that the service hears it, or that what
 * comes back is the volume after the one that ended. Until this test the claim rested on
 * three green suites that each stopped one step short of the other two.
 *
 * The fixture was the other half of why. `scripts/seed-test-server.sh` seeded no series of
 * any kind, so a check written against the catalogue as it stood would have found nothing to
 * continue *to* and passed for it. The script now builds a two-volume series, and this
 * test fails rather than skips when it cannot find one — see [TestServerConfig.NO_SERIES].
 *
 * ### Why `:app` and not `:harness`
 *
 * `:harness` exists to end lugu's process, and this join needs no kill: the book ends
 * inside a service that is already running. What it does need is everything `:harness`
 * deliberately refuses to have. It has to name the two volumes by their **sequence** in
 * one series, which lives in `item_series` in lugu's own Room; it has to prove that the
 * cued volume reached the **head of the queue**, which is another table in the same
 * database; and it has to turn `askBeforeSuggestion` on and put it back, which is lugu's
 * own DataStore. A harness that read any of those would stop being a black box, and one
 * that did not could only report that *something* started playing.
 *
 * ### What it costs the server
 *
 * The two volumes are played and their positions are overwritten, exactly as the title in
 * `lugu.test.playQuery` is. That is why the series is a key of its own: having a server is
 * not the same as agreeing that a test may run two books to their end on it.
 *
 * Both volumes are reset to unstarted first, on the server and in Room, because
 * `nextInSeriesAfter` skips a volume anybody has started. Without the reset this test
 * would pass once and then report, for every run after it, that the series had no next
 * volume — which is true, and says nothing about the code.
 *
 * The method names are underscored rather than backticked for the same reason as in
 * [PlaybackResumptionTest]: a name with spaces needs DEX 040, which needs minSdk 30.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class NextInSeriesTest {

    @get:Rule(order = 0)
    val notifications = grantNotificationPermission()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var controller: MediaController? = null
    private var database: LuguDatabase? = null

    /** Everything this test changes on the device, so that all of it can be put back. */
    private var settingsToRestore: QueueSettings? = null
    private var queueToRestore: List<QueueEntity>? = null
    private var speedToRestore: Float? = null
    private var speedItemToRestore: String? = null

    @After
    fun putEverythingBack() {
        runCatching { controller?.let { held -> onMain { held.pause() } } }
        runCatching { releaseController() }

        val prefs = QueuePrefs(context.applicationContext)
        settingsToRestore?.let { was ->
            runCatching {
                runBlocking {
                    prefs.setContinueSeries(was.continueSeries)
                    prefs.setAskBeforeSuggestion(was.askBeforeSuggestion)
                }
            }
        }

        val speedItem = speedItemToRestore
        val speed = speedToRestore
        if (speedItem != null && speed != null) {
            // Setting a book back to the applicable default forgets the override, which is
            // the right restore for a book that had none.
            runCatching {
                runBlocking {
                    PlaybackPrefs(context.applicationContext)
                        .setSpeedFor(speedItem, MediaType.BOOK, speed)
                }
            }
        }
        speedItemToRestore = null
        speedToRestore = null

        val db = database
        val queue = queueToRestore
        if (db != null && queue != null) {
            runCatching {
                runBlocking {
                    val account = db.serverDao().active()
                    if (account != null) {
                        db.queueDao().clear(account.serverId, account.userId)
                        if (queue.isNotEmpty()) db.queueDao().upsertAll(queue)
                    }
                }
            }
        }
        runCatching { db?.close() }
        database = null
    }

    /**
     * The whole promise, with the setting at its default: the next volume plays itself.
     *
     * The end is a real end. The book is walked forward to its last few seconds and then
     * left alone, so what the service reacts to is the player running out of audio rather
     * than a state this test posted at it. A faked `STATE_ENDED` would prove that the
     * listener works and say nothing about whether anything ever calls it.
     */
    @Test
    fun a_book_that_ends_is_followed_by_the_next_volume_of_its_series() {
        val series = prepare(askFirst = false)

        playToTheEndOf(series.volumeOne)

        val playing = awaitLoaded(series.volumeTwo.id)
        assertWithMessage(
            "the volume after the one that ended was loaded but never started. The setting " +
                "asked for it to start, so this is the service cueing when it was told to play.",
        ).that(awaitPlaying(playing)).isTrue()
        Log.i(TAG, "the next volume in the series began by itself")
    }

    /**
     * The same end, with "ask before a suggestion" on: loaded, at the head of the queue,
     * and silent.
     *
     * This is the branch that decides between starting and cueing, and it is the one with
     * something to get wrong. `playWhenReady` survives the end of a playlist — a player
     * that has run out of audio is still a player that wants to play — so loading the next
     * book into it starts it unless something says otherwise.
     *
     * The queue is asserted as well as the silence, because cueing has two halves: nothing
     * begins, and the answer is not thrown away. A suggestion that was resolved and then
     * forgotten would look identical from the player alone.
     */
    @Test
    fun with_ask_first_on_the_next_volume_is_cued_and_not_started() {
        val series = prepare(askFirst = true)

        playToTheEndOf(series.volumeOne)

        val cued = awaitLoaded(series.volumeTwo.id)
        assertWithMessage(
            "the next volume started on its own with ask-before-a-suggestion on. That " +
                "setting exists so nothing new begins without being asked.",
        ).that(awaitPlaying(cued, timeoutMs = SETTLE_MS)).isFalse()

        val head = runBlocking {
            requireNotNull(database).queueDao().head(series.account.serverId, series.account.userId)
        }
        assertWithMessage("nothing was cued at the head of the queue").that(head).isNotNull()
        val cuedEntry = head!!
        assertWithMessage("something other than the next volume was cued")
            .that(cuedEntry.libraryItemId)
            .isEqualTo(series.volumeTwo.id)
        assertWithMessage("the cued entry was not marked as a suggestion")
            .that(cuedEntry.source)
            .isEqualTo(QueueSource.AUTO)
        Log.i(TAG, "the next volume was cued at the head of the queue and did not start")
    }

    /**
     * The next volume arrives at the speed it was last listened at, not at 1x.
     *
     * The end-of-book continuation was the fourth way an item reaches the player and the
     * only one that never asked what speed it should be at, so a volume with a
     * remembered speed came up at whatever the player happened to hold. That is audible
     * from the first word, and it is worst in a car, where the listener has to find a
     * speed control while driving to undo it.
     *
     * The speed is set on volume **two** while volume one is still playing, so the value
     * cannot have been inherited from the player: volume one is playing at its own speed
     * throughout, and only a store the service reads can produce this number.
     */
    @Test
    fun the_next_volume_arrives_at_its_own_remembered_speed() {
        val series = prepare(askFirst = false)

        val prefs = PlaybackPrefs(context.applicationContext)
        runBlocking {
            speedToRestore = prefs.speedFor(series.volumeTwo.id, MediaType.BOOK)
            speedItemToRestore = series.volumeTwo.id
            prefs.setSpeedFor(series.volumeTwo.id, MediaType.BOOK, REMEMBERED_SPEED)
        }

        playToTheEndOf(series.volumeOne)

        val player = awaitLoaded(series.volumeTwo.id)
        val speed = awaitSpeed(player, REMEMBERED_SPEED)
        assertWithMessage(
            "the next volume began at ${speed}x when it was last listened at " +
                "${REMEMBERED_SPEED}x. A book that starts by itself at the wrong speed is a " +
                "worse first impression than one that does not start at all.",
        ).that(speed).isWithin(SPEED_EPSILON).of(REMEMBERED_SPEED)
        Log.i(TAG, "the next volume began at its own remembered speed")
    }

    // -----------------------------------------------------------------------------------

    private data class Series(
        val account: ServerEntity,
        val volumeOne: LibraryItemEntity,
        val volumeTwo: LibraryItemEntity,
    )

    /**
     * Signs in, finds the two volumes, and puts the device in a known state.
     *
     * A missing server skips, which is the rule the whole suite follows and the only thing
     * a developer without a container can do. A missing *series* fails: the fixture being
     * absent is the reason this join was never observed, so a run that cannot find one has
     * to say so rather than report green.
     */
    private fun prepare(askFirst: Boolean): Series {
        assumeTrue(TestServerConfig.NO_SERVER, TestServerConfig.hasServer)
        assertWithMessage(TestServerConfig.NO_SERIES)
            .that(TestServerConfig.seriesQuery.isNotBlank())
            .isTrue()

        signIn()

        val db = LuguDatabase.build(context).also { database = it }
        val account = awaitAccount(db)
        val volumes = awaitTheSeriesMirrored(db, account)

        val one = volumes[0]
        val two = volumes[1]
        assertSequencesAreInOrder(db, account, one, two)

        // Server first, then Room. The other order lets a pull land between the two and
        // put back what was just cleared.
        TestServerProgress.clear(listOf(one.id, two.id))
        clearProgressInRoom(db, account, listOf(one.id, two.id))

        val queueDao = db.queueDao()
        queueToRestore = runBlocking {
            queueDao.all(account.serverId, account.userId).also {
                // A queue entry outranks every continuation rule, so anything left in it —
                // including the entry the ask-first test cues — would answer this test's
                // question before the series was ever consulted.
                queueDao.clear(account.serverId, account.userId)
            }
        }

        val prefs = QueuePrefs(context.applicationContext)
        settingsToRestore = runBlocking {
            prefs.current().also {
                prefs.setContinueSeries(true)
                prefs.setAskBeforeSuggestion(askFirst)
            }
        }

        return Series(account, one, two)
    }

    /**
     * Signs in through the screen, exactly as [PlaybackResumptionTest] does.
     *
     * The fields are prefilled from `BuildConfig` on a debug build, so this is one tap and
     * it proves the sign-in path before anything else is asserted.
     */
    private fun signIn() {
        compose.waitUntil(UI_TIMEOUT_MS) {
            compose.onAllNodes(hasText(SIGN_IN_PROMPT)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasContentDescription("Settings")).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodes(hasText(SIGN_IN_PROMPT)).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Sign in").performClick()
        }
        compose.waitUntil(UI_TIMEOUT_MS) {
            compose.onAllNodes(hasContentDescription("Settings")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitAccount(db: LuguDatabase): ServerEntity {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            runBlocking { db.serverDao().active() }?.let { return it }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("The sign-in left no active account behind after ${UI_TIMEOUT_MS}ms.")
    }

    /**
     * Waits for the series to reach Room, and answers with its numbered volumes in order.
     *
     * `bySeriesNumbered` is asked rather than the item table, because it is the same join
     * the continuation rule reads: it keeps only the volumes this series gives a number
     * to. A series whose members arrived with a null sequence therefore comes back short
     * here, and this says so — which is the exact shape the missing fixture had.
     */
    private fun awaitTheSeriesMirrored(db: LuguDatabase, account: ServerEntity): List<LibraryItemEntity> {
        // The Library tab is what starts the first sync, and the series listing is part of
        // it. Signing in lands on Home, where a new account's Room stays empty.
        compose.onNodeWithText(LIBRARY_TAB).performClick()

        var found = emptyList<LibraryItemEntity>()
        val deadline = System.currentTimeMillis() + SYNC_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            found = runBlocking {
                db.libraryItemDao()
                    .bySeriesNumbered(account.serverId, account.userId, TestServerConfig.seriesQuery)
            }
            if (found.size >= 2) return found
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(
            "The series named by lugu.test.seriesQuery reached Room with ${found.size} " +
                "numbered volume(s) after ${SYNC_TIMEOUT_MS}ms, and two are needed. Either the " +
                "series is not on this server, or its volumes carry no sequence — a membership " +
                "with a null sequence is one this rule ignores, which is how a green run can " +
                "mean nothing at all. scripts/seed-test-server.sh builds a series that works.",
        )
    }

    /** The numbers themselves, because "in order" is the claim the whole rule rests on. */
    private fun assertSequencesAreInOrder(
        db: LuguDatabase,
        account: ServerEntity,
        one: LibraryItemEntity,
        two: LibraryItemEntity,
    ) {
        val first = sequenceOf(db, account, one)
        val second = sequenceOf(db, account, two)
        assertWithMessage("the first volume of the series has no sequence").that(first).isNotNull()
        assertWithMessage("the second volume of the series has no sequence").that(second).isNotNull()
        assertWithMessage("the two volumes are not in sequence order")
            .that(second!! > first!!)
            .isTrue()
    }

    private fun sequenceOf(db: LuguDatabase, account: ServerEntity, item: LibraryItemEntity): Double? =
        runBlocking {
            db.itemSeriesDao()
                .forItem(account.serverId, account.userId, item.id)
                .firstOrNull { it.seriesName == TestServerConfig.seriesQuery }
                ?.sequence
        }

    /**
     * Puts both volumes back to unstarted in the mirror.
     *
     * The row is rewritten rather than removed, because there is no delete on `ProgressDao`
     * and none is wanted: what the continuation rule asks is whether anybody has started
     * the book, and a row reading zero and unfinished answers no. `isDirty` is cleared so
     * that nothing pushes this rewrite back up as if a listener had done it.
     */
    private fun clearProgressInRoom(db: LuguDatabase, account: ServerEntity, itemIds: List<String>) {
        runBlocking {
            itemIds.forEach { id ->
                val existing = db.progressDao().get(account.serverId, account.userId, id, "") ?: return@forEach
                db.progressDao().upsert(
                    existing.copy(
                        currentTimeSec = 0.0,
                        progress = 0.0,
                        isFinished = false,
                        isDirty = false,
                    ),
                )
            }
        }
    }

    /**
     * Starts the volume, walks it to its last few seconds, and lets it run out.
     *
     * The walk is `ProcessDeathResumptionTest`'s, for the reason commit dfde4e1 records: one
     * large blind skip against a short book runs off its end, which stops playback, which
     * makes every later assertion describe something that already finished. So the position
     * is checked after every step, the step is never allowed past the tail, and a book too
     * short to walk says so instead of ending early and blaming the next assertion.
     */
    private fun playToTheEndOf(volume: LibraryItemEntity) {
        context.sendBroadcast(
            Intent("${BuildConfig.APPLICATION_ID}.action.PLAY_SEARCH")
                .setPackage(BuildConfig.APPLICATION_ID)
                .putExtra("query", volume.title),
        )

        val player = awaitLoaded(volume.id)
        // The session the broadcast builds may replace one that is already holding this
        // same volume — a run that has been here before leaves it loaded — and a seek sent
        // into the old one lands nowhere. So the load is allowed to settle and then read
        // again, rather than being acted on the instant the right id appears.
        Thread.sleep(LOAD_SETTLE_MS)
        assertWithMessage("the volume was replaced while it was being started")
            .that(loadedItemOf(player))
            .isEqualTo(volume.id)

        onMain {
            player.seekTo(0)
            player.play()
        }
        assertWithMessage("the first volume would not play from its beginning")
            .that(awaitPlaying(player))
            .isTrue()

        walkToTheTail(player)
    }

    /** Short steps towards the tail, each one proved by the position it left behind. */
    private fun walkToTheTail(player: MediaController) {
        repeat(MAX_STEPS) {
            val duration = durationOf(player)
            assertWithMessage(
                "the volume reports no duration, so there is no end to walk towards",
            ).that(duration).isGreaterThan(0L)

            val here = positionOf(player)
            val target = min(here + STEP_MS, duration - TAIL_MS)
            if (target <= here) return

            onMain { player.seekTo(target) }
            val landed = awaitPosition(player, atLeast = target - SEEK_SLACK_MS)
            assertWithMessage(
                "the volume stopped while it was being walked towards its end, at ${here}ms of " +
                    "${duration}ms. Point lugu.test.seriesQuery at a series whose volumes are " +
                    "longer than ${TAIL_MS / 1000}s.",
            ).that(landed).isTrue()
        }
        throw AssertionError(
            "the volume was still not within ${TAIL_MS}ms of its end after $MAX_STEPS steps of " +
                "${STEP_MS}ms. Point lugu.test.seriesQuery at a shorter series: this test waits " +
                "for a real end rather than posting one.",
        )
    }

    /** Waits for the session to be holding this item, whether or not it is playing yet. */
    private fun awaitLoaded(itemId: String): MediaController {
        val player = connectController()
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (loadedItemOf(player) == itemId) return player
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(
            "the expected item was not loaded after ${LOAD_TIMEOUT_MS}ms. The item is named " +
                "by its id rather than printed, because a failure message ends up in a CI log." +
                "\n\nWhat the service decided, from its own diary:\n" + continuationDiary(),
        )
    }

    /**
     * What the service recorded about continuing, read out of its own diary.
     *
     * A CI run failed this way once and left nothing behind to read: the run's logcat is
     * not retained, and the service recorded nothing at all unless it succeeded. It
     * records every outcome now, so the failure that used to be a gap can carry its own
     * evidence — which of "the queue had nothing", "attempt 2 of 3 failed" and "gave up"
     * it was.
     *
     * The diary is a file in lugu's own `filesDir`, and this test runs in lugu's process,
     * so it is read directly rather than through the class that writes it. Only the
     * continuation lines are reported: the rest of a long drive is noise here, and a
     * failure message must not print anything naming what is on somebody's shelf.
     */
    private fun continuationDiary(): String {
        val file = java.io.File(context.filesDir, DIARY_FILE)
        if (!file.exists()) return "  (no diary file at ${file.name})"
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
            .map { it.split(DIARY_SEPARATOR) }
            .filter { it.size >= 2 && it[1] in CONTINUATION_EVENTS }
            .takeLast(DIARY_LINES)
            .map { parts -> "  ${parts[1]}${parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()}" }
        return if (lines.isEmpty()) "  (the diary holds no continuation lines)" else lines.joinToString("\n")
    }

    private fun loadedItemOf(player: MediaController): String? {
        var mediaId: String? = null
        onMain { mediaId = player.currentMediaItem?.mediaId }
        return mediaId?.let { MediaResolver.parseMediaId(it)?.first }
    }

    private fun awaitPlaying(player: MediaController, timeoutMs: Long = PLAYBACK_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var playing = false
            onMain { playing = player.isPlaying }
            if (playing) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    private fun awaitPosition(player: MediaController, atLeast: Long): Boolean {
        val deadline = System.currentTimeMillis() + SEEK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            var playing = false
            onMain { playing = player.isPlaying }
            if (playing && positionOf(player) >= atLeast) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    private fun connectController(): MediaController {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, LuguPlaybackService::class.java))
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        onMain { future = MediaController.Builder(context, token).buildAsync() }
        return future.get(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS).also { controller = it }
    }

    private fun releaseController() {
        val held = controller ?: return
        controller = null
        onMain { held.release() }
    }

    /** Every [MediaController] member has to be touched on the thread that built it. */
    private fun positionOf(player: MediaController): Long {
        var position = 0L
        onMain { position = player.currentPosition }
        return position
    }

    private fun durationOf(player: MediaController): Long {
        var duration = 0L
        onMain { duration = player.duration.takeIf { it > 0 } ?: 0L }
        return duration
    }

    /**
     * The speed, once it settles.
     *
     * `awaitLoaded` returns as soon as the media id changes, and the speed is applied on
     * the same coroutine a beat either side of that. Reading once would be a race on
     * which of the two the controller reports first, so this waits for the value and
     * reports whatever it last saw when it does not arrive.
     */
    private fun awaitSpeed(player: MediaController, wanted: Float): Float {
        var seen = speedOf(player)
        val deadline = System.currentTimeMillis() + SPEED_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            seen = speedOf(player)
            if (kotlin.math.abs(seen - wanted) < SPEED_EPSILON) return seen
            Thread.sleep(POLL_MS)
        }
        return seen
    }

    private fun speedOf(player: MediaController): Float {
        var speed = 0f
        onMain { speed = player.playbackParameters.speed }
        return speed
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private companion object {
        const val TAG = "LuguSeries"

        /**
         * A speed no default produces, so a book at this rate got it from the store.
         *
         * It is also one of the seven rates the car button can draw, which keeps the
         * fixture honest about a value a listener could really be on.
         */
        const val REMEMBERED_SPEED = 1.5f
        const val SPEED_EPSILON = 0.05f
        const val SPEED_TIMEOUT_MS = 15_000L

        /**
         * How the diary is stored, spelled out rather than imported.
         *
         * `PlaybackDiary` keeps the file name and the separator private, and prising them
         * open so a test could read a file would make the storage format part of its
         * contract. Repeating those two is the cheaper mistake: if the format changes, this
         * reports an empty diary instead of lying about one. The event names are public and
         * are imported, because those are the part that has to match exactly.
         */
        const val DIARY_FILE = "playback-diary.log"
        const val DIARY_SEPARATOR = "\u001F"
        const val DIARY_LINES = 12

        /** The two continuation events: the one that worked, and the one that did not. */
        val CONTINUATION_EVENTS = setOf(
            PlaybackEvent.CONTINUATION,
            PlaybackEvent.CONTINUATION_NONE,
        )
        
        const val SIGN_IN_PROMPT = "Sign in to your Audiobookshelf server"
        const val LIBRARY_TAB = "Library"

        const val UI_TIMEOUT_MS = 30_000L
        const val POLL_MS = 250L
        const val CONNECT_TIMEOUT_SEC = 20L

        /** The same measured wait [PlaybackResumptionTest] uses for a first cold mirror. */
        const val SYNC_TIMEOUT_MS = 180_000L

        /** A first buffer, and — after the end — a resolve, a load and a first buffer again. */
        const val LOAD_TIMEOUT_MS = 60_000L
        const val PLAYBACK_TIMEOUT_MS = 45_000L
        const val SEEK_TIMEOUT_MS = 15_000L

        /**
         * How long a cued volume is watched for to see whether it starts on its own.
         *
         * Long enough for a player that was told to play to have done it — it is already
         * prepared by the time this runs — and short enough not to add a minute to the run.
         */
        const val SETTLE_MS = 8_000L

        /** Long enough for the session the broadcast asked for to have replaced any other. */
        const val LOAD_SETTLE_MS = 3_000L

        /**
         * The tail the book is left to play out on its own, and the steps that get it there.
         *
         * The tail has to be long enough that the walk finishes before the audio does, and
         * short enough that the test is not waiting on real time it does not need. Four
         * seconds of a seeded twenty-five-second volume is both.
         */
        const val TAIL_MS = 4_000L
        const val STEP_MS = 5_000L
        const val SEEK_SLACK_MS = 1_500L
        const val MAX_STEPS = 40
    }
}

/**
 * Puts a book back to unstarted on the server, so that this test can be run twice.
 *
 * lugu has no reason to expose "forget that I ever opened this", and the server does: the
 * progress row is a row, and `DELETE /api/me/progress/{progressId}` removes it. That id is
 * the row's own, not the item's — a delete addressed by item id answers 404, which is the
 * kind of thing that has to be tried against a running server rather than reasoned about.
 *
 * Written against `HttpURLConnection` rather than lugu's own client on purpose: this is the
 * test arranging the world, and it should not be able to pass because the client under test
 * agreed with itself about what happened.
 *
 * Nothing here is logged. The address and the credentials come from `BuildConfig` and stay
 * there.
 */
private object TestServerProgress {

    fun clear(itemIds: List<String>) {
        val token = login()
        progressIds(token, itemIds.toSet()).forEach { progressId ->
            send("DELETE", "/api/me/progress/$progressId", token, body = null)
        }
    }

    private fun login(): String {
        val body = JSONObject()
            .put("username", TestServerConfig.username)
            .put("password", TestServerConfig.password)
            .toString()
        val user = JSONObject(send("POST", "/login", token = null, body = body)).getJSONObject("user")
        val token = user.optString("accessToken").ifBlank { user.optString("token") }
        if (token.isBlank()) throw AssertionError("The test server accepted the sign-in but returned no token.")
        return token
    }

    private fun progressIds(token: String, itemIds: Set<String>): List<String> {
        val rows = JSONObject(send("GET", "/api/me", token, body = null)).optJSONArray("mediaProgress")
            ?: return emptyList()
        return (0 until rows.length())
            .map { rows.getJSONObject(it) }
            .filter { it.optString("libraryItemId") in itemIds }
            .map { it.optString("id") }
            .filter { it.isNotBlank() }
    }

    private fun send(method: String, path: String, token: String?, body: String?): String {
        val connection = URL(TestServerConfig.url.trimEnd('/') + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                // The path is named and the response body is not: an error page from a
                // server nobody here owns is not something to copy into a CI log.
                throw AssertionError("The test server answered $code to $method on a progress call.")
            }
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MS = 15_000
}
