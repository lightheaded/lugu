package io.github.lightheaded.lugu.core.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sweep that keeps the mirror honest, and the reason two passes may never overlap.
 *
 * A mirror pass stamps every row it writes with the time it started and then deletes
 * everything older, which is how a book removed on the server disappears from the phone
 * without anyone sending an event. It is correct exactly once at a time.
 *
 * This is the mechanism behind a failure that looked like nothing at all: an instrumented
 * test reporting that a title never reached Room. Signing in had begun a mirror of its own
 * while the Library tab was beginning one, and the two deleted each other's work. The lock
 * that prevents it is in `LibraryRepository.syncLibraryItems`; what is asserted here is why
 * that lock cannot be removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryMirrorSweepTest {

    private lateinit var db: LuguDatabase
    private val serverId = "s"
    private val userId = "u"
    private val libraryId = "lib1"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LuguDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /**
     * The interleaving, written out as the two passes would perform it.
     *
     * The later pass sweeps against *its* start time, and by then the earlier pass has
     * restamped the row with a time before that — so the row reads as stale and goes. The
     * earlier pass has already walked past it and never puts it back.
     */
    @Test
    fun `a later pass sweeps away what an earlier one restamped`() = runTest {
        val dao = db.libraryItemDao()
        val early = 1_000L
        val late = 2_000L

        // The later pass writes the book and is about to sweep.
        dao.upsertAll(listOf(book(syncedAtMs = late)))
        // The earlier pass, still walking, writes the same book with its own older stamp.
        dao.upsertAll(listOf(book(syncedAtMs = early)))

        dao.deleteStale(serverId, userId, libraryId, before = late)

        assertThat(dao.countInLibrary(serverId, userId, libraryId)).isEqualTo(0)
    }

    @Test
    fun `one pass on its own keeps what it wrote and drops what the server no longer has`() = runTest {
        val dao = db.libraryItemDao()
        dao.upsertAll(
            listOf(
                book(id = "kept", syncedAtMs = 2_000L),
                book(id = "gone-from-the-server", syncedAtMs = 1_000L),
            ),
        )

        dao.deleteStale(serverId, userId, libraryId, before = 2_000L)

        assertThat(dao.countInLibrary(serverId, userId, libraryId)).isEqualTo(1)
        assertThat(dao.byId(serverId, userId, "kept")).isNotNull()
    }

    /** The sweep is per library, so mirroring one may not empty another. */
    @Test
    fun `a sweep leaves another library alone`() = runTest {
        val dao = db.libraryItemDao()
        dao.upsertAll(
            listOf(
                book(id = "here", syncedAtMs = 1_000L),
                book(id = "elsewhere", syncedAtMs = 1_000L, libraryId = "lib2"),
            ),
        )

        dao.deleteStale(serverId, userId, libraryId, before = 2_000L)

        assertThat(dao.countInLibrary(serverId, userId, libraryId)).isEqualTo(0)
        assertThat(dao.countInLibrary(serverId, userId, "lib2")).isEqualTo(1)
    }

    private fun book(
        id: String = "lighthouse-falls",
        syncedAtMs: Long,
        libraryId: String = this.libraryId,
    ) = LibraryItemEntity(
        serverId = serverId,
        userId = userId,
        id = id,
        libraryId = libraryId,
        mediaType = "BOOK",
        title = "Lighthouse Falls",
        subtitle = null,
        authorName = "Marisol Fen",
        narratorName = null,
        seriesName = null,
        seriesTitle = null,
        seriesSequence = null,
        description = null,
        durationSec = 3_600.0,
        sizeBytes = 1_000L,
        numEpisodes = 0,
        addedAtMs = 1_000L,
        updatedAtMs = 1_000L,
        coverPath = null,
        rawJson = null,
        syncedAtMs = syncedAtMs,
    )
}
