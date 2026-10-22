package io.github.lightheaded.lugu

import android.content.Context
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertWithMessage
import io.github.lightheaded.lugu.core.db.LibraryItemEntity
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.db.ProgressEntity
import io.github.lightheaded.lugu.core.db.episodeKeyOf
import io.github.lightheaded.lugu.core.download.AutoDownloadWorker
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.DownloadPrefs
import io.github.lightheaded.lugu.core.sync.DownloadSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The auto-download rules, forced once against a real library.
 *
 * The backlog row "Auto-download rules have never run against a real library" is the gap:
 * `AutoDownloader` has rules, and the worker that applies them fires every six hours on
 * unmetered power, which is a slow way to find out it is wrong. This test enqueues the
 * production worker once, through the app's own WorkManager and so through Hilt's worker
 * factory, and reads which downloads it asked for.
 *
 * ### The rule it forces
 *
 * "Next in series", with a count of one, and every other rule off. The fixture is the
 * series in `lugu.test.seriesQuery`: volume 1 is started and volume 2 is not, so the rule
 * has exactly one right answer — volume 2, and not volume 1. The series and its sequence
 * numbers come from the server's scan of the seeded folders, so the join the rule reads
 * is the real one and not rows this test wrote.
 *
 * The queue rule and the podcast rule are not forced. The queue rule reads a table that
 * only this device writes, so a real library adds nothing to it. The podcast rule needs a
 * podcast that a test may mark as listened to, and there is no consent key for one yet.
 *
 * ### What it depends on in `:core:download`
 *
 * `AutoDownloadWorker` runs the rules after an early return when no rule is on, and skips
 * the podcast refresh when the episode rule and the notification are off. `AutoDownloader`
 * reads a volume as started from the Room `progress` row with an empty episode key and a
 * position above zero, and leaves alone anything with a `download` row already. And
 * `DownloadRepository.download` writes the `download` row before it requests a byte. A
 * change to any of those changes what this test sees.
 *
 * ### What it costs the server and the device
 *
 * Volume 1's position is set on the server, and cleared again afterwards. Any `download`
 * row for either volume is removed first, because the rule leaves alone an item that has
 * one, and every row the run creates is removed at the end. That is the consent the
 * series key already gives [NextInSeriesTest]: a test may treat those two books as its own.
 *
 * On a developer's own server the rule runs over every series on the account, as it does
 * in production, so it may also queue the next volume of another series that is being
 * read. Those rows are removed at the end too.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AutoDownloadRulesTest {

    @get:Rule(order = 0)
    val notifications = grantNotificationPermission()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val app: LuguApplication
        get() = context.applicationContext as LuguApplication

    private var database: LuguDatabase? = null
    private var account: ActiveAccount? = null
    private var settingsToRestore: DownloadSettings? = null
    /** Server-side progress as this test found it, per volume it changed; null is no row. */
    private val progressBefore = linkedMapOf<String, JSONObject?>()
    private val createdRows = mutableListOf<Pair<String, String>>()

    @After
    fun putEverythingBack() {
        val prefs = DownloadPrefs(context.applicationContext)
        settingsToRestore?.let { was ->
            runCatching {
                runBlocking {
                    prefs.setAutoDownloadQueue(was.autoDownloadQueue)
                    prefs.setAutoDownloadNextInSeries(was.autoDownloadNextInSeries)
                    prefs.setAutoDownloadLatestEpisodes(was.autoDownloadLatestEpisodes)
                    prefs.setNotifyNewEpisodes(was.notifyNewEpisodes)
                }
            }
        }

        val signedIn = account
        if (signedIn != null) {
            createdRows.forEach { (itemId, episodeKey) ->
                runCatching {
                    runBlocking {
                        app.downloadRepository.remove(signedIn, itemId, episodeKey.ifEmpty { null })
                    }
                }
            }
        }

        if (progressBefore.isNotEmpty()) {
            runCatching {
                val token = SeededServer.login()
                progressBefore.forEach { (itemId, before) -> SeededServer.restoreProgress(token, itemId, before) }
            }
            val db = database
            if (db != null && signedIn != null) {
                progressBefore.forEach { (itemId, before) ->
                    runCatching {
                        writeRoomProgress(
                            db,
                            signedIn,
                            itemId,
                            atSec = before?.optDouble("currentTime", 0.0) ?: 0.0,
                            finished = before?.optBoolean("isFinished") ?: false,
                        )
                    }
                }
            }
        }
        runCatching { database?.close() }
        database = null
    }

    @Test
    fun next_in_series_queues_the_volume_after_the_one_being_read_and_nothing_before_it() {
        assumeTrue(TestServerConfig.NO_SERVER, TestServerConfig.hasServer)
        // A failure and not a skip, as in NextInSeriesTest: a missing fixture is how this
        // rule went unobserved, so a run without one has to say so rather than go green.
        assertWithMessage(TestServerConfig.NO_SERIES)
            .that(TestServerConfig.seriesQuery.isNotBlank())
            .isTrue()

        compose.signInThroughTheScreen(UI_TIMEOUT_MS)

        val db = LuguDatabase.build(context).also { database = it }
        val signedIn = awaitAccount()
        val (one, two) = awaitTheSeriesMirrored(db, signedIn)

        // Neither volume may hold a row going in: the rule leaves alone anything that has
        // one, so a row from an earlier run would make the right answer look like a miss.
        runBlocking {
            app.downloadRepository.remove(signedIn, one.id, null)
            app.downloadRepository.remove(signedIn, two.id, null)
        }

        // Volume 1 started and volume 2 not: the one state with exactly one right answer.
        // Volume 2 is reset rather than checked, because NextInSeriesTest plays into it.
        setVolumeProgress(db, signedIn, one, atSec = STARTED_AT_SEC)
        setVolumeProgress(db, signedIn, two, atSec = 0.0)

        val prefs = DownloadPrefs(context.applicationContext)
        settingsToRestore = runBlocking {
            prefs.current().also {
                prefs.setAutoDownloadQueue(false)
                prefs.setAutoDownloadLatestEpisodes(0)
                prefs.setNotifyNewEpisodes(false)
                prefs.setAutoDownloadNextInSeries(1)
            }
        }

        val before = rowKeys(db, signedIn)
        runTheWorkerOnce()
        val after = rowKeys(db, signedIn)
        createdRows += (after - before)

        val created = after - before
        Log.i(TAG, "One pass of the rules created ${created.size} download row(s).")

        assertWithMessage(
            "After one pass of the next-in-series rule, volume 2 of the test series has no " +
                "download row. The rule did not choose it, or DownloadRepository refused it.",
        ).that(created).contains(two.id to episodeKeyOf(null))

        assertWithMessage(
            "The rule queued volume 1, which is the volume being read. It must only queue " +
                "what comes after the last started volume.",
        ).that(created).doesNotContain(one.id to episodeKeyOf(null))

        // Only this rule is on, so everything it queued is a book in some series, with no
        // episode. A podcast episode or a book outside a series here is another rule firing.
        val seriesMembers = runBlocking {
            db.libraryItemDao().seriesTitles(signedIn.serverId, signedIn.userId).flatMap { title ->
                db.libraryItemDao().bySeriesNumbered(signedIn.serverId, signedIn.userId, title).map { it.id }
            }.toSet()
        }
        created.forEach { (itemId, episodeKey) ->
            assertWithMessage("With only the series rule on, a row was created for something that is not a numbered series volume.")
                .that(itemId in seriesMembers && episodeKey.isEmpty())
                .isTrue()
        }
    }

    /**
     * Sets one volume's position: on the server first, then in Room.
     *
     * Server first for the reason [NextInSeriesTest] gives: the other order lets a pull land
     * between the two and put back what was just written. Room is written as well because
     * the rule reads Room, and a pull is not guaranteed to have run in the time this test
     * has. Zero on the server is a deleted row, which is what "unstarted" is there.
     */
    private fun setVolumeProgress(db: LuguDatabase, signedIn: ActiveAccount, volume: LibraryItemEntity, atSec: Double) {
        val token = SeededServer.login()
        if (volume.id !in progressBefore) progressBefore[volume.id] = SeededServer.progress(token, volume.id)
        if (atSec > 0.0) {
            SeededServer.setProgress(token, volume.id, atSec)
        } else {
            SeededServer.restoreProgress(token, volume.id, before = null)
        }
        writeRoomProgress(db, signedIn, volume.id, atSec, finished = false)
    }

    /** `isDirty` stays false, so nothing pushes this row back up as if a listener had made it. */
    private fun writeRoomProgress(db: LuguDatabase, signedIn: ActiveAccount, itemId: String, atSec: Double, finished: Boolean) {
        val now = System.currentTimeMillis()
        runBlocking {
            val existing = db.progressDao().get(signedIn.serverId, signedIn.userId, itemId, "")
            db.progressDao().upsert(
                existing?.copy(
                    currentTimeSec = atSec,
                    progress = 0.0,
                    isFinished = finished,
                    isDirty = false,
                ) ?: ProgressEntity(
                    serverId = signedIn.serverId,
                    userId = signedIn.userId,
                    libraryItemId = itemId,
                    episodeKey = "",
                    currentTimeSec = atSec,
                    durationSec = 0.0,
                    progress = 0.0,
                    isFinished = finished,
                    lastUpdateMs = now,
                    startedAtMs = now,
                    serverLastUpdateMs = 0L,
                    isDirty = false,
                ),
            )
        }
    }

    /**
     * Enqueues the production worker once, with no constraints, and waits for it to end.
     *
     * The periodic request is left alone: it is a unique work under its own name, and a
     * one-off request of the same class neither replaces it nor resets its period. Hilt's
     * worker factory builds it, because the app's WorkManager is configured with it.
     */
    private fun runTheWorkerOnce() {
        val request = OneTimeWorkRequestBuilder<AutoDownloadWorker>().build()
        val manager = WorkManager.getInstance(context)
        manager.enqueue(request).result.get(WORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        var info: WorkInfo? = null
        val deadline = System.currentTimeMillis() + WORK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            info = manager.getWorkInfoById(request.id).get(WORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (info?.state?.isFinished == true) break
            Thread.sleep(POLL_MS)
        }
        assertWithMessage(
            "The auto-download worker did not succeed within ${WORK_TIMEOUT_MS}ms. It asks for " +
                "a retry when the rules throw, which leaves it enqueued again.",
        ).that(info?.state).isEqualTo(WorkInfo.State.SUCCEEDED)
    }

    private fun rowKeys(db: LuguDatabase, signedIn: ActiveAccount): Set<Pair<String, String>> = runBlocking {
        db.downloadDao().allForAccount(signedIn.serverId, signedIn.userId)
            .map { it.libraryItemId to it.episodeKey }
            .toSet()
    }

    private fun awaitAccount(): ActiveAccount {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            runBlocking { app.authRepository.account() }?.let { return it.also { account = it } }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("The sign-in left no active account behind after ${UI_TIMEOUT_MS}ms.")
    }

    /** The first two numbered volumes of the test series, in reading order, once Room has them. */
    private fun awaitTheSeriesMirrored(db: LuguDatabase, signedIn: ActiveAccount): Pair<LibraryItemEntity, LibraryItemEntity> {
        // The Library tab starts a sync of its own, which is the faster of the two routes
        // into Room for a new account.
        compose.onNodeWithText(LIBRARY_TAB).performClick()

        var found = emptyList<LibraryItemEntity>()
        val deadline = System.currentTimeMillis() + SYNC_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            found = runBlocking {
                db.libraryItemDao().bySeriesNumbered(signedIn.serverId, signedIn.userId, TestServerConfig.seriesQuery)
            }
            if (found.size >= 2) return found[0] to found[1]
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(
            "The series named by lugu.test.seriesQuery reached Room with ${found.size} " +
                "numbered volume(s) after ${SYNC_TIMEOUT_MS}ms, and two are needed. " +
                "scripts/seed-test-server.sh builds a series that works.",
        )
    }

    private companion object {
        const val TAG = "LuguAutoDownload"
        const val LIBRARY_TAB = "Library"

        const val UI_TIMEOUT_MS = 30_000L
        const val POLL_MS = 250L

        /** The same measured wait [NextInSeriesTest] uses for a first cold mirror. */
        const val SYNC_TIMEOUT_MS = 180_000L

        /** One pass: an item fetch per queued volume, against a server on the same host. */
        const val WORK_TIMEOUT_MS = 60_000L

        /** Started, and well inside the seeded volume's twenty-five seconds. */
        const val STARTED_AT_SEC = 5.0
    }
}
