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
     *
     * [libraryId] scopes the shelf to one library, or spans every library when null.
     * A shelf that ignored the library picker sitting directly above it was read — quite
     * reasonably — as the picker being broken, so the caller now has to say which it
     * means rather than getting whichever the query happened to do.
     */
    @Query(
        """
        SELECT i.* FROM library_item i
        INNER JOIN progress p
            ON p.serverId = i.serverId AND p.userId = i.userId AND p.libraryItemId = i.id
        WHERE i.serverId = :serverId AND i.userId = :userId
          AND (:libraryId IS NULL OR i.libraryId = :libraryId)
          AND p.isFinished = 0 AND p.currentTimeSec > 0
        GROUP BY i.serverId, i.userId, i.id
        ORDER BY MAX(p.lastUpdateMs) DESC
        LIMIT :limit
        """,
    )
    fun observeContinueListening(
        serverId: String,
        userId: String,
        libraryId: String? = null,
        limit: Int = 20,
    ): Flow<List<LibraryItemEntity>>

    /**
     * Full-text search across the mirror, scoped to one library.
     *
     * The FTS table is joined back to `library_item` rather than being read directly,
     * so results carry every column the grid renders and the caller never has to know
     * an index exists.
     */
    @Query(
        """
        SELECT i.* FROM library_item_fts f
        INNER JOIN library_item i
            ON i.serverId = f.serverId AND i.userId = f.userId AND i.id = f.itemId
        WHERE f.serverId = :serverId AND f.userId = :userId AND f.libraryId = :libraryId
          AND library_item_fts MATCH :match
        ORDER BY i.title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    fun searchFts(
        serverId: String,
        userId: String,
        libraryId: String,
        match: String,
        limit: Int = 200,
    ): Flow<List<LibraryItemEntity>>

    /**
     * The same index, across every library at once.
     *
     * A car has no library picker and a voice search has no way to name one, so "play
     * the dark forest" has to look everywhere the phone would let you look.
     */
    @Query(
        """
        SELECT i.* FROM library_item_fts f
        INNER JOIN library_item i
            ON i.serverId = f.serverId AND i.userId = f.userId AND i.id = f.itemId
        WHERE f.serverId = :serverId AND f.userId = :userId
          AND library_item_fts MATCH :match
        ORDER BY i.title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    suspend fun searchEverywhere(
        serverId: String,
        userId: String,
        match: String,
        limit: Int = 50,
    ): List<LibraryItemEntity>

    /** The fallback for a query the FTS syntax cannot take, also across every library. */
    @Query(
        """
        SELECT * FROM library_item
        WHERE serverId = :serverId AND userId = :userId
          AND (title LIKE '%' || :query || '%' OR authorName LIKE '%' || :query || '%')
        ORDER BY title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    suspend fun searchEverywhereLike(
        serverId: String,
        userId: String,
        query: String,
        limit: Int = 50,
    ): List<LibraryItemEntity>

    /**
     * Almost finished: past the threshold but not marked done. These are the ones worth
     * an hour on a commute to clear, and the ones easiest to forget about.
     */
    @Query(
        """
        SELECT i.* FROM library_item i
        INNER JOIN progress p
            ON p.serverId = i.serverId AND p.userId = i.userId AND p.libraryItemId = i.id
        WHERE i.serverId = :serverId AND i.userId = :userId
          AND (:libraryId IS NULL OR i.libraryId = :libraryId)
          AND p.isFinished = 0 AND p.progress >= :minProgress AND p.progress < 1.0
        GROUP BY i.serverId, i.userId, i.id
        ORDER BY MAX(p.progress) DESC
        LIMIT :limit
        """,
    )
    fun observeAlmostFinished(
        serverId: String,
        userId: String,
        libraryId: String? = null,
        minProgress: Double = 0.9,
        limit: Int = 20,
    ): Flow<List<LibraryItemEntity>>

    /** Started, not nearly done, and untouched for a while — the ones that get abandoned. */
    @Query(
        """
        SELECT i.* FROM library_item i
        INNER JOIN progress p
            ON p.serverId = i.serverId AND p.userId = i.userId AND p.libraryItemId = i.id
        WHERE i.serverId = :serverId AND i.userId = :userId
          AND (:libraryId IS NULL OR i.libraryId = :libraryId)
          AND p.isFinished = 0 AND p.currentTimeSec > 0 AND p.progress < :maxProgress
        GROUP BY i.serverId, i.userId, i.id
        HAVING MAX(p.lastUpdateMs) < :staleBeforeMs
        ORDER BY MAX(p.lastUpdateMs) DESC
        LIMIT :limit
        """,
    )
    fun observeStale(
        serverId: String,
        userId: String,
        staleBeforeMs: Long,
        libraryId: String? = null,
        maxProgress: Double = 0.9,
        limit: Int = 20,
    ): Flow<List<LibraryItemEntity>>

    /** Unstarted and short enough to finish in a sitting. */
    @Query(
        """
        SELECT i.* FROM library_item i
        WHERE i.serverId = :serverId AND i.userId = :userId
          AND (:libraryId IS NULL OR i.libraryId = :libraryId)
          AND i.mediaType = 'BOOK'
          AND i.durationSec > 0 AND i.durationSec <= :maxDurationSec
          AND NOT EXISTS (
            SELECT 1 FROM progress p
            WHERE p.serverId = i.serverId AND p.userId = i.userId AND p.libraryItemId = i.id
              AND (p.currentTimeSec > 0 OR p.isFinished = 1)
          )
        ORDER BY i.addedAtMs DESC
        LIMIT :limit
        """,
    )
    fun observeShortListens(
        serverId: String,
        userId: String,
        libraryId: String? = null,
        maxDurationSec: Double = 3 * 3600.0,
        limit: Int = 20,
    ): Flow<List<LibraryItemEntity>>

    /**
     * Next in series: for every series with something finished in it, the lowest-numbered
     * volume not yet started.
     *
     * Ordering is by [LibraryItemEntity.seriesSequence] and never by name — "#10" sorts
     * before "#2" as text, which is precisely how a shelf like this recommends book ten
     * to someone who just finished book one. Items whose sequence could not be parsed
     * are left out rather than guessed at.
     */
    @Query(
        """
        SELECT i.* FROM library_item i
        WHERE i.serverId = :serverId AND i.userId = :userId
          AND (:libraryId IS NULL OR i.libraryId = :libraryId)
          AND i.seriesTitle IS NOT NULL AND i.seriesSequence IS NOT NULL
          AND NOT EXISTS (
            SELECT 1 FROM progress p
            WHERE p.serverId = i.serverId AND p.userId = i.userId AND p.libraryItemId = i.id
              AND (p.currentTimeSec > 0 OR p.isFinished = 1)
          )
          AND EXISTS (
            SELECT 1 FROM library_item d
            INNER JOIN progress q
                ON q.serverId = d.serverId AND q.userId = d.userId AND q.libraryItemId = d.id
            WHERE d.serverId = i.serverId AND d.userId = i.userId
              AND d.seriesTitle = i.seriesTitle AND q.isFinished = 1
              AND d.seriesSequence IS NOT NULL AND d.seriesSequence < i.seriesSequence
          )
          AND i.seriesSequence = (
            SELECT MIN(n.seriesSequence) FROM library_item n
            WHERE n.serverId = i.serverId AND n.userId = i.userId
              AND n.seriesTitle = i.seriesTitle AND n.seriesSequence IS NOT NULL
              AND NOT EXISTS (
                SELECT 1 FROM progress r
                WHERE r.serverId = n.serverId AND r.userId = n.userId AND r.libraryItemId = n.id
                  AND (r.currentTimeSec > 0 OR r.isFinished = 1)
              )
          )
        GROUP BY i.serverId, i.userId, i.seriesTitle
        ORDER BY i.title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    fun observeNextInSeries(
        serverId: String,
        userId: String,
        libraryId: String? = null,
        limit: Int = 20,
    ): Flow<List<LibraryItemEntity>>

    /** Everything with bytes on the phone — the shelf that still works in airplane mode. */
    @Query(
        """
        SELECT i.* FROM library_item i
        INNER JOIN download d
            ON d.serverId = i.serverId AND d.userId = i.userId AND d.libraryItemId = i.id
        WHERE i.serverId = :serverId AND i.userId = :userId AND d.state = 'completed'
          AND (:libraryId IS NULL OR i.libraryId = :libraryId)
        GROUP BY i.serverId, i.userId, i.id
        ORDER BY MAX(d.completedAtMs) DESC
        LIMIT :limit
        """,
    )
    fun observeDownloaded(
        serverId: String,
        userId: String,
        libraryId: String? = null,
        limit: Int = 50,
    ): Flow<List<LibraryItemEntity>>

    /**
     * Podcasts being listened to — the only ones worth refreshing or downloading ahead.
     *
     * "Listened to" means at least one episode has been started, which is the closest
     * thing to a subscription lugu can know without asking anyone to manage a list.
     */
    @Query(
        """
        SELECT i.* FROM library_item i
        WHERE i.serverId = :serverId AND i.userId = :userId AND i.mediaType = 'PODCAST'
          AND EXISTS (
            SELECT 1 FROM progress p
            WHERE p.serverId = i.serverId AND p.userId = i.userId AND p.libraryItemId = i.id
              AND p.currentTimeSec > 0
          )
        ORDER BY i.title COLLATE NOCASE
        """,
    )
    suspend fun followedPodcasts(serverId: String, userId: String): List<LibraryItemEntity>

    /**
     * Every series, once. Backs the series node of the car browse tree, where a flat
     * list of every book is unusable and the series is the unit people think in.
     */
    @Query(
        """
        SELECT DISTINCT seriesTitle FROM library_item
        WHERE serverId = :serverId AND userId = :userId AND seriesTitle IS NOT NULL
        ORDER BY seriesTitle COLLATE NOCASE
        """,
    )
    suspend fun seriesTitles(serverId: String, userId: String): List<String>

    /** One series, in reading order — by sequence, never by title. */
    @Query(
        """
        SELECT * FROM library_item
        WHERE serverId = :serverId AND userId = :userId AND seriesTitle = :seriesTitle
        ORDER BY seriesSequence IS NULL, seriesSequence, title COLLATE NOCASE
        """,
    )
    suspend fun bySeries(serverId: String, userId: String, seriesTitle: String): List<LibraryItemEntity>

    @Query(
        """
        SELECT * FROM library_item
        WHERE serverId = :serverId AND userId = :userId AND mediaType = :mediaType
        ORDER BY title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    suspend fun byMediaType(
        serverId: String,
        userId: String,
        mediaType: String,
        limit: Int = 500,
    ): List<LibraryItemEntity>

    @Query(
        """
        SELECT * FROM library_item
        WHERE serverId = :serverId AND userId = :userId AND libraryId = :libraryId
        ORDER BY title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    suspend fun byLibrary(
        serverId: String,
        userId: String,
        libraryId: String,
        limit: Int = 500,
    ): List<LibraryItemEntity>

    /**
     * The volume that follows this one in its series, if it has not been started.
     *
     * Distinct from [observeNextInSeries], which surveys every series at once for a
     * shelf. This answers the narrower question end-of-book continuation asks: someone
     * has just finished *this* book, so what is next in *this* series. Ordered by
     * sequence, never by name, for the same reason as everywhere else — "#10" sorts
     * before "#2" as text.
     */
    @Query(
        """
        SELECT n.* FROM library_item n
        WHERE n.serverId = :serverId AND n.userId = :userId
          AND n.seriesTitle = :seriesTitle
          AND n.seriesSequence IS NOT NULL AND n.seriesSequence > :afterSequence
          AND NOT EXISTS (
            SELECT 1 FROM progress p
            WHERE p.serverId = n.serverId AND p.userId = n.userId AND p.libraryItemId = n.id
              AND (p.currentTimeSec > 0 OR p.isFinished = 1)
          )
        ORDER BY n.seriesSequence
        LIMIT 1
        """,
    )
    suspend fun nextInSeriesAfter(
        serverId: String,
        userId: String,
        seriesTitle: String,
        afterSequence: Double,
    ): LibraryItemEntity?
}

@Dao
interface LibraryItemFtsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<LibraryItemFtsEntity>)

    @Query("DELETE FROM library_item_fts WHERE serverId = :serverId AND userId = :userId AND itemId IN (:itemIds)")
    suspend fun deleteByItemIds(serverId: String, userId: String, itemIds: List<String>)

    @Query("DELETE FROM library_item_fts WHERE serverId = :serverId AND userId = :userId AND libraryId = :libraryId")
    suspend fun deleteForLibrary(serverId: String, userId: String, libraryId: String)

    /**
     * Re-indexes a batch. Delete-then-insert rather than upsert because an FTS4 table
     * has no unique constraint to conflict on, so an insert alone would duplicate every
     * row on the second sync — and duplicate rows mean duplicate search results.
     */
    @Transaction
    suspend fun replaceAll(serverId: String, userId: String, rows: List<LibraryItemFtsEntity>) {
        if (rows.isEmpty()) return
        deleteByItemIds(serverId, userId, rows.map { it.itemId })
        insertAll(rows)
    }

    /** Drops index rows whose item no longer exists, after a sweep removed stale items. */
    @Query(
        """
        DELETE FROM library_item_fts
        WHERE serverId = :serverId AND userId = :userId AND libraryId = :libraryId
          AND itemId NOT IN (
            SELECT id FROM library_item
            WHERE serverId = :serverId AND userId = :userId AND libraryId = :libraryId
          )
        """,
    )
    suspend fun deleteOrphans(serverId: String, userId: String, libraryId: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download WHERE serverId = :serverId AND userId = :userId ORDER BY requestedAtMs DESC")
    fun observeAll(serverId: String, userId: String): Flow<List<DownloadEntity>>

    @Query(
        """
        SELECT * FROM download
        WHERE serverId = :serverId AND userId = :userId AND libraryItemId = :itemId
        """,
    )
    fun observeForItem(serverId: String, userId: String, itemId: String): Flow<List<DownloadEntity>>

    @Query(
        """
        SELECT * FROM download
        WHERE serverId = :serverId AND userId = :userId
          AND libraryItemId = :itemId AND episodeKey = :episodeKey
        """,
    )
    suspend fun get(serverId: String, userId: String, itemId: String, episodeKey: String): DownloadEntity?

    /**
     * Looks a row up without knowing the account.
     *
     * The download engine only ever learns which *file* changed, and a file id carries
     * the item and episode but not the server or user. Scoping is recovered from the row
     * itself rather than by asking who is signed in, which would be wrong the moment a
     * download outlives a session.
     */
    @Query(
        """
        SELECT * FROM download
        WHERE libraryItemId = :itemId AND episodeKey = :episodeKey
        LIMIT 1
        """,
    )
    suspend fun findAny(itemId: String, episodeKey: String): DownloadEntity?

    @Query("SELECT * FROM download WHERE state != 'completed'")
    suspend fun unfinished(): List<DownloadEntity>

    @Query(
        """
        SELECT * FROM download
        WHERE serverId = :serverId AND userId = :userId AND state = 'completed'
        """,
    )
    suspend fun completed(serverId: String, userId: String): List<DownloadEntity>

    @Query("SELECT COALESCE(SUM(bytesDownloaded), 0) FROM download WHERE serverId = :serverId AND userId = :userId")
    fun observeBytesUsed(serverId: String, userId: String): Flow<Long>

    @Query("SELECT COALESCE(SUM(bytesDownloaded), 0) FROM download")
    suspend fun bytesUsed(): Long

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query(
        """
        UPDATE download SET state = :state, bytesDownloaded = :bytesDownloaded, percent = :percent,
                            bytesTotal = :bytesTotal, completedAtMs = :completedAtMs, error = :error
        WHERE serverId = :serverId AND userId = :userId
          AND libraryItemId = :itemId AND episodeKey = :episodeKey
        """,
    )
    suspend fun updateState(
        serverId: String,
        userId: String,
        itemId: String,
        episodeKey: String,
        state: String,
        bytesDownloaded: Long,
        bytesTotal: Long,
        percent: Float,
        completedAtMs: Long,
        error: String?,
    )

    @Query(
        """
        DELETE FROM download
        WHERE serverId = :serverId AND userId = :userId
          AND libraryItemId = :itemId AND episodeKey = :episodeKey
        """,
    )
    suspend fun delete(serverId: String, userId: String, itemId: String, episodeKey: String)
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

    /**
     * The next episode of this podcast after the one just finished, if it is unplayed.
     *
     * Forwards in publication order rather than "the newest one": someone working
     * through a backlog is moved along it, and someone already at the newest episode is
     * given nothing rather than being sent back to the start of the archive.
     */
    @Query(
        """
        SELECT e.* FROM episode e
        WHERE e.serverId = :serverId AND e.userId = :userId AND e.libraryItemId = :itemId
          AND e.publishedAtMs > :afterPublishedAtMs
          AND NOT EXISTS (
            SELECT 1 FROM progress p
            WHERE p.serverId = e.serverId AND p.userId = e.userId
              AND p.libraryItemId = e.libraryItemId AND p.episodeKey = e.id
              AND p.isFinished = 1
          )
        ORDER BY e.publishedAtMs
        LIMIT 1
        """,
    )
    suspend fun nextAfter(
        serverId: String,
        userId: String,
        itemId: String,
        afterPublishedAtMs: Long,
    ): EpisodeEntity?

    @Query(
        """
        SELECT * FROM episode
        WHERE serverId = :serverId AND userId = :userId AND libraryItemId = :itemId
        ORDER BY publishedAtMs DESC
        """,
    )
    suspend fun forItem(serverId: String, userId: String, itemId: String): List<EpisodeEntity>

    /**
     * The newest episodes not yet finished — what an auto-download rule fetches ahead.
     *
     * Newest first, because the whole point of downloading ahead is having the thing
     * that just came out ready before anyone asks for it.
     */
    @Query(
        """
        SELECT e.* FROM episode e
        WHERE e.serverId = :serverId AND e.userId = :userId AND e.libraryItemId = :itemId
          AND NOT EXISTS (
            SELECT 1 FROM progress p
            WHERE p.serverId = e.serverId AND p.userId = e.userId
              AND p.libraryItemId = e.libraryItemId AND p.episodeKey = e.id
              AND p.isFinished = 1
          )
        ORDER BY e.publishedAtMs DESC
        LIMIT :limit
        """,
    )
    suspend fun latestUnfinished(
        serverId: String,
        userId: String,
        itemId: String,
        limit: Int,
    ): List<EpisodeEntity>

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

/**
 * A queue entry with everything needed to draw it, resolved in the query.
 *
 * The queue stores identity only — an item id and an episode key — because a queue that
 * copied titles would show a stale one after a rename, and would have to be migrated
 * every time the UI wanted one more field. The join is against the mirror, so this works
 * with the network off.
 */
data class QueueRow(
    val libraryItemId: String,
    val episodeKey: String,
    val position: Int,
    val source: String,
    val title: String,
    val author: String?,
    val mediaType: String,
    val durationSec: Double,
    val coverPath: String?,
    val isDownloaded: Boolean,
    val currentTimeSec: Double,
)

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue WHERE serverId = :serverId AND userId = :userId ORDER BY position")
    fun observeQueue(serverId: String, userId: String): Flow<List<QueueEntity>>

    /**
     * The queue as a screen or a car sees it.
     *
     * `LEFT JOIN` throughout, deliberately: an item that has fallen out of the mirror
     * still appears, with whatever is known about it, rather than silently vanishing
     * from a list the listener built by hand.
     */
    @Query(
        """
        SELECT q.libraryItemId AS libraryItemId,
               q.episodeKey AS episodeKey,
               q.position AS position,
               q.source AS source,
               COALESCE(NULLIF(e.title, ''), i.title, '') AS title,
               i.authorName AS author,
               COALESCE(i.mediaType, 'book') AS mediaType,
               COALESCE(NULLIF(e.durationSec, 0), i.durationSec, 0) AS durationSec,
               i.coverPath AS coverPath,
               CASE WHEN d.state = 'completed' THEN 1 ELSE 0 END AS isDownloaded,
               COALESCE(p.currentTimeSec, 0) AS currentTimeSec
        FROM queue q
        LEFT JOIN library_item i
            ON i.serverId = q.serverId AND i.userId = q.userId AND i.id = q.libraryItemId
        LEFT JOIN episode e
            ON e.serverId = q.serverId AND e.userId = q.userId AND e.id = q.episodeKey
        LEFT JOIN download d
            ON d.serverId = q.serverId AND d.userId = q.userId
           AND d.libraryItemId = q.libraryItemId AND d.episodeKey = q.episodeKey
        LEFT JOIN progress p
            ON p.serverId = q.serverId AND p.userId = q.userId
           AND p.libraryItemId = q.libraryItemId AND p.episodeKey = q.episodeKey
        WHERE q.serverId = :serverId AND q.userId = :userId
        ORDER BY q.position
        """,
    )
    fun observeRows(serverId: String, userId: String): Flow<List<QueueRow>>

    @Query("SELECT * FROM queue WHERE serverId = :serverId AND userId = :userId ORDER BY position")
    suspend fun all(serverId: String, userId: String): List<QueueEntity>

    @Query("SELECT * FROM queue WHERE serverId = :serverId AND userId = :userId ORDER BY position LIMIT 1")
    suspend fun head(serverId: String, userId: String): QueueEntity?

    @Query(
        """
        SELECT COUNT(*) FROM queue
        WHERE serverId = :serverId AND userId = :userId
          AND libraryItemId = :libraryItemId AND episodeKey = :episodeKey
        """,
    )
    suspend fun count(serverId: String, userId: String, libraryItemId: String, episodeKey: String): Int

    @Upsert
    suspend fun upsertAll(entries: List<QueueEntity>)

    @Query(
        """
        DELETE FROM queue
        WHERE serverId = :serverId AND userId = :userId
          AND libraryItemId = :libraryItemId AND episodeKey = :episodeKey
        """,
    )
    suspend fun delete(serverId: String, userId: String, libraryItemId: String, episodeKey: String)

    @Query("DELETE FROM queue WHERE serverId = :serverId AND userId = :userId")
    suspend fun clear(serverId: String, userId: String)

    /**
     * Appends, having first removed any existing entry for the same thing.
     *
     * Queuing something already queued moves it rather than duplicating it: two rows for
     * one book would play it twice, and no one has ever meant that.
     */
    @Transaction
    suspend fun addLast(entry: QueueEntity) {
        delete(entry.serverId, entry.userId, entry.libraryItemId, entry.episodeKey)
        val rows = all(entry.serverId, entry.userId)
        upsertAll(rows.renumbered() + entry.copy(position = rows.size))
    }

    /** Puts it at the head, so it plays when whatever is playing now finishes. */
    @Transaction
    suspend fun addFirst(entry: QueueEntity) {
        delete(entry.serverId, entry.userId, entry.libraryItemId, entry.episodeKey)
        val rows = all(entry.serverId, entry.userId)
        upsertAll(listOf(entry.copy(position = 0)) + rows.renumbered(from = 1))
    }

    @Transaction
    suspend fun removeAndRenumber(serverId: String, userId: String, libraryItemId: String, episodeKey: String) {
        delete(serverId, userId, libraryItemId, episodeKey)
        upsertAll(all(serverId, userId).renumbered())
    }

    /** Reorder, by index. Out-of-range indices are a no-op rather than an exception. */
    @Transaction
    suspend fun move(serverId: String, userId: String, from: Int, to: Int) {
        val rows = all(serverId, userId).toMutableList()
        if (from !in rows.indices || to !in rows.indices || from == to) return
        rows.add(to, rows.removeAt(from))
        upsertAll(rows.renumbered())
    }

    /** Pops the head — what end-of-book continuation calls, so an entry is consumed once. */
    @Transaction
    suspend fun takeHead(serverId: String, userId: String): QueueEntity? {
        val head = head(serverId, userId) ?: return null
        delete(serverId, userId, head.libraryItemId, head.episodeKey)
        upsertAll(all(serverId, userId).renumbered())
        return head
    }
}

/**
 * Positions are kept contiguous from zero after every mutation.
 *
 * Gaps would work for ordering but not for the queue screen, which moves a row by index;
 * keeping them dense means the index a listener drags is the position stored.
 *
 * List order is taken as the truth and never re-derived from the old positions — a
 * reorder is precisely a list whose order disagrees with them, so sorting here would
 * undo every move.
 */
private fun List<QueueEntity>.renumbered(from: Int = 0): List<QueueEntity> =
    mapIndexed { index, entry -> entry.copy(position = from + index) }

@Dao
interface PositionHistoryDao {
    @Query(
        """
        SELECT * FROM position_history
        WHERE serverId = :serverId AND userId = :userId AND libraryItemId = :itemId
        ORDER BY atMs DESC LIMIT :limit
        """,
    )
    fun observeForItem(
        serverId: String,
        userId: String,
        itemId: String,
        limit: Int = 50,
    ): Flow<List<PositionHistoryEntity>>

    @Query(
        """
        SELECT * FROM position_history
        WHERE serverId = :serverId AND userId = :userId
        ORDER BY atMs DESC LIMIT :limit
        """,
    )
    fun observeRecent(serverId: String, userId: String, limit: Int = 50): Flow<List<PositionHistoryEntity>>

    @Query(
        """
        SELECT * FROM position_history
        WHERE serverId = :serverId AND userId = :userId AND libraryItemId = :itemId
        ORDER BY atMs DESC LIMIT 1
        """,
    )
    suspend fun mostRecentForItem(serverId: String, userId: String, itemId: String): PositionHistoryEntity?

    @Insert
    suspend fun insert(entry: PositionHistoryEntity)

    /** Keeps the history bounded; recovery only ever needs the recent past. */
    @Query(
        """
        DELETE FROM position_history
        WHERE serverId = :serverId AND userId = :userId AND atMs < :before
        """,
    )
    suspend fun trimOlderThan(serverId: String, userId: String, before: Long)
}
