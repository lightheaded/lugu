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
     * it makes every book its own series — which is why `seriesTitle` exists as a column.
     */
    @Test
    fun `a series groups by its title, not by the name with the number in it`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(
                item("a", seriesName = "The Breakwater #1", seriesTitle = "The Breakwater", seriesSequence = 1.0),
                item("b", seriesName = "The Breakwater #2", seriesTitle = "The Breakwater", seriesSequence = 2.0),
            ),
        )

        val series = db.libraryItemDao().observeSeries(serverId, userId).first()

        assertThat(series).hasSize(1)
        assertThat(series.single().name).isEqualTo("The Breakwater")
        assertThat(series.single().itemCount).isEqualTo(2)
    }

    @Test
    fun `a series page is in reading order, and the unnumbered come last`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(
                item("ten", title = "Tenth", seriesTitle = "Breakwater", seriesSequence = 10.0),
                item("two", title = "Second", seriesTitle = "Breakwater", seriesSequence = 2.0),
                item("none", title = "A companion volume", seriesTitle = "Breakwater", seriesSequence = null),
            ),
        )

        val rows = db.libraryItemDao().observeBySeries(serverId, userId, "Breakwater").first()

        // Ordered by number, not by title — sorted as text "10" would come before "2",
        // which is precisely how a series shelf recommends the wrong book.
        assertThat(rows.map { it.id }).containsExactly("two", "ten", "none").inOrder()
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
