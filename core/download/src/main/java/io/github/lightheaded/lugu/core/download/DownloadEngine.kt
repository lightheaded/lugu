package io.github.lightheaded.lugu.core.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.api.AbsJson
import io.github.lightheaded.lugu.core.db.DownloadDao
import io.github.lightheaded.lugu.core.db.DownloadEntity
import io.github.lightheaded.lugu.core.db.DownloadState
import io.github.lightheaded.lugu.core.sync.Clock
import io.github.lightheaded.lugu.core.sync.DownloadPrefs
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the Media3 [DownloadManager] and keeps Room's view of it honest.
 *
 * Media3 tracks downloads one file at a time; a listener thinks in books. This class is
 * the translation: each file's progress is folded back into the item row that a screen
 * can render, and that row carries the manifest, so an item stays playable offline even
 * after this process has died and the download index is all that survived.
 */
@OptIn(UnstableApi::class)
@Singleton
class DownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadCache: DownloadCache,
    private val downloadDao: DownloadDao,
    private val downloadPrefs: DownloadPrefs,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialises recomputation so two file events for one book cannot interleave. */
    private val refreshLock = Mutex()

    /** Runs only while something is downloading; see [startTicking]. */
    private var tickerJob: Job? = null

    private val downloadManagerLazy = lazy {
        DownloadManager(
            context,
            downloadCache.databaseProvider,
            downloadCache.cache,
            downloadCache.httpDataSourceFactory(),
            Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS),
        ).apply {
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
            addListener(ProgressListener())
        }
    }

    val downloadManager: DownloadManager by downloadManagerLazy

    init {
        // Requirements follow settings live, so turning Wi-Fi-only on mid-download pauses
        // what is running rather than waiting until the next download to take effect.
        scope.launch {
            downloadPrefs.settings.collect { settings ->
                // Only if something has already needed the manager: constructing it here
                // would open the download database on every cold start, for everyone,
                // including people who have never downloaded anything.
                if (downloadManagerLazy.isInitialized()) {
                    applyRequirements(settings.wifiOnly, settings.requiresCharging)
                }
            }
        }
    }

    /** Applies the current network and power rules; safe to call whenever they change. */
    fun applyRequirements(wifiOnly: Boolean, requiresCharging: Boolean) {
        var flags = if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK
        if (requiresCharging) flags = flags or Requirements.DEVICE_CHARGING
        downloadManager.requirements = Requirements(flags)
    }

    /**
     * Rebuilds every unfinished row from the download index.
     *
     * Called at startup because downloads outlive the app: a book can finish while the
     * process is dead, and without this the UI would still show it at 40% forever.
     */
    suspend fun reconcile() {
        val settings = downloadPrefs.current()
        applyRequirements(settings.wifiOnly, settings.requiresCharging)
        downloadDao.unfinished().forEach { refresh(it) }
        startTicking()
    }

    private inner class ProgressListener : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            onFileEvent(download.request.id, finalException?.message)
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            onFileEvent(download.request.id, null)
        }
    }

    private fun onFileEvent(fileId: String, error: String?) {
        val (itemId, episodeKey, _) = DownloadKeys.parse(fileId) ?: return
        scope.launch {
            refreshLock.withLock {
                downloadDao.findAny(itemId, episodeKey)?.let { refresh(it, error) }
            }
        }
        startTicking()
    }

    /**
     * Polls while bytes are actually moving, because nothing else will.
     *
     * [DownloadManager.Listener] fires when a download changes *state* — queued,
     * downloading, completed — and never once in between, so a row written at "queued"
     * stayed at 0% until the file finished. The notification looked fine throughout,
     * because [DownloadService] polls on its own timer, and the app looked frozen next
     * to it: a 629 MB book with a visible bar in the shade and nothing moving in the
     * screen that started it.
     *
     * The tick stops as soon as no file is downloading, so a download parked waiting for
     * Wi-Fi costs nothing; the state change that resumes it starts the tick again.
     */
    private fun startTicking() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (downloadManager.currentDownloads.any { it.state == Download.STATE_DOWNLOADING }) {
                delay(PROGRESS_TICK_MS)
                refreshLock.withLock { downloadDao.unfinished().forEach { refresh(it) } }
            }
        }
    }

    /** Folds every file of one item into the single row the UI reads. */
    private suspend fun refresh(row: DownloadEntity, error: String? = null) {
        val manifest = runCatching {
            AbsJson.decodeFromString(DownloadManifest.serializer(), row.tracksJson)
        }.getOrNull() ?: return

        // Media3's own Download objects stop at this line; everything past it works on
        // plain values, so the fold can be exercised without a DownloadManager.
        val index = downloadManager.downloadIndex
        val files = manifest.tracks.map { track ->
            val download = runCatching { index.getDownload(track.cacheKey) }.getOrNull()
            track to download?.let {
                FileProgress(
                    state = it.state,
                    bytesDownloaded = it.bytesDownloaded,
                    contentLength = it.contentLength,
                    percentDownloaded = it.percentDownloaded,
                )
            }
        }

        val folded = DownloadAggregation.fold(files, row.bytesTotal)

        downloadDao.updateState(
            serverId = row.serverId,
            userId = row.userId,
            itemId = row.libraryItemId,
            episodeKey = row.episodeKey,
            state = folded.state,
            bytesDownloaded = folded.bytesDownloaded,
            bytesTotal = folded.bytesTotal,
            percent = folded.percent,
            completedAtMs = if (folded.state == DownloadState.COMPLETED) clock.nowMs() else row.completedAtMs,
            error = if (folded.state == DownloadState.FAILED) error ?: row.error else null,
        )
    }

    private companion object {
        /**
         * Three at a time. Higher numbers do not finish a book sooner on a phone
         * connection and do make the progress bar jump around between files.
         */
        const val MAX_PARALLEL_DOWNLOADS = 3

        /** Matches the download notification's own update interval; a bar that moves once a second reads as alive. */
        const val PROGRESS_TICK_MS = 1_000L
    }
}
