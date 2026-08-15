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
 * The queue, exercised against real SQLite.
 *
 * Ordering is the whole contract here and none of it is checked by the compiler: a queue
 * that quietly reorders itself, duplicates an entry, or leaves a hole where a removed
 * row was is a queue nobody can trust to play the right thing next in a car.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueDaoTest {

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

    private fun dao() = db.queueDao()

    private fun entry(itemId: String, episodeId: String? = null, source: String = QueueSource.USER) = QueueEntity(
        serverId = serverId,
        userId = userId,
        libraryItemId = itemId,
        episodeKey = episodeKeyOf(episodeId),
        position = 0,
        addedAtMs = 0,
        source = source,
    )

    private suspend fun ids(): List<String> =
        dao().all(serverId, userId).map { it.libraryItemId }

    @Test
    fun `appending keeps the order things were added in`() = runTest {
        dao().addLast(entry("a"))
        dao().addLast(entry("b"))
        dao().addLast(entry("c"))

        assertThat(ids()).containsExactly("a", "b", "c").inOrder()
        assertThat(dao().all(serverId, userId).map { it.position }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `play next jumps the queue`() = runTest {
        dao().addLast(entry("a"))
        dao().addLast(entry("b"))
        dao().addFirst(entry("c"))

        assertThat(ids()).containsExactly("c", "a", "b").inOrder()
    }

    /** Queuing something twice means moving it, not playing it twice. */
    @Test
    fun `re-queuing moves rather than duplicates`() = runTest {
        dao().addLast(entry("a"))
        dao().addLast(entry("b"))
        dao().addLast(entry("a"))

        assertThat(ids()).containsExactly("b", "a").inOrder()
    }

    @Test
    fun `one podcast's episodes are separate entries`() = runTest {
        dao().addLast(entry("pod", episodeId = "ep1"))
        dao().addLast(entry("pod", episodeId = "ep2"))

        assertThat(dao().all(serverId, userId).map { it.episodeKey }).containsExactly("ep1", "ep2").inOrder()
    }

    @Test
    fun `removing closes the gap it leaves`() = runTest {
        dao().addLast(entry("a"))
        dao().addLast(entry("b"))
        dao().addLast(entry("c"))

        dao().removeAndRenumber(serverId, userId, "b", "")

        assertThat(ids()).containsExactly("a", "c").inOrder()
        assertThat(dao().all(serverId, userId).map { it.position }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `moving a row lands it where it was dropped`() = runTest {
        listOf("a", "b", "c", "d").forEach { dao().addLast(entry(it)) }

        dao().move(serverId, userId, from = 0, to = 2)

        assertThat(ids()).containsExactly("b", "c", "a", "d").inOrder()
    }

    @Test
    fun `moving to nowhere changes nothing`() = runTest {
        listOf("a", "b").forEach { dao().addLast(entry(it)) }

        dao().move(serverId, userId, from = 0, to = 9)
        dao().move(serverId, userId, from = 5, to = 0)
        dao().move(serverId, userId, from = 1, to = 1)

        assertThat(ids()).containsExactly("a", "b").inOrder()
    }

    /** End-of-book continuation pops the head, and must consume it exactly once. */
    @Test
    fun `taking the head consumes it`() = runTest {
        dao().addLast(entry("a"))
        dao().addLast(entry("b"))

        assertThat(dao().takeHead(serverId, userId)?.libraryItemId).isEqualTo("a")
        assertThat(ids()).containsExactly("b")
        assertThat(dao().takeHead(serverId, userId)?.libraryItemId).isEqualTo("b")
        assertThat(dao().takeHead(serverId, userId)).isNull()
    }

    @Test
    fun `another account's queue is not this one`() = runTest {
        dao().addLast(entry("a"))
        dao().addLast(entry("mine").copy(userId = "other"))

        assertThat(ids()).containsExactly("a")
    }

    /**
     * The display join. An entry whose item has fallen out of the mirror still shows up —
     * a queue that silently drops what the listener put in it is worse than one with a
     * row that reads thinly.
     */
    @Test
    fun `rows carry titles from the mirror and survive its absence`() = runTest {
        db.libraryItemDao().upsertAll(
            listOf(
                LibraryItemEntity(
                    serverId = serverId,
                    userId = userId,
                    id = "a",
                    libraryId = "lib1",
                    mediaType = "book",
                    title = "The Dark Forest",
                    subtitle = null,
                    authorName = "Cixin Liu",
                    narratorName = null,
                    seriesName = null,
                    seriesTitle = null,
                    seriesSequence = null,
                    description = null,
                    durationSec = 1000.0,
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
        dao().addLast(entry("a"))
        dao().addLast(entry("gone"))

        val rows = dao().observeRows(serverId, userId).first()

        assertThat(rows.map { it.title }).containsExactly("The Dark Forest", "").inOrder()
        assertThat(rows.first().author).isEqualTo("Cixin Liu")
        assertThat(rows.first().isDownloaded).isFalse()
    }
}
