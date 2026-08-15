package io.github.lightheaded.lugu.core.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
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
import kotlinx.coroutines.SupervisorJob
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
    }

    /** Folds every file of one item into the single row the UI reads. */
    private suspend fun refresh(row: DownloadEntity, error: String? = null) {
        val manifest = runCatching {
            AbsJson.decodeFromString(DownloadManifest.serializer(), row.tracksJson)
        }.getOrNull() ?: return

        val index = downloadManager.downloadIndex
        val files = manifest.tracks.map { track ->
            track to runCatching { index.getDownload(track.cacheKey) }.getOrNull()
        }

        val bytesDownloaded = files.sumOf { (_, download) -> download?.bytesDownloaded ?: 0L }
        val contentLengths = files.map { (_, download) -> download?.contentLength ?: C.LENGTH_UNSET.toLong() }
        val bytesTotal = if (contentLengths.all { it > 0 }) contentLengths.sum() else row.bytesTotal

        // Falling back to a duration-weighted average matters for the first seconds of a
        // multi-file book, when most files have not been opened and their sizes are
        // still unknown — a naive bytes/total would read 0% for a while and look stuck.
        val percent = if (bytesTotal > 0 && contentLengths.all { it > 0 }) {
            (bytesDownloaded.toDouble() / bytesTotal).toFloat()
        } else {
            val weight = files.sumOf { (track, _) -> track.durationSec }.takeIf { it > 0 } ?: 1.0
            files.sumOf { (track, download) ->
                val fraction = download?.percentDownloaded?.takeIf { it >= 0f }?.div(100f) ?: 0f
                track.durationSec * fraction
            }.div(weight).toFloat()
        }.coerceIn(0f, 1f)

        val states = files.map { (_, download) -> download?.state }
        val state = when {
            states.all { it == Download.STATE_COMPLETED } -> DownloadState.COMPLETED
            states.any { it == Download.STATE_FAILED } -> DownloadState.FAILED
            states.any { it == Download.STATE_DOWNLOADING } -> DownloadState.DOWNLOADING
            states.all { it == null } -> DownloadState.FAILED
            else -> DownloadState.QUEUED
        }

        downloadDao.updateState(
            serverId = row.serverId,
            userId = row.userId,
            itemId = row.libraryItemId,
            episodeKey = row.episodeKey,
            state = state,
            bytesDownloaded = bytesDownloaded,
            bytesTotal = bytesTotal,
            percent = percent,
            completedAtMs = if (state == DownloadState.COMPLETED) clock.nowMs() else row.completedAtMs,
            error = if (state == DownloadState.FAILED) error ?: row.error else null,
        )
    }

    private companion object {
        /**
         * Three at a time. Higher numbers do not finish a book sooner on a phone
         * connection and do make the progress bar jump around between files.
         */
        const val MAX_PARALLEL_DOWNLOADS = 3
    }
}
