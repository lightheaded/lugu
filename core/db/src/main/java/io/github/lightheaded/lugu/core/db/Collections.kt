package io.github.lightheaded.lugu.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A collection as the server keeps it — a named, ordered set of items.
 *
 * Mirrored like everything else, so the list is there on a cold start with no network and
 * so membership can be shown on an item page without a round trip. Editing still needs the
 * server, because a collection is shared state and there is no sensible way to merge two
 * offline reorderings of one list.
 */
@Entity(
    tableName = "collection",
    primaryKeys = ["serverId", "userId", "id"],
    indices = [Index(value = ["serverId", "userId", "libraryId"])],
)
data class CollectionEntity(
    val serverId: String,
    val userId: String,
    val id: String,
    val libraryId: String,
    val name: String,
    val description: String?,
    val updatedAtMs: Long,
    /** Set by each sync pass, so collections deleted elsewhere can be swept. */
    val syncedAtMs: Long,
)

/**
 * One item's place in one collection.
 *
 * Its own table rather than a list column on [CollectionEntity] because the interesting
 * question is asked from the other direction — *which collections is this book in* — and a
 * JSON column cannot be joined against the mirror to answer it.
 */
@Entity(
    tableName = "collection_item",
    primaryKeys = ["serverId", "userId", "collectionId", "libraryItemId"],
    indices = [Index(value = ["serverId", "userId", "libraryItemId"])],
)
data class CollectionItemEntity(
    val serverId: String,
    val userId: String,
    val collectionId: String,
    val libraryItemId: String,
    val position: Int,
)

/** A collection with the count the list needs, so the screen does not query per row. */
data class CollectionSummary(val id: String, val name: String, val itemCount: Int)

@Dao
interface CollectionDao {
    @Query(
        """
        SELECT c.id AS id, c.name AS name, COUNT(ci.libraryItemId) AS itemCount
        FROM collection c
        LEFT JOIN collection_item ci
            ON ci.serverId = c.serverId AND ci.userId = c.userId AND ci.collectionId = c.id
        WHERE c.serverId = :serverId AND c.userId = :userId
          AND (:libraryId IS NULL OR c.libraryId = :libraryId)
        GROUP BY c.serverId, c.userId, c.id
        ORDER BY c.name COLLATE NOCASE
        """,
    )
    fun observeAll(serverId: String, userId: String, libraryId: String? = null): Flow<List<CollectionSummary>>

    /** The items of one collection, in the order the collection puts them. */
    @Query(
        """
        SELECT i.* FROM library_item i
        INNER JOIN collection_item ci
            ON ci.serverId = i.serverId AND ci.userId = i.userId AND ci.libraryItemId = i.id
        WHERE ci.serverId = :serverId AND ci.userId = :userId AND ci.collectionId = :collectionId
        ORDER BY ci.position
        """,
    )
    fun observeItems(serverId: String, userId: String, collectionId: String): Flow<List<LibraryItemEntity>>

    /** Which collections hold this item — the question an item page asks. */
    @Query(
        """
        SELECT collectionId FROM collection_item
        WHERE serverId = :serverId AND userId = :userId AND libraryItemId = :itemId
        """,
    )
    fun observeMembership(serverId: String, userId: String, itemId: String): Flow<List<String>>

    @Query("SELECT * FROM collection WHERE serverId = :serverId AND userId = :userId AND id = :id")
    suspend fun byId(serverId: String, userId: String, id: String): CollectionEntity?

    @Upsert
    suspend fun upsertAll(collections: List<CollectionEntity>)

    @Upsert
    suspend fun upsertItems(items: List<CollectionItemEntity>)

    @Query(
        """
        DELETE FROM collection_item
        WHERE serverId = :serverId AND userId = :userId AND collectionId = :collectionId
        """,
    )
    suspend fun clearItems(serverId: String, userId: String, collectionId: String)

    @Query(
        """
        DELETE FROM collection
        WHERE serverId = :serverId AND userId = :userId AND syncedAtMs < :before
        """,
    )
    suspend fun deleteStale(serverId: String, userId: String, before: Long)

    @Query("DELETE FROM collection_item WHERE serverId = :serverId AND userId = :userId")
    suspend fun clearAllItems(serverId: String, userId: String)

    /**
     * Replaces one collection's membership wholesale.
     *
     * A collection is an ordered list and the server owns the order, so a diff would have
     * to reproduce that ordering to be worth anything. Deleting and reinserting inside one
     * transaction is both simpler and the only version that cannot leave a stale position.
     */
    @Transaction
    suspend fun replaceItems(
        serverId: String,
        userId: String,
        collectionId: String,
        items: List<CollectionItemEntity>,
    ) {
        clearItems(serverId, userId, collectionId)
        upsertItems(items)
    }
}
