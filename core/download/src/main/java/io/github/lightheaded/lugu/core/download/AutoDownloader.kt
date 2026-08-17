package io.github.lightheaded.lugu.core.download

import io.github.lightheaded.lugu.core.db.DownloadDao
import io.github.lightheaded.lugu.core.db.EpisodeDao
import io.github.lightheaded.lugu.core.db.LibraryItemDao
import io.github.lightheaded.lugu.core.db.ProgressDao
import io.github.lightheaded.lugu.core.db.QueueDao
import io.github.lightheaded.lugu.core.db.episodeKeyOf
import io.github.lightheaded.lugu.core.db.toEpisodeIdOrNull
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.DownloadPrefs
import io.github.lightheaded.lugu.core.sync.DownloadSettings
import javax.inject.Inject
import javax.inject.Singleton

/** What one pass of the rules did, so the caller can say so rather than guess. */
data class AutoDownloadResult(val queued: Int, val refused: Int)

/**
 * Downloading ahead of being asked.
 *
 * Every rule here is off by default and each is separately switchable, because this is
 * the app spending someone's storage and data on a prediction. The prediction is only
 * ever about things already chosen: what is in the queue, the next volume of a series
 * being read, the newest episodes of a podcast being listened to. Nothing is ever
 * fetched because it looked interesting.
 *
 * The storage cap is not special-cased. A rule that hits it is refused exactly as a
 * button press would be, and refusals are counted rather than retried — an automatic
 * download hammering a full cap would drain a battery to no effect.
 */
@Singleton
class AutoDownloader @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val downloadPrefs: DownloadPrefs,
    private val queueDao: QueueDao,
    private val itemDao: LibraryItemDao,
    private val episodeDao: EpisodeDao,
    private val progressDao: ProgressDao,
    private val downloadDao: DownloadDao,
) {
    suspend fun run(account: ActiveAccount): AutoDownloadResult {
        val settings = downloadPrefs.current()
        if (!settings.hasAutoDownloadRule) return AutoDownloadResult(queued = 0, refused = 0)

        val wanted = buildList {
            if (settings.autoDownloadQueue) addAll(fromQueue(account))
            if (settings.autoDownloadNextInSeries > 0) addAll(fromSeries(account, settings))
            if (settings.autoDownloadLatestEpisodes > 0) addAll(fromPodcasts(account, settings))
        }.distinct()

        var queued = 0
        var refused = 0
        for (target in wanted) {
            if (hasRow(account, target)) continue
            downloadRepository.download(account, target.itemId, target.episodeId)
                .onSuccess { queued += 1 }
                .onFailure { refused += 1 }
        }
        return AutoDownloadResult(queued, refused)
    }

    /** Everything queued, because the queue is a statement about what happens next. */
    private suspend fun fromQueue(account: ActiveAccount): List<Target> =
        queueDao.all(account.serverId, account.userId)
            .map { Target(it.libraryItemId, it.episodeKey.toEpisodeIdOrNull()) }

    /**
     * The next unstarted volumes of every series already being read.
     *
     * Ordered by sequence, like everything else that touches a series, and taken from
     * after the last volume with any progress — so finishing book two fetches book
     * three, and a series nobody has started fetches nothing.
     */
    private suspend fun fromSeries(account: ActiveAccount, settings: DownloadSettings): List<Target> =
        itemDao.seriesTitles(account.serverId, account.userId).flatMap { title ->
            // Numbered by *this* series, asked of the join table rather than of the item's
            // own `seriesSequence` column. That column is re-derived for the primary series
            // only, so for a book in two series it was the other series' number — which
            // dropped books that this series numbers and kept books that it does not.
            val volumes = itemDao.bySeriesNumbered(account.serverId, account.userId, title)
            val lastStarted = volumes.indexOfLast { hasProgress(account, it.id) }
            if (lastStarted < 0) {
                emptyList()
            } else {
                volumes.drop(lastStarted + 1)
                    .filterNot { hasProgress(account, it.id) }
                    .take(settings.autoDownloadNextInSeries)
                    .map { Target(it.id, null) }
            }
        }

    private suspend fun fromPodcasts(account: ActiveAccount, settings: DownloadSettings): List<Target> =
        itemDao.followedPodcasts(account.serverId, account.userId).flatMap { podcast ->
            episodeDao.latestUnfinished(
                account.serverId,
                account.userId,
                podcast.id,
                settings.autoDownloadLatestEpisodes,
            ).map { Target(podcast.id, it.id) }
        }

    private suspend fun hasProgress(account: ActiveAccount, itemId: String): Boolean {
        val row = progressDao.get(account.serverId, account.userId, itemId, "") ?: return false
        return row.currentTimeSec > 0 || row.isFinished
    }

    /**
     * Anything with a row already — completed, in flight, or failed — is left alone.
     *
     * Failed included, deliberately: a rule that retried a failing download every six
     * hours forever would be indistinguishable from a bug, and the failure is already
     * visible on the Downloads screen with a retry button next to it.
     */
    private suspend fun hasRow(account: ActiveAccount, target: Target): Boolean =
        downloadDao.get(
            account.serverId,
            account.userId,
            target.itemId,
            episodeKeyOf(target.episodeId),
        ) != null

    private data class Target(val itemId: String, val episodeId: String?)
}
