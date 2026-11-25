package io.github.lightheaded.lugu.core.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.lightheaded.lugu.core.db.EpisodeDao
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.DownloadPrefs
import io.github.lightheaded.lugu.core.sync.NewEpisode
import io.github.lightheaded.lugu.core.sync.PodcastRefresher
import io.github.lightheaded.lugu.core.sync.SyncRetry
import java.util.concurrent.TimeUnit

/**
 * The periodic pass that keeps ahead of the listener.
 *
 * Refreshing podcasts and applying the download rules are one job because they are one
 * thought: an episode that arrived is exactly the thing a rule would want to fetch, and
 * splitting them would mean waiting up to another six hours between finding out and
 * acting on it.
 *
 * It does nothing at all unless something has been switched on. Every rule and the
 * notification are off by default, so an untouched install schedules a worker that
 * returns immediately — cheaper than deciding whether to schedule it at each change.
 */
@HiltWorker
class AutoDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val authRepository: AuthRepository,
    private val downloadPrefs: DownloadPrefs,
    private val podcastRefresher: PodcastRefresher,
    private val autoDownloader: AutoDownloader,
    private val notifier: NewEpisodeNotifier,
    private val episodeDao: EpisodeDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = downloadPrefs.current()
        val wantsEpisodes = settings.notifyNewEpisodes || settings.autoDownloadLatestEpisodes > 0
        if (!settings.hasAutoDownloadRule && !settings.notifyNewEpisodes) return Result.success()

        val account = authRepository.account() ?: return Result.success()
        // Nothing here can reach the server without a token: every feed refresh and every
        // download would fail one by one. The pass ends instead. The sign-in that ends the
        // lapse runs it again at once (DownloadScheduler.runNow), so new episodes do not
        // wait for the next period. See SyncRetry.
        if (authRepository.lapsedAccount() != null) return Result.success()

        if (wantsEpisodes) {
            val new = runCatching { podcastRefresher.refreshFollowed(account) }
                .getOrElse { return retryUnlessLapsed(it) }
            if (settings.notifyNewEpisodes) notifier.notify(account, new, descriptionsOf(account, new))
        }

        runCatching { autoDownloader.run(account) }.getOrElse { return retryUnlessLapsed(it) }
        return Result.success()
    }

    /** The session can also end during the pass, when the server refuses a refresh. */
    private fun retryUnlessLapsed(error: Throwable): Result =
        if (SyncRetry.worthRetrying(error)) Result.retry() else Result.success()

    /**
     * The feed descriptions for the snippet, read from the rows the refresh just wrote.
     *
     * Read here rather than carried on [NewEpisode], which is the refresher's answer to
     * "what is new" and has no use for the text. A row that cannot be read costs that one
     * notification its snippet and nothing else.
     */
    private suspend fun descriptionsOf(
        account: ActiveAccount,
        episodes: List<NewEpisode>,
    ): Map<String, String?> = episodes.take(NewEpisodeNotifier.MAX_ROWS).associate { episode ->
        episode.episodeId to runCatching {
            episodeDao.byId(account.serverId, account.userId, episode.episodeId)?.description
        }.getOrNull()
    }
}

object DownloadScheduler {
    private const val AUTO_DOWNLOAD_WORK = "lugu-auto-download"
    private const val AUTO_DOWNLOAD_NOW_WORK = "lugu-auto-download-now"

    /**
     * Unmetered and idle-friendly regardless of the Wi-Fi-only setting.
     *
     * The setting governs downloads someone asked for; this is lugu deciding on its own,
     * and deciding on its own to spend mobile data is not a thing it should ever do.
     */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<AutoDownloadWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(AUTO_DOWNLOAD_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * One pass now, because a sign-in has just made the server reachable.
     *
     * A pass during a lapse ends without a retry, and the periodic one keeps `KEEP`, so
     * without this a new episode waited up to six hours after the sign-in. The constraints
     * are the periodic pass's own, for the reason [schedulePeriodic] gives. A pass with
     * nothing switched on returns at once, so this costs nothing on an untouched install.
     */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutoDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(AUTO_DOWNLOAD_NOW_WORK, ExistingWorkPolicy.REPLACE, request)
    }
}
