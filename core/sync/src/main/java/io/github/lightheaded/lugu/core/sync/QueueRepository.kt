package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.db.EpisodeDao
import io.github.lightheaded.lugu.core.db.EpisodeEntity
import io.github.lightheaded.lugu.core.db.ItemSeriesDao
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

/**
 * A copy of the whole queue, held only long enough for an undo to use it.
 *
 * Opaque on purpose: it carries stored rows, which are the database's business and not a
 * screen's. A caller can say how many there were and hand it back, and nothing else.
 */
class QueueSnapshot internal constructor(internal val entries: List<QueueEntity>) {
    val size: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()
}

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

/**
 * Which way round a podcast runs.
 *
 * Kept pure and apart from the queue because it is the whole of the disagreement upstream
 * has two open threads about (app#473 and server#1321): continuation always walked forwards
 * in publication order, which is right for a serial being worked through and wrong for a
 * news show, where the thing to play after this morning's episode is yesterday's, not next
 * week's. No client can tell the two apart from the feed, so it is a setting — and being a
 * setting, it is worth being able to hold it to a test rather than to a database ordering.
 */
object PodcastOrder {

    /**
     * The episode to play after the one that has just finished, or null at the end of the
     * road in whichever direction is being travelled.
     *
     * [candidates] is every unfinished episode of the one podcast, in any order — as
     * `EpisodeDao.latestUnfinished` returns them. Oldest-first listening moves up that list
     * to the earliest episode published after the one just heard; newest-first moves down it
     * to the latest episode published before it. Both comparisons are strict, so two
     * episodes sharing a publication instant cannot follow each other round in a circle.
     */
    fun nextEpisode(
        candidates: List<EpisodeEntity>,
        afterPublishedAtMs: Long,
        oldestFirst: Boolean,
    ): EpisodeEntity? = if (oldestFirst) {
        candidates.filter { it.publishedAtMs > afterPublishedAtMs }.minByOrNull { it.publishedAtMs }
    } else {
        candidates.filter { it.publishedAtMs < afterPublishedAtMs }.maxByOrNull { it.publishedAtMs }
    }

    /** What the notice says, which differs because one direction goes back through the archive. */
    fun reasonFor(oldestFirst: Boolean): String = if (oldestFirst) "Next episode" else "Earlier episode"
}

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
    private val seriesDao: ItemSeriesDao,
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
     * The queue exactly as it is stored, so that an undo can put it back.
     *
     * Taken at the entity level rather than from [observe], because that is what makes the
     * restore exact: position, the time each entry was added, and whether a continuation
     * rule put it there rather than the listener all survive the round trip. Rebuilding
     * from titles would quietly turn suggestions into choices and lose the original order.
     */
    suspend fun snapshot(account: ActiveAccount): QueueSnapshot =
        QueueSnapshot(queueDao.all(account.serverId, account.userId))

    /** Puts [snapshot] back, replacing whatever is queued now. */
    suspend fun restore(account: ActiveAccount, snapshot: QueueSnapshot) {
        queueDao.clear(account.serverId, account.userId)
        if (snapshot.entries.isNotEmpty()) queueDao.upsertAll(snapshot.entries)
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
            // This podcast's own answer where it has one, the default otherwise.
            val oldestFirst = queuePrefs.podcastOldestFirst(finishedItemId)
            // Unbounded on purpose: the episode to play next can be anywhere in the
            // backlog, and a window would quietly answer with the wrong one for anybody
            // working through an archive. The list is one podcast's unfinished episodes,
            // which the feed itself keeps to a size worth holding.
            val candidates = episodeDao.latestUnfinished(
                account.serverId,
                account.userId,
                finishedItemId,
                Int.MAX_VALUE,
            )
            val next = PodcastOrder.nextEpisode(candidates, finished.publishedAtMs, oldestFirst) ?: return null
            return NextUp.Suggested(
                resolve(account, finishedItemId, next.id, true),
                reason = PodcastOrder.reasonFor(oldestFirst),
            )
        }

        if (!settings.continueSeries) return null
        // Every series this book is in, not just one of them. A book can end two series at
        // once — the second Breakwater book is also the first Riverton one — and the item's
        // own series columns can only name one, so asking them meant the other series
        // silently stopped continuing. Memberships come back with the numbered ones first,
        // so the series with a known position gets the first say.
        seriesDao.forItem(account.serverId, account.userId, finishedItemId).forEach { membership ->
            val sequence = membership.sequence ?: return@forEach
            val next = itemDao.nextInSeriesAfter(
                account.serverId,
                account.userId,
                membership.seriesName,
                sequence,
            ) ?: return@forEach
            return NextUp.Suggested(
                resolve(account, next.id, null, true),
                reason = "Next in ${membership.seriesName}",
            )
        }
        return null
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
