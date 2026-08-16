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
 * Browsing by author, series and narrator.
 *
 * These are the pages the item screen's links point at, and they are pure SQL — so the
 * two decisions that matter, grouping a series by its title rather than its name and
 * ordering a series by its number rather than its title, are invisible until they run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseQueryTest {

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

    private fun item(
        id: String,
        title: String = "Title $id",
        author: String? = null,
        narrator: String? = null,
        seriesName: String? = null,
        seriesTitle: String? = null,
        seriesSequence: Double? = null,
        libraryId: String = "lib1",
    ) = LibraryItemEntity(
        serverId = serverId,
        userId = userId,
        id = id,
        libraryId = libraryId,
        mediaType = "BOOK",
        title = title,
        subtitle = null,
        authorName = author,
        narratorName = narrator,
        seriesName = seriesName,
        seriesTitle = seriesTitle,
        seriesSequence = seriesSequence,
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

    /** Membership is its own row from schema 6 on, so every series case states one. */
    private fun membership(
        itemId: String,
        seriesName: String,
        sequence: Double? = null,
        serverRank: Int? = null,
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
        origin = SeriesOrigin.SERVER,
        syncedAtMs = 0,
    )

    @Test
    fun `authors come back once each, with a count`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(
                item("a", author = "James T. R. Corven"),
                item("b", author = "James T. R. Corven"),
                item("c", author = "Ingrid Salla"),
                item("d", author = null),
                item("e", author = "   "),
            ),
        )

        val authors = db.libraryItemDao().observeAuthors(serverId, userId).first()

        assertThat(authors.map { it.name })
            .containsExactly("Ingrid Salla", "James T. R. Corven").inOrder()
        assertThat(authors.first { it.name == "James T. R. Corven" }.itemCount).isEqualTo(2)
        // A blank credit is not an author called "   ", and would sort to the top of the list.
        assertThat(authors.map { it.name }).doesNotContain("   ")
    }

    /**
     * The subtle one. `seriesName` carries the number ("The Breakwater #2"), so grouping by
     * it makes every book its own series — which is why membership is kept apart from it.
     */
    @Test
    fun `a series groups by its name, not by the string with the number in it`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(item("a", seriesName = "The Breakwater #1"), item("b", seriesName = "The Breakwater #2")),
        )
        db.itemSeriesDao().upsertAll(
            listOf(membership("a", "The Breakwater", 1.0), membership("b", "The Breakwater", 2.0)),
        )

        val series = db.libraryItemDao().observeSeries(serverId, userId).first()

        assertThat(series).hasSize(1)
        assertThat(series.single().name).isEqualTo("The Breakwater")
        assertThat(series.single().itemCount).isEqualTo(2)
    }

    /**
     * A book in two series belongs on both pages and in both counts.
     *
     * The one column this replaced could only ever put it on one, and for a book whose
     * joined string was "The Breakwater #2, Riverton #1" it put it on neither: it filed the
     * book under a series called "The Breakwater #2, Riverton" that nothing else was in.
     */
    @Test
    fun `a book in two series appears under both`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(
                item("wakes", title = "Lighthouse Wakes"),
                item("falls", title = "Lighthouse Falls", seriesName = "The Breakwater #2, Riverton #1"),
            ),
        )
        db.itemSeriesDao().upsertAll(
            listOf(
                membership("wakes", "The Breakwater", 1.0),
                membership("falls", "The Breakwater", 2.0),
                membership("falls", "Riverton", 1.0),
            ),
        )

        val series = db.libraryItemDao().observeSeries(serverId, userId).first()

        assertThat(series.map { it.name }).containsExactly("Riverton", "The Breakwater").inOrder()
        assertThat(series.first { it.name == "The Breakwater" }.itemCount).isEqualTo(2)
        assertThat(db.libraryItemDao().observeBySeries(serverId, userId, "Riverton").first().map { it.id })
            .containsExactly("falls")
    }

    @Test
    fun `a series page is in reading order, and the unnumbered come last`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(item("ten", title = "Tenth"), item("two", title = "Second"), item("none", title = "A companion")),
        )
        db.itemSeriesDao().upsertAll(
            listOf(
                membership("ten", "The Breakwater", 10.0),
                membership("two", "The Breakwater", 2.0),
                membership("none", "The Breakwater", sequence = null),
            ),
        )

        val rows = db.libraryItemDao().observeBySeries(serverId, userId, "The Breakwater").first()

        // Ordered by number, not by title — sorted as text "10" would come before "2",
        // which is precisely how a series shelf recommends the wrong book.
        assertThat(rows.map { it.id }).containsExactly("two", "ten", "none").inOrder()
    }

    /**
     * What the library-series listing buys a series nobody numbered.
     *
     * Alphabetical is simply wrong for these — the first book of "The Tidelands" is called
     * "Zenith" and the second "Aftermath" — and the server's own web client shows them in
     * an order it will not explain to a client any other way. Laying the page out that way
     * costs nothing, because a page shows the whole series and lets the reader choose.
     */
    @Test
    fun `a series with no numbers falls back to the order the server gave`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(item("first", title = "Zenith"), item("second", title = "Aftermath")),
        )
        db.itemSeriesDao().upsertAll(
            listOf(
                membership("first", "The Tidelands", sequence = null, serverRank = 0),
                membership("second", "The Tidelands", sequence = null, serverRank = 1),
            ),
        )

        val rows = db.libraryItemDao().observeBySeries(serverId, userId, "The Tidelands").first()

        assertThat(rows.map { it.id }).containsExactly("first", "second").inOrder()
    }

    @Test
    fun `an author page groups their series together and orders each by sequence`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(
                item("standalone", title = "Zebra", author = "Corven"),
                item("s2", title = "Second", author = "Corven", seriesTitle = "Breakwater", seriesSequence = 2.0),
                item("s1", title = "First", author = "Corven", seriesTitle = "Breakwater", seriesSequence = 1.0),
            ),
        )

        val rows = db.libraryItemDao().observeByAuthor(serverId, userId, "Corven").first()

        // Series first and in order, then whatever belongs to no series.
        assertThat(rows.map { it.id }).containsExactly("s1", "s2", "standalone").inOrder()
    }

    @Test
    fun `narrators are grouped separately from authors`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(
                item("a", author = "Corven", narrator = "Jefferson Vale"),
                item("b", author = "Salla", narrator = "Jefferson Vale"),
            ),
        )

        val narrators = db.libraryItemDao().observeNarrators(serverId, userId).first()

        assertThat(narrators.map { it.name }).containsExactly("Jefferson Vale")
        assertThat(narrators.single().itemCount).isEqualTo(2)
        assertThat(db.libraryItemDao().observeByNarrator(serverId, userId, "Jefferson Vale").first())
            .hasSize(2)
    }

    @Test
    fun `a library id narrows the groups`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(
                item("a", author = "Corven", libraryId = "lib1"),
                item("b", author = "Salla", libraryId = "lib2"),
            ),
        )

        val dao = db.libraryItemDao()
        assertThat(dao.observeAuthors(serverId, userId, "lib1").first().map { it.name })
            .containsExactly("Corven")
        assertThat(dao.observeAuthors(serverId, userId).first().map { it.name }).hasSize(2)
    }
}
