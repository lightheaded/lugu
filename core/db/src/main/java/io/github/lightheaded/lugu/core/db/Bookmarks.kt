package io.github.lightheaded.lugu.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A named position in a book, kept locally and mirrored to the server.
 *
 * The server addresses a bookmark by `(libraryItemId, time)` — there is no id — so that
 * pair is the primary key here too. It also means the *time* is the identity: moving a
 * bookmark is a delete and an insert, not an update, and renaming one is an update that
 * keeps the time fixed. Rounding to whole seconds at the write boundary keeps the two
 * sides addressing the same row; a bookmark stored at 1234.4004 that the server rounds
 * to 1234 is a bookmark that can never be deleted again.
 *
 * Audiobookshelf has no concept of a bookmark on a podcast episode (the endpoints take
 * a library item and nothing else), so neither does this table.
 *
 * [isDirty] and [isDeleted] are what make bookmarking work with the network off. A new
 * bookmark is written here first and pushed when there is a server to push to; a deleted
 * one becomes a tombstone rather than disappearing, because a row that vanished locally
 * would simply be re-created by the next pull from the server.
 */
@Entity(
    tableName = "bookmark",
    primaryKeys = ["serverId", "userId", "libraryItemId", "timeSec"],
    indices = [Index(value = ["serverId", "userId", "libraryItemId"])],
)
data class BookmarkEntity(
    val serverId: String,
    val userId: String,
    val libraryItemId: String,
    /** Whole seconds from the start of the book — the server's own addressing unit. */
    val timeSec: Long,
    val title: String,
    val createdAtMs: Long,
    /** True while a local change has not been confirmed by the server. */
    val isDirty: Boolean = false,
    /** A tombstone: deleted locally, not yet deleted on the server. */
    val isDeleted: Boolean = false,
)

@Dao
interface BookmarkDao {
    @Query(
        """
        SELECT * FROM bookmark
        WHERE serverId = :serverId AND userId = :userId AND libraryItemId = :itemId
          AND isDeleted = 0
        ORDER BY timeSec
        """,
    )
    fun observeForItem(serverId: String, userId: String, itemId: String): Flow<List<BookmarkEntity>>

    @Query(
        """
        SELECT * FROM bookmark
        WHERE serverId = :serverId AND userId = :userId AND libraryItemId = :itemId
          AND isDeleted = 0
        ORDER BY timeSec
        """,
    )
    suspend fun forItem(serverId: String, userId: String, itemId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmark WHERE serverId = :serverId AND userId = :userId AND isDirty = 1")
    suspend fun dirty(serverId: String, userId: String): List<BookmarkEntity>

    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity)

    @Upsert
    suspend fun upsertAll(bookmarks: List<BookmarkEntity>)

    @Query(
        """
        DELETE FROM bookmark
        WHERE serverId = :serverId AND userId = :userId AND libraryItemId = :itemId
          AND timeSec = :timeSec
        """,
    )
    suspend fun deleteRow(serverId: String, userId: String, itemId: String, timeSec: Long)

    /**
     * Replaces everything the server knows about, keeping local work that it does not.
     *
     * A pull is authoritative only about bookmarks that have already been agreed on:
     * anything still dirty is a local edit in flight, and dropping it here would lose a
     * bookmark made in a tunnel.
     */
    @Query("DELETE FROM bookmark WHERE serverId = :serverId AND userId = :userId AND isDirty = 0")
    suspend fun deleteSettled(serverId: String, userId: String)

    @Query("DELETE FROM bookmark WHERE serverId = :serverId AND userId = :userId")
    suspend fun deleteAll(serverId: String, userId: String)
}
