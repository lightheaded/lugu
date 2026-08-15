package io.github.lightheaded.lugu.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.workmanager.WorkManagerScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The foreground service that actually moves the bytes.
 *
 * Media3's [DownloadService] is used rather than a hand-rolled downloader because the
 * hard parts are already solved in it: resuming a part-finished file after the process
 * dies, honouring network and charging requirements, and restarting after a reboot. A
 * two-gigabyte book that has to start over because the phone slept is the failure this
 * avoids.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class LuguDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.lugu_download_channel_name,
    0,
) {
    @Inject lateinit var engine: DownloadEngine

    override fun getDownloadManager(): DownloadManager {
        ensureChannel()
        return engine.downloadManager
    }

    /**
     * Restarts stalled downloads when their requirements come back — off Wi-Fi and back
     * on, or plugged in again. Without a scheduler a download interrupted by a walk out
     * of range simply never resumes.
     */
    override fun getScheduler(): Scheduler = WorkManagerScheduler(this, WORK_NAME)

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification {
        ensureChannel()

        val active = downloads.filter { it.state == Download.STATE_DOWNLOADING }
        val percent = active
            .map { it.percentDownloaded }
            .filter { it >= 0f }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toInt()

        val text = when {
            notMetRequirements != 0 -> "Waiting for Wi-Fi or power"
            active.isEmpty() -> "Preparing…"
            active.size == 1 -> "Downloading 1 item"
            else -> "Downloading ${active.size} items"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("lugu")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (percent != null) setProgress(100, percent, false)
            }
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.lugu_download_channel_name),
                // Downloads are background work someone asked for and then stopped
                // thinking about; they should never interrupt.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val CHANNEL_ID = "lugu_downloads"
        const val FOREGROUND_NOTIFICATION_ID = 4102
        private const val WORK_NAME = "lugu-download-scheduler"
    }
}
