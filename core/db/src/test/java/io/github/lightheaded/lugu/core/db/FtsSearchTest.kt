package io.github.lightheaded.lugu.core.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.FtsQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FtsSearchTest {

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

    private suspend fun index(
        id: String,
        title: String,
        author: String = "",
        narrator: String = "",
        series: String = "",
        libraryId: String = "lib1",
    ) {
        db.libraryItemDao().upsertAll(
            listOf(
                LibraryItemEntity(
                    serverId = serverId,
                    userId = userId,
                    id = id,
                    libraryId = libraryId,
                    mediaType = "BOOK",
                    title = title,
                    subtitle = null,
                    authorName = author,
                    narratorName = narrator,
                    seriesName = series,
                    seriesTitle = series,
                    seriesSequence = null,
                    description = null,
                    durationSec = 100.0,
                    sizeBytes = 0,
                    numEpisodes = 0,
                    addedAtMs = 0,
                    updatedAtMs = 0,
                    coverPath = null,
                    rawJson = null,
                    syncedAtMs = 0,
                ),
            ),
        )
        db.libraryItemFtsDao().replaceAll(
            serverId,
            userId,
            listOf(
                LibraryItemFtsEntity(
                    serverId = serverId,
                    userId = userId,
                    itemId = id,
                    libraryId = libraryId,
                    text = listOf(title, author, narrator, series).filter { it.isNotBlank() }.joinToString(" "),
                ),
            ),
        )
    }

    private suspend fun search(query: String, libraryId: String = "lib1"): List<String> {
        val match = FtsQuery.toMatchExpression(query) ?: return emptyList()
        return db.libraryItemDao().searchFts(serverId, userId, libraryId, match).first().map { it.id }
    }

    @Test
    fun `finds a book by any of its metadata`() = runTest {
        index("li_1", "Lighthouse Wakes", author = "James T. R. Corven", narrator = "Jefferson Vale")

        assertThat(search("lighthouse")).containsExactly("li_1")
        assertThat(search("corven")).containsExactly("li_1")
        assertThat(search("vale")).containsExactly("li_1")
    }

    /** Results should narrow while a word is still being typed, not only once it is finished. */
    @Test
    fun `matches on a prefix`() = runTest {
        index("li_1", "Lighthouse Wakes", author = "James T. R. Corven")

        assertThat(search("ligh")).containsExactly("li_1")
        assertThat(search("cor")).containsExactly("li_1")
    }

    @Test
    fun `every term must match, so half-remembering still finds it`() = runTest {
        index("li_1", "Lighthouse Wakes", author = "James T. R. Corven")
        index("li_2", "Lighthouse Falls", author = "James T. R. Corven")

        assertThat(search("lighthouse")).containsExactly("li_1", "li_2")
        assertThat(search("lighthouse wakes")).containsExactly("li_1")
        assertThat(search("corven falls")).containsExactly("li_2")
    }

    @Test
    fun `search is scoped to the library in view`() = runTest {
        index("li_1", "Lighthouse Wakes", libraryId = "lib1")
        index("li_2", "Lighthouse Falls", libraryId = "lib2")

        assertThat(search("lighthouse", libraryId = "lib1")).containsExactly("li_1")
        assertThat(search("lighthouse", libraryId = "lib2")).containsExactly("li_2")
    }

    /**
     * The index is rewritten on every sync. An FTS4 table has no unique constraint, so an
     * insert without the matching delete would double every row — and with it every
     * search result.
     */
    @Test
    fun `re-indexing the same item does not duplicate it`() = runTest {
        index("li_1", "Lighthouse Wakes", author = "James T. R. Corven")
        index("li_1", "Lighthouse Wakes", author = "James T. R. Corven")
        index("li_1", "Lighthouse Wakes", author = "James T. R. Corven")

        assertThat(search("lighthouse")).containsExactly("li_1")
    }

    @Test
    fun `an item that leaves the library leaves the index`() = runTest {
        index("li_1", "Lighthouse Wakes")
        index("li_2", "Lighthouse Falls")

        db.libraryItemDao().delete(serverId, userId, "li_2")
        db.libraryItemFtsDao().deleteOrphans(serverId, userId, "lib1")

        assertThat(search("lighthouse")).containsExactly("li_1")
    }

    /**
     * The search box runs on every keystroke, so half-typed and punctuation-only input is
     * the normal case. FTS4 MATCH is a query language and throws on malformed input — a
     * crash while typing would be the worst possible failure here.
     */
    @Test
    fun `half-typed and punctuation-only queries never reach FTS`() {
        assertThat(FtsQuery.toMatchExpression("")).isNull()
        assertThat(FtsQuery.toMatchExpression("   ")).isNull()
        assertThat(FtsQuery.toMatchExpression("\"")).isNull()
        assertThat(FtsQuery.toMatchExpression("-")).isNull()
        assertThat(FtsQuery.toMatchExpression("a")).isNull()
        assertThat(FtsQuery.toMatchExpression("*")).isNull()
    }

    @Test
    fun `quotes and dashes in a real query are stripped rather than passed through`() = runTest {
        index("li_1", "Lighthouse Wakes", author = "James T. R. Corven")

        // Would be a syntax error inside MATCH if it reached SQLite unsanitised.
        assertThat(search("\"lighthouse")).containsExactly("li_1")
        assertThat(search("-lighthouse")).containsExactly("li_1")
        assertThat(search("lighthouse!")).containsExactly("li_1")
    }
}
