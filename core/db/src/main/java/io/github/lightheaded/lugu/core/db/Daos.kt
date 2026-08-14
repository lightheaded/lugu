package io.github.lightheaded.lugu.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM server WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<ServerEntity?>

    @Query("SELECT * FROM server WHERE isActive = 1 LIMIT 1")
    suspend fun active(): ServerEntity?

    @Upsert
    suspend fun upsert(server: ServerEntity)

    @Query("UPDATE server SET isActive = 0")
    suspend fun clearActive()

    @Transaction
    suspend fun setActive(server: ServerEntity) {
        clearActive()
        upsert(server.copy(isActive = true))
    }

    @Query("DELETE FROM server WHERE serverId = :serverId")
    suspend fun delete(serverId: String)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library WHERE serverId = :serverId AND userId = :userId ORDER BY displayOrder, name")
    fun observeAll(serverId: String, userId: String): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM library WHERE serverId = :serverId AND userId = :userId ORDER BY displayOrder, name")
    suspend fun all(serverId: String, userId: String): List<LibraryEntity>

    @Upsert
    suspend fun upsertAll(libraries: List<LibraryEntity>)

    @Query("DELETE FROM library WHERE serverId = :serverId AND userId = :userId AND id NOT IN (:keepIds)")
    suspend fun deleteMissing(serverId: String, userId: String, keepIds: List<String>)
}

@Dao
interface LibraryItemDao {
    @Query(
        """
        SELECT * FROM library_item
        WHERE serverId = :serverId AND userId = :userId AND libraryId = :libraryId
        ORDER BY title COLLATE NOCASE
        """,
    )
    fun observeByLibrary(serverId: String, userId: String, libraryId: String): Flow<List<LibraryItemEntity>>

    @Query(
        """
        SELECT * FROM library_item
        WHERE serverId = :serverId AND userId = :userId AND libraryId = :libraryId
          AND (title LIKE '%' || :query || '%'
               OR authorName LIKE '%' || :query || '%'
               OR seriesName LIKE '%' || :query || '%'
               OR narratorName LIKE '%' || :query || '%')
        ORDER BY title COLLATE NOCASE
        LIMIT 200
        """,
    )
    fun search(serverId: String, userId: String, libraryId: String, query: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_item WHERE serverId = :serverId AND userId = :userId AND id = :id")
    fun observeById(serverId: String, userId: String, id: String): Flow<LibraryItemEntity?>

    @Query("SELECT * FROM library_item WHERE serverId = :serverId AND userId = :userId AND id = :id")
    suspend fun byId(serverId: String, userId: String, id: String): LibraryItemEntity?

    @Query("SELECT COUNT(*) FROM library_item WHERE serverId = :serverId AND userId = :userId")
    suspend fun count(serverId: String, userId: String): Int

    @Upsert
    suspend fun upsertAll(items: List<LibraryItemEntity>)

    /** Sweeps rows a full sync pass did not touch — the server dropped them. */
    @Query(
        """
        DELETE FROM library_item
        WHERE serverId = :serverId AND userId = :userId AND libraryId = :libraryId AND syncedAtMs < :before
        """,
    )
    suspend fun deleteStale(serverId: String, userId: String, libraryId: String, before: Long)

    @Query("DELETE FROM library_item WHERE serverId = :serverId AND userId = :userId AND id = :id")
    suspend fun delete(serverId: String, userId: String, id: String)

    /**
     * Continue-listening, computed locally so it renders on a cold start with no network.
     *
     * The GROUP BY is load-bearing. A book has one progress row, but a podcast has one
     * per episode, and joining on `libraryItemId` alone returns that podcast once per
     * episode listened to. The UI keys this list by item id, and Compose throws on a
     * duplicate key — so a duplicate row here crashes the app rather than looking odd.
     * Ordering by MAX(lastUpdateMs) ranks a podcast by its most recent episode.
     */
    @Query(
        """
        SELECT i.* FROM library_item i
        INNER JOIN progress p
            ON p.serverId = i.serverId AND p.userId = i.userId AND p.libraryItemId = i.id
        WHERE i.serverId = :serverId AND i.userId = :userId
          AND p.isFinished = 0 AND p.currentTimeSec > 0
        GROUP BY i.serverId, i.userId, i.id
        ORDER BY MAX(p.lastUpdateMs) DESC
        LIMIT :limit
        """,
    )
    fun observeContinueListening(serverId: String, userId: String, limit: Int = 20): Flow<List<LibraryItemEntity>>
}

@Dao
interface EpisodeDao {
    @Query(
        """
        SELECT * FROM episode
        WHERE serverId = :serverId AND userId = :userId AND libraryItemId = :itemId
        ORDER BY publishedAtMs DESC
        """,
    )
    fun observeForItem(serverId: String, userId: String, itemId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episode WHERE serverId = :serverId AND userId = :userId AND id = :id")
    suspend fun byId(serverId: String, userId: String, id: String): EpisodeEntity?

    @Upsert
    suspend fun upsertAll(episodes: List<EpisodeEntity>)

    @Query("DELETE FROM episode WHERE serverId = :serverId AND userId = :userId AND libraryItemId = :itemId")
    suspend fun deleteForItem(serverId: String, userId: String, itemId: String)
}

@Dao
interface ChapterDao {
    @Query(
        """
        SELECT * FROM chapter
        WHERE serverId = :serverId AND userId = :userId AND libraryItemId = :itemId
        ORDER BY startSec
        """,
    )
    fun observeForItem(serverId: String, userId: String, itemId: String): Flow<List<ChapterEntity>>

