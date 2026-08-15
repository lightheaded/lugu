package io.github.lightheaded.lugu.core.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The computed shelves, exercised against real SQLite.
 *
 * These are pure SQL, which means the compiler cannot check any of the reasoning in
 * them — the difference between "unstarted" and "not finished", or between ordering by
 * a series name and by its number, is invisible until it is run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShelfQueryTest {

    private lateinit var db: LuguDatabase
    private val serverId = "s"
    private val userId = "u"
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LuguDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun item(
        id: String,
        title: String = "Title $id",
        mediaType: String = "BOOK",
        seriesName: String? = null,
        seriesTitle: String? = null,
        seriesSequence: Double? = null,
        durationSec: Double = 36_000.0,
        addedAtMs: Long = 0,
    ) = LibraryItemEntity(
        serverId = serverId,
        userId = userId,
        id = id,
        libraryId = "lib1",
        mediaType = mediaType,
        title = title,
        subtitle = null,
        authorName = null,
        narratorName = null,
        seriesName = seriesName,
        seriesTitle = seriesTitle,
        seriesSequence = seriesSequence,
        description = null,
        durationSec = durationSec,
        sizeBytes = 0,
        numEpisodes = 0,
        addedAtMs = addedAtMs,
        updatedAtMs = 0,
        coverPath = null,
        rawJson = null,
        syncedAtMs = 0,
    )

    private fun progress(
        itemId: String,
        progress: Double,
        isFinished: Boolean = false,
        lastUpdateMs: Long = now,
        episodeId: String? = null,
    ) = ProgressEntity(
        serverId = serverId,
        userId = userId,
        libraryItemId = itemId,
        episodeKey = episodeKeyOf(episodeId),
        currentTimeSec = progress * 36_000.0,
        durationSec = 36_000.0,
        progress = progress,
        isFinished = isFinished,
        lastUpdateMs = lastUpdateMs,
        startedAtMs = 0,
        serverLastUpdateMs = 0,
        isDirty = false,
    )

    @Test
    fun `almost finished holds only the nearly-done and unfinished`() = runTest {
        db.libraryItemDao().upsertAll(listOf(item("a"), item("b"), item("c"), item("d")))
        db.progressDao().upsertAll(
            listOf(
                progress("a", 0.95),
                progress("b", 0.5),
                progress("c", 1.0, isFinished = true),
                progress("d", 0.92),
            ),
        )

        val rows = db.libraryItemDao().observeAlmostFinished(serverId, userId).first()

        assertThat(rows.map { it.id }).containsExactly("a", "d")
        // Nearest the end first: the shortest way to clear one.
        assertThat(rows.first().id).isEqualTo("a")
    }

    @Test
    fun `pick it back up ignores books touched recently`() = runTest {
        val fortnight = 14L * 24 * 60 * 60 * 1000
        db.libraryItemDao().upsertAll(listOf(item("stale"), item("fresh"), item("nearlydone")))
        db.progressDao().upsertAll(
            listOf(
                progress("stale", 0.3, lastUpdateMs = now - fortnight - 1),
                progress("fresh", 0.3, lastUpdateMs = now - 1000),
                // Almost finished belongs on its own shelf, not this one.
                progress("nearlydone", 0.97, lastUpdateMs = now - fortnight - 1),
            ),
        )

        val rows = db.libraryItemDao().observeStale(serverId, userId, staleBeforeMs = now - fortnight).first()

        assertThat(rows.map { it.id }).containsExactly("stale")
    }

    @Test
    fun `short listens are unstarted and short`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(
                item("short", durationSec = 2 * 3600.0),
                item("long", durationSec = 20 * 3600.0),
                item("shortstarted", durationSec = 2 * 3600.0),
                item("shortpodcast", mediaType = "PODCAST", durationSec = 2 * 3600.0),
            ),
        )
        db.progressDao().upsert(progress("shortstarted", 0.1))

        val rows = db.libraryItemDao().observeShortListens(serverId, userId).first()

        assertThat(rows.map { it.id }).containsExactly("short")
    }

    /**
     * The case the parsed sequence column exists for. Ordered by name, "#10" sorts before
     * "#2", so a shelf reading the name would recommend book ten to someone who has just
     * finished book one.
     */
    @Test
    fun `next in series picks the lowest unstarted number, not the lowest name`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(
                item("v1", seriesName = "Riverton #1", seriesTitle = "Riverton", seriesSequence = 1.0),
                item("v2", seriesName = "Riverton #2", seriesTitle = "Riverton", seriesSequence = 2.0),
                item("v10", seriesName = "Riverton #10", seriesTitle = "Riverton", seriesSequence = 10.0),
            ),
        )
        db.progressDao().upsert(progress("v1", 1.0, isFinished = true))

        val rows = db.libraryItemDao().observeNextInSeries(serverId, userId).first()

        assertThat(rows.map { it.id }).containsExactly("v2")
    }

    @Test
    fun `next in series stays quiet until something in the series is finished`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(
                item("v1", seriesName = "Riverton #1", seriesTitle = "Riverton", seriesSequence = 1.0),
                item("v2", seriesName = "Riverton #2", seriesTitle = "Riverton", seriesSequence = 2.0),
            ),
        )
        // Started but not finished: recommending the next one now would be presumptuous.
        db.progressDao().upsert(progress("v1", 0.4))

        assertThat(db.libraryItemDao().observeNextInSeries(serverId, userId).first()).isEmpty()
    }

    @Test
    fun `next in series offers one book per series`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(
                item("a1", seriesName = "A #1", seriesTitle = "A", seriesSequence = 1.0),
                item("a2", seriesName = "A #2", seriesTitle = "A", seriesSequence = 2.0),
                item("a3", seriesName = "A #3", seriesTitle = "A", seriesSequence = 3.0),
                item("b1", seriesName = "B #1", seriesTitle = "B", seriesSequence = 1.0),
                item("b2", seriesName = "B #2", seriesTitle = "B", seriesSequence = 2.0),
            ),
        )
        db.progressDao().upsertAll(
            listOf(progress("a1", 1.0, isFinished = true), progress("b1", 1.0, isFinished = true)),
        )

        val rows = db.libraryItemDao().observeNextInSeries(serverId, userId).first()

        assertThat(rows.map { it.id }).containsExactly("a2", "b2")
        // A duplicate id here is a Compose crash, not a cosmetic problem.
        assertThat(rows.map { it.id }).containsNoDuplicates()
    }

    /**
     * Roughly a third of this library's series entries have no parseable number. Guessing
     * an order for them would be worse than leaving them out — it risks a spoiler.
     */
    @Test
    fun `a series with no number is left out rather than guessed at`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(
                item("c1", seriesName = "The Tidelands", seriesTitle = "The Tidelands", seriesSequence = null),
                item("c2", seriesName = "The Tidelands", seriesTitle = "The Tidelands", seriesSequence = null),
            ),
        )
        db.progressDao().upsert(progress("c1", 1.0, isFinished = true))

        assertThat(db.libraryItemDao().observeNextInSeries(serverId, userId).first()).isEmpty()
    }

    @Test
    fun `downloaded holds completed downloads, newest first`() = runTest {
        db.libraryItemDao().upsertAll(listOf(item("a"), item("b"), item("c")))
        db.downloadDao().upsert(download("a", DownloadState.COMPLETED, completedAtMs = 100))
        db.downloadDao().upsert(download("b", DownloadState.DOWNLOADING))
        db.downloadDao().upsert(download("c", DownloadState.COMPLETED, completedAtMs = 200))

        val rows = db.libraryItemDao().observeDownloaded(serverId, userId).first()

        assertThat(rows.map { it.id }).containsExactly("c", "a").inOrder()
    }

    /**
     * A podcast has one download row per episode. Joining on the item alone returns the
     * podcast once per downloaded episode — the same fan-out that once crashed the
     * continue-listening shelf, because Compose throws on a duplicate key.
     */
    @Test
    fun `a podcast with several downloaded episodes appears exactly once`() = runTest {
        db.libraryItemDao().upsertAll(listOf(item("pod", mediaType = "PODCAST")))
        db.downloadDao().upsert(download("pod", DownloadState.COMPLETED, episodeKey = "ep1", completedAtMs = 10))
        db.downloadDao().upsert(download("pod", DownloadState.COMPLETED, episodeKey = "ep2", completedAtMs = 20))

        val rows = db.libraryItemDao().observeDownloaded(serverId, userId).first()

        assertThat(rows.map { it.id }).containsExactly("pod")
    }

    /**
     * The shelves used to be account-wide while the grid below them was scoped to the
     * selected library, so picking the audiobook library filtered the grid and left
     * podcasts on the shelves directly above it — which reads as the filter being broken.
     * Passing a library id has to actually narrow every shelf, and passing none has to
     * still span everything, because both are real choices the settings screen offers.
     */
    @Test
    fun `a library id narrows every shelf, and no library id spans them`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(
                item("book", durationSec = 3_600.0),
                item("pod", mediaType = "PODCAST").copy(libraryId = "lib2"),
            ),
        )
        db.progressDao().upsertAll(
            listOf(
                progress("book", 0.4),
                progress("pod", 0.4, episodeId = "ep1"),
            ),
        )
        db.downloadDao().upsert(download("book", DownloadState.COMPLETED, completedAtMs = 10))
        db.downloadDao().upsert(
            download("pod", DownloadState.COMPLETED, episodeKey = "ep1", completedAtMs = 20),
        )

        val dao = db.libraryItemDao()
        assertThat(dao.observeContinueListening(serverId, userId, "lib1").first().map { it.id })
            .containsExactly("book")
        assertThat(dao.observeContinueListening(serverId, userId, "lib2").first().map { it.id })
            .containsExactly("pod")
        assertThat(dao.observeContinueListening(serverId, userId).first().map { it.id })
            .containsExactly("book", "pod")

        assertThat(dao.observeDownloaded(serverId, userId, "lib2").first().map { it.id })
            .containsExactly("pod")
        assertThat(dao.observeStale(serverId, userId, staleBeforeMs = now + 1, libraryId = "lib1")
            .first().map { it.id }).containsExactly("book")
    }

    private fun download(
        itemId: String,
        state: String,
        episodeKey: String = "",
        completedAtMs: Long = 0,
    ) = DownloadEntity(
        serverId = serverId,
        userId = userId,
        libraryItemId = itemId,
        episodeKey = episodeKey,
        title = "Title $itemId",
        author = null,
        mediaType = "BOOK",
        state = state,
        tracksJson = """{"tracks":[]}""",
        durationSec = 0.0,
        bytesTotal = 0,
        bytesDownloaded = 0,
        percent = if (state == DownloadState.COMPLETED) 1f else 0.5f,
        requestedAtMs = 0,
        completedAtMs = completedAtMs,
        error = null,
    )
}
