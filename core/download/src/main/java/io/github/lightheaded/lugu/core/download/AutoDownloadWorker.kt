package io.github.lightheaded.lugu.core.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.DownloadPrefs
import io.github.lightheaded.lugu.core.sync.NewEpisode
import io.github.lightheaded.lugu.core.sync.PodcastRefresher
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

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
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = downloadPrefs.current()
        val wantsEpisodes = settings.notifyNewEpisodes || settings.autoDownloadLatestEpisodes > 0
        if (!settings.hasAutoDownloadRule && !settings.notifyNewEpisodes) return Result.success()

        val account = authRepository.account() ?: return Result.success()

        if (wantsEpisodes) {
            val new = runCatching { podcastRefresher.refreshFollowed(account) }
                .getOrElse { return Result.retry() }
            if (settings.notifyNewEpisodes) notifier.notify(new)
        }

        runCatching { autoDownloader.run(account) }.getOrElse { return Result.retry() }
        return Result.success()
    }
}

/**
 * The extras a new-episode notification carries to the activity it opens.
 *
 * Declared here, beside the code that writes them, rather than in `:app`: this module
 * cannot see the app module, and a pair of string keys copied into two places is a pair of
 * string keys that will disagree one day.
 */
object NewEpisodeIntent {
    const val EXTRA_LIBRARY_ITEM_ID = "io.github.lightheaded.lugu.extra.LIBRARY_ITEM_ID"
    const val EXTRA_EPISODE_ID = "io.github.lightheaded.lugu.extra.EPISODE_ID"
}

/**
 * Says when a podcast being listened to has published something.
 *
 * Off by default and one notification for the whole batch rather than one each: waking
 * up to eleven separate notifications is how a podcast app gets its notifications turned
 * off permanently.
 */
@Singleton
class NewEpisodeNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun notify(episodes: List<NewEpisode>) {
        if (episodes.isEmpty()) return
        // Below API 33 the permission does not exist and notifications are allowed by
        // default; above it, being refused is an answer to respect silently.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel()

        val title = if (episodes.size == 1) {
            "New episode of ${episodes.first().podcastTitle}"
        } else {
            "${episodes.size} new episodes"
        }
        val text = episodes.take(MAX_LISTED).joinToString(" · ") {
            if (episodes.size == 1) it.episodeTitle else "${it.podcastTitle}: ${it.episodeTitle}"
        }

        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openEpisode(tapTarget(episodes)))
            .setAutoCancel(true)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    /**
     * Opens one episode's page, with the library underneath it.
     *
     * The launcher intent is still the base, so the activity starts the way it always
     * starts and the graph comes up with Home at its root; the two ids ride on it as
     * extras. That is what lets back from the episode page reach the library on a cold tap
     * instead of leaving the app.
     *
     * `FLAG_UPDATE_CURRENT` with a fixed request code, because the notification id is fixed
     * too: a second batch replaces the first notification, so its tap must replace the
     * first one's extras as well. Without the flag the new notification would open the
     * episode the old one was about.
     */
    private fun openEpisode(episode: NewEpisode): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        intent.putExtra(NewEpisodeIntent.EXTRA_LIBRARY_ITEM_ID, episode.libraryItemId)
        intent.putExtra(NewEpisodeIntent.EXTRA_EPISODE_ID, episode.episodeId)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "New episodes",
                // A new episode is news, not an interruption: it can wait for the next
                // time the phone is picked up.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private companion object {
        const val CHANNEL_ID = "lugu_episodes"
        const val NOTIFICATION_ID = 4103
        const val MAX_LISTED = 5
    }
}

/**
 * Which episode a tap opens when the notification is about several.
 *
 * The batch is deliberate — one notification for eleven episodes rather than eleven
 * notifications — so a tap has to mean something for a list rather than for one row. It
 * opens the **first** episode in the list, which is the newest: `PodcastRefresher` returns
 * them newest first, and the notification's own text names them in that order, so the tap
 * lands on the episode the reader was already looking at.
 *
 * The alternative was to send a batch of more than one to Home and let the reader find
 * them. That was rejected because it is the old behaviour under a new name: it throws away
 * the one thing the notification knows exactly when it is most useful, and a batch of two
 * is far commoner than a batch of eleven. Nothing is lost either way — back from the
 * episode page is Home, which is where the rest of the batch is reachable from.
 */
internal fun tapTarget(episodes: List<NewEpisode>): NewEpisode = episodes.first()

object DownloadScheduler {
    private const val AUTO_DOWNLOAD_WORK = "lugu-auto-download"

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
}
