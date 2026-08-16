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
 * Collections, and the one property that makes them worth mirroring: the order is the
 * server's, and it has to survive the round trip intact.
 *
 * A collection is the only grouping in the library that a person made rather than one the
 * metadata implies, so its order carries intent — unlike a series, where the number is the
 * order, or an author page, where alphabetical is as good as anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectionDaoTest {

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

    private fun collection(id: String, name: String, libraryId: String = "lib1", syncedAtMs: Long = 10) =
        CollectionEntity(
            serverId = serverId,
            userId = userId,
            id = id,
            libraryId = libraryId,
            name = name,
            description = null,
            updatedAtMs = 0,
            syncedAtMs = syncedAtMs,
        )

    private fun member(collectionId: String, itemId: String, position: Int) = CollectionItemEntity(
        serverId = serverId,
        userId = userId,
        collectionId = collectionId,
        libraryItemId = itemId,
        position = position,
    )

    private fun item(id: String, title: String = "Title $id") = LibraryItemEntity(
        serverId = serverId,
        userId = userId,
        id = id,
        libraryId = "lib1",
        mediaType = "BOOK",
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

    @Test
    fun `a collection lists its items in the collection's own order`() = runTest {
        db.libraryItemDao().upsertAll(listOf(item("a"), item("b"), item("c")))
        val dao = db.collectionDao()
        dao.upsertAll(listOf(collection("c1", "Winter reading")))
        // Deliberately not alphabetical and not the insertion order.
        dao.upsertItems(listOf(member("c1", "c", 0), member("c1", "a", 1), member("c1", "b", 2)))

        val rows = dao.observeItems(serverId, userId, "c1").first()

        assertThat(rows.map { it.id }).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun `the list carries a count so the screen does not query per row`() = runTest {
        val dao = db.collectionDao()
        dao.upsertAll(listOf(collection("c1", "Winter reading"), collection("c2", "Empty")))
        dao.upsertItems(listOf(member("c1", "a", 0), member("c1", "b", 1)))

        val summaries = dao.observeAll(serverId, userId).first()

        assertThat(summaries.map { it.name }).containsExactly("Empty", "Winter reading").inOrder()
        assertThat(summaries.first { it.id == "c1" }.itemCount).isEqualTo(2)
        // A collection with nothing in it still exists, and still has to be listed.
        assertThat(summaries.first { it.id == "c2" }.itemCount).isEqualTo(0)
    }

    @Test
    fun `membership can be asked from the item's side`() = runTest {
        val dao = db.collectionDao()
        dao.upsertAll(listOf(collection("c1", "One"), collection("c2", "Two")))
        dao.upsertItems(listOf(member("c1", "a", 0), member("c2", "a", 0), member("c2", "b", 1)))

        assertThat(dao.observeMembership(serverId, userId, "a").first()).containsExactly("c1", "c2")
        assertThat(dao.observeMembership(serverId, userId, "b").first()).containsExactly("c2")
    }

    @Test
    fun `replacing membership leaves no stale position behind`() = runTest {
        db.libraryItemDao().upsertAll(listOf(item("a"), item("b"), item("c")))
        val dao = db.collectionDao()
        dao.upsertAll(listOf(collection("c1", "One")))
        dao.replaceItems(serverId, userId, "c1", listOf(member("c1", "a", 0), member("c1", "b", 1)))

        // The server reorders and drops one; a diff would have to reproduce that ordering
        // to be worth anything, which is why this replaces wholesale.
        dao.replaceItems(serverId, userId, "c1", listOf(member("c1", "c", 0), member("c1", "a", 1)))

        assertThat(dao.observeItems(serverId, userId, "c1").first().map { it.id })
            .containsExactly("c", "a").inOrder()
    }

    @Test
    fun `a collection deleted elsewhere is swept`() = runTest {
        val dao = db.collectionDao()
        dao.upsertAll(listOf(collection("kept", "Kept", syncedAtMs = 20)))
        dao.upsertAll(listOf(collection("gone", "Gone", syncedAtMs = 5)))

        dao.deleteStale(serverId, userId, before = 10)

        assertThat(dao.observeAll(serverId, userId).first().map { it.id }).containsExactly("kept")
    }

    @Test
    fun `collections are scoped to a library and to an account`() = runTest {
        val dao = db.collectionDao()
        dao.upsertAll(
            listOf(
                collection("c1", "Books", libraryId = "lib1"),
                collection("c2", "Shows", libraryId = "lib2"),
            ),
        )

        assertThat(dao.observeAll(serverId, userId, "lib1").first().map { it.id }).containsExactly("c1")
        assertThat(dao.observeAll(serverId, userId).first()).hasSize(2)
        assertThat(dao.observeAll(serverId, "other").first()).isEmpty()
    }
}
