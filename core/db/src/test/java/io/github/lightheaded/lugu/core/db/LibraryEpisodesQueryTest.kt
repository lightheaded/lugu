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
 * The query behind the episodes view: every episode of one library's podcasts.
 *
 * Each row must carry its own podcast's title, the list must stay inside one library, and
 * it must stay inside one account. Two accounts on one phone can mirror the same podcast
 * id from different servers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryEpisodesQueryTest {

    private lateinit var db: LuguDatabase
    private val serverId = "https://pods.example#u1"
    private val userId = "u1"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LuguDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun podcast(id: String, libraryId: String, title: String, server: String = serverId) =
        LibraryItemEntity(
            serverId = server,
            userId = userId,
            id = id,
            libraryId = libraryId,
            mediaType = "PODCAST",
            title = title,
            subtitle = null,
            authorName = null,
            narratorName = null,
            seriesName = null,
            seriesTitle = null,
            seriesSequence = null,
            description = null,
            durationSec = 0.0,
            sizeBytes = 0,
            numEpisodes = 0,
            addedAtMs = 0,
            updatedAtMs = 0,
            coverPath = null,
            rawJson = null,
            syncedAtMs = 0,
        )

    private fun episode(id: String, itemId: String, publishedAtMs: Long, server: String = serverId) =
        EpisodeEntity(
            serverId = server,
            userId = userId,
            id = id,
            libraryItemId = itemId,
            title = "Episode $id",
            subtitle = null,
            description = null,
            episodeNumber = null,
            season = null,
            publishedAtMs = publishedAtMs,
            durationSec = 1_800.0,
            position = 0,
        )

    @Test
    fun `episodes of every podcast in the library come back, each with its podcast`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(podcast("pod-a", "lib-pods", "Alpha"), podcast("pod-b", "lib-pods", "Bravo")),
        )
        db.episodeDao().upsertAll(
            listOf(
                episode("a1", "pod-a", publishedAtMs = 100),
                episode("b1", "pod-b", publishedAtMs = 300),
                episode("a2", "pod-a", publishedAtMs = 200),
            ),
        )

        val rows = db.episodeDao().observeForLibrary(serverId, userId, "lib-pods").first()

        assertThat(rows.map { it.episode.id }).containsExactly("b1", "a2", "a1").inOrder()
        assertThat(rows.associate { it.episode.id to it.podcastTitle })
            .containsExactly("a1", "Alpha", "a2", "Alpha", "b1", "Bravo")
    }

    @Test
    fun `another library and another account stay out`() = runTest {
        val otherServer = "https://other.example#u1"
        db.libraryItemDao().upsertAll(
            listOf(
                podcast("pod-a", "lib-pods", "Alpha"),
                podcast("pod-x", "lib-other", "Elsewhere"),
                podcast("pod-a", "lib-pods", "Alpha on another server", server = otherServer),
            ),
        )
        db.episodeDao().upsertAll(
            listOf(
                episode("a1", "pod-a", publishedAtMs = 100),
                episode("x1", "pod-x", publishedAtMs = 200),
                episode("z1", "pod-a", publishedAtMs = 300, server = otherServer),
            ),
        )

        val rows = db.episodeDao().observeForLibrary(serverId, userId, "lib-pods").first()

        assertThat(rows.map { it.episode.id }).containsExactly("a1")
        assertThat(rows.single().podcastTitle).isEqualTo("Alpha")
    }
}
