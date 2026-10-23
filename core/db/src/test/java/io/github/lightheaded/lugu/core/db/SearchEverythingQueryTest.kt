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
 * The three queries behind Home's search. Each one must look only in the libraries it is
 * given, because a library whose type the settings hide must stay out of the results.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchEverythingQueryTest {

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

    private suspend fun item(id: String, title: String, libraryId: String, mediaType: String = "BOOK", text: String = title) {
        db.libraryItemDao().upsertAll(
            listOf(
                LibraryItemEntity(
                    serverId = serverId,
                    userId = userId,
                    id = id,
                    libraryId = libraryId,
                    mediaType = mediaType,
                    title = title,
                    subtitle = null,
                    authorName = "Ada Merriweather",
                    narratorName = "Jefferson Vale",
                    seriesName = null,
                    seriesTitle = null,
                    seriesSequence = null,
                    description = null,
                    durationSec = 100.0,
                    sizeBytes = 0,
                    numEpisodes = 0,
                    addedAtMs = 0,
                    updatedAtMs = 0,
                    coverPath = null,
                    rawJson = "{\"large\":true}",
                    syncedAtMs = 0,
                ),
            ),
        )
        db.libraryItemFtsDao().replaceAll(
            serverId,
            userId,
            listOf(LibraryItemFtsEntity(serverId, userId, id, libraryId, text)),
        )
    }

    private suspend fun episode(id: String, itemId: String, title: String, publishedAtMs: Long) {
        db.episodeDao().upsertAll(
            listOf(
                EpisodeEntity(
                    serverId = serverId,
                    userId = userId,
                    id = id,
                    libraryItemId = itemId,
                    title = title,
                    subtitle = null,
                    description = "a long description",
                    episodeNumber = null,
                    season = null,
                    publishedAtMs = publishedAtMs,
                    durationSec = 60.0,
                    position = 0,
                ),
            ),
        )
    }

    @Test
    fun `the index finds words in the text, only in the libraries it is given`() = runTest {
        item("b1", "Salt and Shortwave", "books", text = "Salt and Shortwave a radio story")
        item("h1", "Radio Hidden", "hidden", text = "Radio Hidden a radio story")

        val found = db.libraryItemDao().searchInFts(serverId, userId, listOf("books"), "radio*").first()

        assertThat(found.map { it.id }).containsExactly("b1")
    }

    @Test
    fun `the name list holds every item of the given libraries and nothing else`() = runTest {
        item("b1", "Salt and Shortwave", "books")
        item("p1", "Night Shift Radio", "podcasts", mediaType = "PODCAST")
        item("h1", "Radio Hidden", "hidden")

        val rows = db.libraryItemDao().observeSearchable(serverId, userId, listOf("books", "podcasts")).first()

        assertThat(rows.map { it.id }).containsExactly("b1", "p1")
        val podcast = rows.single { it.id == "p1" }
        assertThat(podcast.mediaType).isEqualTo("PODCAST")
        assertThat(podcast.authorName).isEqualTo("Ada Merriweather")
        assertThat(podcast.narratorName).isEqualTo("Jefferson Vale")
    }

    @Test
    fun `episodes come newest first, named after their podcast, from the given libraries only`() = runTest {
        item("p1", "Night Shift Radio", "podcasts", mediaType = "PODCAST")
        item("p2", "Hidden Show", "hidden", mediaType = "PODCAST")
        episode("e1", "p1", "The first one", publishedAtMs = 1_000)
        episode("e2", "p1", "The second one", publishedAtMs = 2_000)
        episode("e3", "p2", "Not to be found", publishedAtMs = 3_000)

        val rows = db.episodeDao().observeSearchable(serverId, userId, listOf("podcasts")).first()

        assertThat(rows.map { it.id }).containsExactly("e2", "e1").inOrder()
        assertThat(rows.first().podcastTitle).isEqualTo("Night Shift Radio")
    }

    @Test
    fun `no library to look in finds nothing`() = runTest {
        item("b1", "Salt and Shortwave", "books")

        assertThat(db.libraryItemDao().observeSearchable(serverId, userId, emptyList()).first()).isEmpty()
        assertThat(db.libraryItemDao().searchInFts(serverId, userId, emptyList(), "salt*").first()).isEmpty()
    }
}
