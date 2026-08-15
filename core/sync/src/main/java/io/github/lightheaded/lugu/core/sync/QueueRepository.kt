package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.db.EpisodeDao
import io.github.lightheaded.lugu.core.db.LibraryItemDao
import io.github.lightheaded.lugu.core.db.QueueDao
import io.github.lightheaded.lugu.core.db.QueueEntity
import io.github.lightheaded.lugu.core.db.QueueRow
import io.github.lightheaded.lugu.core.db.QueueSource
import io.github.lightheaded.lugu.core.db.episodeKeyOf
import io.github.lightheaded.lugu.core.db.toEpisodeIdOrNull
import io.github.lightheaded.lugu.core.model.MediaType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One thing waiting to be played. */
data class QueueItem(
    val libraryItemId: String,
    val episodeId: String?,
    val title: String,
    val author: String?,
    val mediaType: MediaType,
    val durationSec: Double,
    val coverPath: String?,
    val isDownloaded: Boolean,
    val currentTimeSec: Double,
    /** True when a continuation rule suggested it rather than the listener adding it. */
    val isSuggestion: Boolean,
)

/** Where the next thing to play came from, which decides whether it starts unasked. */
sealed interface NextUp {
    /** The listener put it there. Plays without ceremony. */
    data class Queued(val item: QueueItem) : NextUp

    /** A rule worked it out. Subject to the "ask first" setting. */
    data class Suggested(val item: QueueItem, val reason: String) : NextUp
}

/**
 * The play queue, and what happens when a book ends.
 *
 * The queue is deliberately device-local. Audiobookshelf has no queue concept to sync
 * against — the nearest thing is a playlist, which is a different idea with a different
 * lifetime — so mirroring one to the server is an option to offer later, not a
 * foundation to build on. Everything here works offline and survives process death,
 * because a car is exactly where both of those are tested.
 */
@Singleton
class QueueRepository @Inject constructor(
    private val queueDao: QueueDao,
    private val itemDao: LibraryItemDao,
    private val episodeDao: EpisodeDao,
    private val queuePrefs: QueuePrefs,
    private val clock: Clock,
) {
    fun observe(account: ActiveAccount): Flow<List<QueueItem>> =
        queueDao.observeRows(account.serverId, account.userId).map { rows -> rows.map { it.toItem() } }

    suspend fun contains(account: ActiveAccount, itemId: String, episodeId: String?): Boolean =
        queueDao.count(account.serverId, account.userId, itemId, episodeKeyOf(episodeId)) > 0

    suspend fun addLast(account: ActiveAccount, itemId: String, episodeId: String?) {
        queueDao.addLast(entry(account, itemId, episodeId, QueueSource.USER))
    }

    suspend fun addNext(account: ActiveAccount, itemId: String, episodeId: String?) {
        queueDao.addFirst(entry(account, itemId, episodeId, QueueSource.USER))
    }

    suspend fun remove(account: ActiveAccount, itemId: String, episodeId: String?) {
        queueDao.removeAndRenumber(account.serverId, account.userId, itemId, episodeKeyOf(episodeId))
    }

    suspend fun move(account: ActiveAccount, from: Int, to: Int) {
        queueDao.move(account.serverId, account.userId, from, to)
    }

    suspend fun clear(account: ActiveAccount) {
        queueDao.clear(account.serverId, account.userId)
    }

    /**
     * What to play now that something has finished, and whether it was asked for.
     *
     * The queue always wins: an entry there is an instruction, and an instruction
     * outranks a guess. Only when the queue is empty do the continuation rules run, and
     * what they return is marked as a suggestion so the caller can honour "ask first".
     */
    suspend fun next(account: ActiveAccount, finishedItemId: String, finishedEpisodeId: String?): NextUp? {
        queueDao.takeHead(account.serverId, account.userId)?.let { head ->
            return NextUp.Queued(resolve(account, head.libraryItemId, head.episodeKey.toEpisodeIdOrNull(), false))
        }

        val settings = queuePrefs.current()
        if (finishedEpisodeId != null) {
            if (!settings.continuePodcast) return null
            val finished = episodeDao.byId(account.serverId, account.userId, finishedEpisodeId) ?: return null
            val next = episodeDao.nextAfter(
                account.serverId,
                account.userId,
                finishedItemId,
                finished.publishedAtMs,
            ) ?: return null
            return NextUp.Suggested(
                resolve(account, finishedItemId, next.id, true),
                reason = "Next episode",
            )
        }

        if (!settings.continueSeries) return null
        val finished = itemDao.byId(account.serverId, account.userId, finishedItemId) ?: return null
        val seriesTitle = finished.seriesTitle ?: return null
        val sequence = finished.seriesSequence ?: return null
        val next = itemDao.nextInSeriesAfter(account.serverId, account.userId, seriesTitle, sequence) ?: return null
        return NextUp.Suggested(resolve(account, next.id, null, true), reason = "Next in $seriesTitle")
    }

    /**
     * Puts a suggestion at the head of the queue so a "start it?" prompt has something
     * to point at, and so dismissing the prompt does not lose the answer.
     */
    suspend fun offer(account: ActiveAccount, item: QueueItem) {
        queueDao.addFirst(entry(account, item.libraryItemId, item.episodeId, QueueSource.AUTO))
    }

    private suspend fun resolve(
        account: ActiveAccount,
        itemId: String,
        episodeId: String?,
        isSuggestion: Boolean,
    ): QueueItem {
        val item = itemDao.byId(account.serverId, account.userId, itemId)
        val episode = episodeId?.let { episodeDao.byId(account.serverId, account.userId, it) }
        return QueueItem(
            libraryItemId = itemId,
            episodeId = episodeId,
            title = episode?.title?.takeIf { it.isNotBlank() } ?: item?.title.orEmpty(),
            author = item?.authorName,
            mediaType = if (episodeId != null) MediaType.PODCAST else MediaType.BOOK,
            durationSec = episode?.durationSec ?: item?.durationSec ?: 0.0,
            coverPath = item?.coverPath,
            isDownloaded = false,
            currentTimeSec = 0.0,
            isSuggestion = isSuggestion,
        )
    }

    private fun entry(account: ActiveAccount, itemId: String, episodeId: String?, source: String) = QueueEntity(
        serverId = account.serverId,
        userId = account.userId,
        libraryItemId = itemId,
        episodeKey = episodeKeyOf(episodeId),
        position = 0,
        addedAtMs = clock.nowMs(),
        source = source,
    )

    private fun QueueRow.toItem() = QueueItem(
        libraryItemId = libraryItemId,
        episodeId = episodeKey.toEpisodeIdOrNull(),
        title = title,
        author = author,
        mediaType = if (episodeKey.isNotEmpty()) MediaType.PODCAST else MediaType.fromWire(mediaType),
        durationSec = durationSec,
        coverPath = coverPath,
        isDownloaded = isDownloaded,
        currentTimeSec = currentTimeSec,
        isSuggestion = source == QueueSource.AUTO,
    )
}