    @Query(
        """
        SELECT * FROM chapter
        WHERE serverId = :serverId AND userId = :userId AND libraryItemId = :itemId
        ORDER BY startSec
        """,
    )
    suspend fun forItem(serverId: String, userId: String, itemId: String): List<ChapterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapter WHERE serverId = :serverId AND userId = :userId AND libraryItemId = :itemId")
    suspend fun deleteForItem(serverId: String, userId: String, itemId: String)

    /**
     * The one place chapters are written. Re-indexes by ascending start so the
     * sorted-by-start invariant holds no matter what order the server sent
     * (server #3007 sorts by internal id).
     */
    @Transaction
    suspend fun replaceForItem(serverId: String, userId: String, itemId: String, chapters: List<ChapterEntity>) {
        deleteForItem(serverId, userId, itemId)
        val ordered = chapters
            .sortedBy { it.startSec }
            .mapIndexed { index, chapter -> chapter.copy(chapterIndex = index) }
        insertAll(ordered)
    }
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE serverId = :serverId AND userId = :userId")
    fun observeAll(serverId: String, userId: String): Flow<List<ProgressEntity>>

    @Query(
        """
        SELECT * FROM progress
        WHERE serverId = :serverId AND userId = :userId
          AND libraryItemId = :itemId AND episodeKey = :episodeKey
        """,
    )
    fun observeOne(serverId: String, userId: String, itemId: String, episodeKey: String): Flow<ProgressEntity?>

    @Query(
        """
        SELECT * FROM progress
        WHERE serverId = :serverId AND userId = :userId
          AND libraryItemId = :itemId AND episodeKey = :episodeKey
        """,
    )
    suspend fun get(serverId: String, userId: String, itemId: String, episodeKey: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE serverId = :serverId AND userId = :userId AND isDirty = 1")
    suspend fun dirty(serverId: String, userId: String): List<ProgressEntity>

    /** What the headset play button should resume after a process death or a reboot. */
    @Query(
        """
        SELECT * FROM progress
        WHERE serverId = :serverId AND userId = :userId AND isFinished = 0 AND currentTimeSec > 0
        ORDER BY lastUpdateMs DESC LIMIT 1
        """,
    )
    suspend fun mostRecent(serverId: String, userId: String): ProgressEntity?

    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Upsert
    suspend fun upsertAll(progress: List<ProgressEntity>)

    @Query(
        """
        UPDATE progress SET isDirty = 0, serverLastUpdateMs = :serverLastUpdateMs
        WHERE serverId = :serverId AND userId = :userId
          AND libraryItemId = :itemId AND episodeKey = :episodeKey
        """,
    )
    suspend fun markClean(
        serverId: String,
        userId: String,
        itemId: String,
        episodeKey: String,
        serverLastUpdateMs: Long,
    )
}

@Dao
interface SessionLedgerDao {
    @Query("SELECT * FROM session_ledger WHERE serverId = :serverId AND userId = :userId ORDER BY startedAtMs DESC LIMIT :limit")
    fun observeRecent(serverId: String, userId: String, limit: Int = 100): Flow<List<SessionLedgerEntity>>

    @Query("SELECT * FROM session_ledger WHERE id = :id")
    suspend fun byId(id: String): SessionLedgerEntity?

    @Query("SELECT * FROM session_ledger WHERE serverId = :serverId AND userId = :userId AND isUploaded = 0 AND isLocal = 1")
    suspend fun pendingUploads(serverId: String, userId: String): List<SessionLedgerEntity>

    @Upsert
    suspend fun upsert(session: SessionLedgerEntity)

    @Query("UPDATE session_ledger SET isUploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox WHERE serverId = :serverId AND userId = :userId ORDER BY createdAtMs LIMIT :limit")
    suspend fun peek(serverId: String, userId: String, limit: Int = 100): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE serverId = :serverId AND userId = :userId")
    fun observeDepth(serverId: String, userId: String): Flow<Int>

    @Insert
    suspend fun insert(entry: OutboxEntity)

    @Query("DELETE FROM outbox WHERE serverId = :serverId AND userId = :userId AND kind = :kind AND dedupeKey = :dedupeKey")
    suspend fun deleteByDedupeKey(serverId: String, userId: String, kind: String, dedupeKey: String)

    @Query("DELETE FROM outbox WHERE rowId = :rowId")
    suspend fun delete(rowId: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastAttemptAtMs = :nowMs, lastError = :error WHERE rowId = :rowId")
    suspend fun recordFailure(rowId: Long, nowMs: Long, error: String?)

    /**
     * A later position for the same item supersedes an earlier queued one: there is no
     * value in replaying every 5-second tick after a week offline.
     */
    @Transaction
    suspend fun enqueueSuperseding(entry: OutboxEntity) {
        deleteByDedupeKey(entry.serverId, entry.userId, entry.kind, entry.dedupeKey)
        insert(entry)
    }
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue WHERE serverId = :serverId AND userId = :userId ORDER BY position")
    fun observeQueue(serverId: String, userId: String): Flow<List<QueueEntity>>

    @Upsert
    suspend fun upsertAll(entries: List<QueueEntity>)

    @Query("DELETE FROM queue WHERE serverId = :serverId AND userId = :userId")
    suspend fun clear(serverId: String, userId: String)
}
