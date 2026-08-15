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
 * Bookmarks, and the two rules that keep them from being lost or resurrected.
 *
 * A bookmark made offline must survive a pull from a server that has never heard of it,
 * and a bookmark deleted offline must not come back on the next pull. Both are decided
 * by which rows `deleteSettled` spares, which is invisible from the type signature.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookmarkDaoTest {

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

    private fun bookmark(
        timeSec: Long,
        itemId: String = "li_1",
        title: String = "At $timeSec",
        isDirty: Boolean = false,
        isDeleted: Boolean = false,
    ) = BookmarkEntity(
        serverId = serverId,
        userId = userId,
        libraryItemId = itemId,
        timeSec = timeSec,
        title = title,
        createdAtMs = timeSec,
        isDirty = isDirty,
        isDeleted = isDeleted,
    )

    @Test
    fun `bookmarks come back in time order`() = runTest {
        val dao = db.bookmarkDao()
        dao.upsertAll(listOf(bookmark(900), bookmark(100), bookmark(500)))

        val rows = dao.observeForItem(serverId, userId, "li_1").first()

        assertThat(rows.map { it.timeSec }).containsExactly(100L, 500L, 900L).inOrder()
    }

    @Test
    fun `a tombstone is hidden but still present`() = runTest {
        val dao = db.bookmarkDao()
        dao.upsert(bookmark(100))
        dao.upsert(bookmark(100, isDirty = true, isDeleted = true))

        assertThat(dao.observeForItem(serverId, userId, "li_1").first()).isEmpty()
        // Still owed to the server, so still in the dirty set — otherwise the delete
        // would never be sent and the next pull would hand the bookmark straight back.
        assertThat(dao.dirty(serverId, userId).map { it.timeSec }).containsExactly(100L)
    }

    @Test
    fun `a pull keeps work the server has not seen`() = runTest {
        val dao = db.bookmarkDao()
        dao.upsertAll(
            listOf(
                bookmark(100), // already agreed with the server
                bookmark(200, isDirty = true), // made in a tunnel
            ),
        )

        dao.deleteSettled(serverId, userId)

        assertThat(dao.forItem(serverId, userId, "li_1").map { it.timeSec }).containsExactly(200L)
    }

    @Test
    fun `the same time is the same bookmark`() = runTest {
        val dao = db.bookmarkDao()
        dao.upsert(bookmark(100, title = "Where the storm starts"))
        dao.upsert(bookmark(100, title = "Renamed"))

        val rows = dao.forItem(serverId, userId, "li_1")

        // The server addresses a bookmark by (item, time) and gives it no id, so two
        // rows at the same second would be a row the server could never delete.
        assertThat(rows).hasSize(1)
        assertThat(rows.single().title).isEqualTo("Renamed")
    }

    @Test
    fun `bookmarks are scoped to one account`() = runTest {
        val dao = db.bookmarkDao()
        dao.upsert(bookmark(100))
        dao.upsert(bookmark(100).copy(userId = "other"))

        assertThat(dao.forItem(serverId, userId, "li_1")).hasSize(1)
        assertThat(dao.forItem(serverId, "other", "li_1")).hasSize(1)
    }
}
