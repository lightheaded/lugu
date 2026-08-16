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
 * The write path for series membership, where three sources of different quality meet.
 *
 * The rules that matter are not expressible in a column type: a membership is a set and
 * not a row, a cheap source must not undo an authoritative one, and the sweep that keeps
 * the table honest must only run after a pass that actually finished. Each of those is a
 * way for a series page to end up wrong days later, with nothing in the code to point at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItemSeriesDaoTest {

    private lateinit var db: LuguDatabase
    private val serverId = "s"
    private val userId = "u"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LuguDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun membership(
        itemId: String,
        seriesName: String,
        sequence: Double? = null,
        serverRank: Int? = null,
        origin: Int = SeriesOrigin.SERVER,
        syncedAtMs: Long = 0,
        libraryId: String = "lib1",
    ) = ItemSeriesEntity(
        serverId = serverId,
        userId = userId,
        libraryItemId = itemId,
        libraryId = libraryId,
        seriesName = seriesName,
        seriesId = null,
        sequence = sequence,
        serverRank = serverRank,
        origin = origin,
        syncedAtMs = syncedAtMs,
    )

    private fun item(id: String, libraryId: String = "lib1") = LibraryItemEntity(
        serverId = serverId,
        userId = userId,
        id = id,
        libraryId = libraryId,
        mediaType = "BOOK",
        title = "Title $id",
        subtitle = null,
        authorName = null,
        narratorName = null,
        seriesName = null,
        seriesTitle = null,
        seriesSequence = null,
        description = null,
        durationSec = 3_600.0,
        sizeBytes = 0,
        numEpisodes = 0,
        addedAtMs = 0,
        updatedAtMs = 0,
        coverPath = null,
        rawJson = null,
        syncedAtMs = 0,
    )

    /**
     * A membership is a set, which is why the write is a replace and not an upsert.
     *
     * Somebody who moves a book out of one series on the server and into another expects
     * both changes. An upsert would deliver only the second, and the book would sit on the
     * old series page until the mirror was deleted.
     */
    @Test
    fun `replacing an item's series drops the ones it is no longer in`() = runTest {
        val dao = db.itemSeriesDao()
        dao.replaceForItems(
            serverId,
            userId,
            listOf("falls"),
            listOf(membership("falls", "The Breakwater", 2.0), membership("falls", "Riverton", 1.0)),
        )

        dao.replaceForItems(serverId, userId, listOf("falls"), listOf(membership("falls", "Riverton", 1.0)))

        assertThat(dao.forItem(serverId, userId, "falls").map { it.seriesName })
            .containsExactly("Riverton")
    }

    /**
     * The guard that keeps the paged sync from undoing the series listing.
     *
     * The paged sync runs on every app open and can only parse the joined string. Without
     * this, a book in two series would be right for the few seconds after a series pass and
     * wrong for the rest of the day.
     */
    @Test
    fun `the parsed floor can tell which items the server has already spoken for`() = runTest {
        val dao = db.itemSeriesDao()
        dao.upsertAll(
            listOf(
                membership("falls", "Riverton", 1.0, origin = SeriesOrigin.SERVER),
                membership("wakes", "The Breakwater", 1.0, origin = SeriesOrigin.PARSED),
            ),
        )

        val spokenFor = dao.itemsAtOrAbove(
            serverId,
            userId,
            listOf("falls", "wakes", "unknown"),
            SeriesOrigin.SERVER,
        )

        assertThat(spokenFor).containsExactly("falls")
    }

    /**
     * The sweep after a completed listing pass, which is the only thing that can remove a
     * series the server no longer has.
     */
    @Test
    fun `the sweep drops what a finished pass did not see, in that library only`() = runTest {
        val dao = db.itemSeriesDao()
        dao.upsertAll(
            listOf(
                membership("fresh", "The Breakwater", 1.0, syncedAtMs = 200),
                membership("stale", "The Tidelands", syncedAtMs = 100),
                membership("elsewhere", "Riverton", 1.0, syncedAtMs = 100, libraryId = "lib2"),
            ),
        )

        dao.deleteStale(serverId, userId, "lib1", before = 200)

        assertThat(dao.forItem(serverId, userId, "stale")).isEmpty()
        assertThat(dao.forItem(serverId, userId, "fresh")).hasSize(1)
        // A pass over one library says nothing about another one's series.
        assertThat(dao.forItem(serverId, userId, "elsewhere")).hasSize(1)
    }

    /**
     * The same hazard the search index has: a membership left pointing at a swept item puts
     * a book on a series page that opens a blank screen when tapped.
     */
    @Test
    fun `memberships do not outlive the item they belong to`() = runTest {
        db.libraryItemDao().upsertAll(listOf(item("wakes")))
        val dao = db.itemSeriesDao()
        dao.upsertAll(
            listOf(membership("wakes", "The Breakwater", 1.0), membership("gone", "The Breakwater", 2.0)),
        )

        dao.deleteOrphans(serverId, userId, "lib1")

        assertThat(dao.forItem(serverId, userId, "gone")).isEmpty()
        assertThat(dao.forItem(serverId, userId, "wakes")).hasSize(1)
    }

    /**
     * The two columns that predate the table, brought back into line with it.
     *
     * They are written from the joined string as items are paged in, and for a book in two
     * series that string names neither — so "Lighthouse Falls" arrives with a series called
     * "The Breakwater #2, Riverton" and a number that belongs to Riverton. Everything that
     * still wants one answer per book reads these, so leaving them wrong is not a cosmetic
     * matter: it is an author page grouping a book under a series nothing else is in.
     */
    @Test
    fun `the item's primary series is re-derived from its memberships`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(
                item("falls").copy(
                    seriesName = "The Breakwater #2, Riverton #1",
                    seriesTitle = "The Breakwater #2, Riverton",
                    seriesSequence = 1.0,
                ),
                item("orphan").copy(seriesTitle = "Unabridged", seriesSequence = 3.0),
            ),
        )
        db.itemSeriesDao().upsertAll(
            listOf(
                membership("falls", "Riverton", 1.0),
                membership("falls", "The Breakwater", 2.0),
            ),
        )

        db.libraryItemDao().refreshPrimarySeries(serverId, userId, "lib1")

        val falls = db.libraryItemDao().byId(serverId, userId, "falls")
        assertThat(falls?.seriesTitle).isEqualTo("Riverton")
        assertThat(falls?.seriesSequence).isEqualTo(1.0)

        // A book the listing put in no series is in no series, invented column or not.
        val orphan = db.libraryItemDao().byId(serverId, userId, "orphan")
        assertThat(orphan?.seriesTitle).isNull()
        assertThat(orphan?.seriesSequence).isNull()
    }

    /**
     * The car's browse tree and the download-ahead rule both ask for series by name, and
     * both used to read a column that held one series per book.
     */
    @Test
    fun `series names come back once each, and a series comes back in order`() = runTest {
        db.libraryItemDao().upsertAll(listOf(item("wakes"), item("falls"), item("companion")))
        db.itemSeriesDao().upsertAll(
            listOf(
                membership("wakes", "The Breakwater", 1.0),
                membership("falls", "The Breakwater", 2.0),
                membership("falls", "Riverton", 1.0),
                membership("companion", "The Breakwater", sequence = null, serverRank = 9),
            ),
        )

        val dao = db.libraryItemDao()

        assertThat(dao.seriesTitles(serverId, userId))
            .containsExactly("Riverton", "The Breakwater").inOrder()
        assertThat(dao.bySeries(serverId, userId, "The Breakwater").map { it.id })
            .containsExactly("wakes", "falls", "companion").inOrder()
        assertThat(dao.nextInSeriesAfter(serverId, userId, "The Breakwater", afterSequence = 1.0)?.id)
            .isEqualTo("falls")
    }
}
