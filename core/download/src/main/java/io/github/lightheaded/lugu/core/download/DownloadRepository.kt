package io.github.lightheaded.lugu.core.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.AbsJson
import io.github.lightheaded.lugu.core.api.toDomain
import io.github.lightheaded.lugu.core.db.DownloadDao
import io.github.lightheaded.lugu.core.db.DownloadEntity
import io.github.lightheaded.lugu.core.db.DownloadState
import io.github.lightheaded.lugu.core.db.ProgressDao
import io.github.lightheaded.lugu.core.db.episodeKeyOf
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.Clock
import io.github.lightheaded.lugu.core.sync.DownloadPrefs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** What a screen needs to know about one item's download. */
data class DownloadStatus(
    val libraryItemId: String,
    val episodeId: String?,
    val title: String,
    val author: String?,
    val state: String,
    val percent: Float,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val error: String?,
) {
    val isComplete: Boolean get() = state == DownloadState.COMPLETED
    val isActive: Boolean get() = state == DownloadState.DOWNLOADING || state == DownloadState.QUEUED
    val isFailed: Boolean get() = state == DownloadState.FAILED
}

/** Refused before anything is enqueued, so the reason can be shown rather than guessed at. */
sealed interface DownloadRefusal {
    data class OverStorageCap(val usedBytes: Long, val capBytes: Long, val neededBytes: Long) : DownloadRefusal

    data object NoAudioFiles : DownloadRefusal
}

class DownloadRefusedException(val refusal: DownloadRefusal) : Exception(describe(refusal)) {
    private companion object {
        /**
         * A refusal states its arithmetic.
         *
         * "You are over your cap" with no numbers is unfalsifiable: it reads as a setting
         * the listener chose badly, when it can just as easily be the app's own estimate
         * being wrong. Printing all three numbers makes a bad estimate visible as a bad
         * estimate — which is exactly how the podcast feed-size bug was reported.
         */
        fun describe(refusal: DownloadRefusal): String = when (refusal) {
            is DownloadRefusal.OverStorageCap ->
                "Needs ${formatBytes(refusal.neededBytes)}, and " +
                    "${formatBytes(refusal.usedBytes)} of the " +
                    "${formatBytes(refusal.capBytes)} cap is already used. Raise the cap " +
                    "in Settings, or remove a download."

            DownloadRefusal.NoAudioFiles -> "The server lists no audio files for this item."
        }
    }
}

/** Bytes as a listener would say them. Binary units, because that is what the cap is set in. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val gb = bytes / (1024.0 * 1024 * 1024)
    if (gb >= 1) return "%.1f GB".format(gb)
    val mb = bytes / (1024.0 * 1024)
    if (mb >= 1) return "%.0f MB".format(mb)
    return "%.0f KB".format(bytes / 1024.0)
}

/**
 * Downloading, as the rest of the app sees it.
 *
 * The manifest is resolved from the item payload rather than from a play session, so
 * downloading never starts a listening session on the server — downloading a book is not
 * listening to it, and the stats should not say otherwise.
 */
@OptIn(UnstableApi::class)
@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: AbsClient,
    private val downloadDao: DownloadDao,
    private val progressDao: ProgressDao,
    private val downloadCache: DownloadCache,
    private val downloadPrefs: DownloadPrefs,
    private val engine: DownloadEngine,
    private val clock: Clock,
) {
    fun observeAll(account: ActiveAccount): Flow<List<DownloadStatus>> =
        downloadDao.observeAll(account.serverId, account.userId).map { rows -> rows.map { it.toStatus() } }

    fun observeForItem(account: ActiveAccount, itemId: String): Flow<List<DownloadStatus>> =
        downloadDao.observeForItem(account.serverId, account.userId, itemId)
            .map { rows -> rows.map { it.toStatus() } }

    /**
     * What the storage readout shows — and deliberately the same number the cap check
     * reads, rather than the sum of the rows.
     *
     * They can disagree: the cache holds bytes for a download whose row was deleted
     * while the service was dead, and the rows are per account while the cache and the
     * cap are per device. A readout that says 600 MB while the check says the phone is
     * full is how a correct refusal ends up reported as a bug. Room's own sum is kept as
     * the change signal, since it is what moves when a download does.
     */
    fun observeBytesUsed(account: ActiveAccount): Flow<Long> =
        downloadDao.observeBytesUsed(account.serverId, account.userId)
            .map { downloadCache.bytesUsed() }
            // Off the main thread: reading the cache blocks until it has finished
            // indexing, which on a phone full of books is not instant.
            .flowOn(Dispatchers.IO)

    suspend fun status(account: ActiveAccount, itemId: String, episodeId: String?): DownloadStatus? =
        downloadDao.get(account.serverId, account.userId, itemId, episodeKeyOf(episodeId))?.toStatus()

    /**
     * The manifest for an item whose bytes are all on disk, or null.
     *
     * This is what makes offline playback possible, and the null case is what stops lugu
     * pretending a half-finished download is playable.
     */
    suspend fun completedManifest(
        account: ActiveAccount,
        itemId: String,
        episodeId: String?,
    ): DownloadManifest? {
        val row = downloadDao.get(account.serverId, account.userId, itemId, episodeKeyOf(episodeId))
            ?: return null
        if (row.state != DownloadState.COMPLETED) return null
        return runCatching { AbsJson.decodeFromString(DownloadManifest.serializer(), row.tracksJson) }.getOrNull()
    }

    suspend fun completedRow(account: ActiveAccount, itemId: String, episodeId: String?): DownloadEntity? =
        downloadDao.get(account.serverId, account.userId, itemId, episodeKeyOf(episodeId))
            ?.takeIf { it.state == DownloadState.COMPLETED }

    /**
     * Queues an item, or one podcast episode, for download.
     *
     * Fails loudly on the storage cap rather than silently filling a phone: the estimate
     * is the item's own reported size, checked before a single byte is fetched.
     */
    suspend fun download(account: ActiveAccount, itemId: String, episodeId: String?): Result<Unit> = runCatching {
        val settings = downloadPrefs.current()
        val dto = client.item(itemId, expanded = true)
        val domain = dto.toDomain()
        val episodeKey = episodeKeyOf(episodeId)

        val manifest = if (episodeId != null) {
            ManifestBuilder.forEpisode(dto, episodeId, account.baseUrl)
        } else {
            ManifestBuilder.forBook(dto, account.baseUrl)
        }
        if (manifest == null || manifest.tracks.isEmpty()) {
            throw DownloadRefusedException(DownloadRefusal.NoAudioFiles)
        }

        val estimatedBytes = estimateBytes(manifest, domain.durationSec)
        val usedBytes = downloadCache.bytesUsed()
        if (usedBytes + estimatedBytes > settings.storageCapBytes) {
            throw DownloadRefusedException(
                DownloadRefusal.OverStorageCap(usedBytes, settings.storageCapBytes, estimatedBytes),
            )
        }

        val durationSec = if (episodeId != null) {
            manifest.tracks.sumOf { it.durationSec }
        } else {
            domain.durationSec
        }

        // The row lands before the requests do, so a screen shows "queued" the instant
        // the button is pressed rather than after the first byte arrives.
        downloadDao.upsert(
            DownloadEntity(
                serverId = account.serverId,
                userId = account.userId,
                libraryItemId = itemId,
                episodeKey = episodeKey,
                title = titleFor(dto, episodeId) ?: domain.title,
                author = domain.authorName,
                mediaType = if (episodeId != null) MediaType.PODCAST.name else domain.mediaType.name,
                state = DownloadState.QUEUED,
                tracksJson = AbsJson.encodeToString(DownloadManifest.serializer(), manifest),
                durationSec = durationSec,
                bytesTotal = estimatedBytes,
                bytesDownloaded = 0,
                percent = 0f,
                requestedAtMs = clock.nowMs(),
                completedAtMs = 0,
                error = null,
            ),
        )

        engine.applyRequirements(settings.wifiOnly, settings.requiresCharging)
        manifest.tracks.forEach { track ->
            DownloadService.sendAddDownload(
                context,
                LuguDownloadService::class.java,
                DownloadRequest.Builder(track.cacheKey, android.net.Uri.parse(track.url))
                    .setCustomCacheKey(track.cacheKey)
                    .setMimeType(track.mimeType)
                    .build(),
                /* foreground = */ false,
            )
        }
    }

    /** Removes the bytes and the row. Progress is untouched — deleting a file is not forgetting a book. */
    suspend fun remove(account: ActiveAccount, itemId: String, episodeId: String?) {
        val episodeKey = episodeKeyOf(episodeId)
        val row = downloadDao.get(account.serverId, account.userId, itemId, episodeKey)
        val manifest = row?.tracksJson?.let {
            runCatching { AbsJson.decodeFromString(DownloadManifest.serializer(), it) }.getOrNull()
        }
        manifest?.tracks?.forEach { track ->
            runCatching {
                DownloadService.sendRemoveDownload(
                    context,
                    LuguDownloadService::class.java,
                    track.cacheKey,
                    /* foreground = */ false,
                )
            }
        }
        downloadDao.delete(account.serverId, account.userId, itemId, episodeKey)
    }

    /**
     * Reclaims downloads for books finished long enough ago.
     *
     * Opt-in and off by default: an app that deletes something the user did not ask it
     * to delete has to be very sure, and "finished" is a flag the server can set from
     * another device.
     */
    suspend fun sweepFinished(account: ActiveAccount): Int {
        val days = downloadPrefs.current().autoDeleteFinishedAfterDays
        if (days <= 0) return 0

        val cutoff = clock.nowMs() - days * MILLIS_PER_DAY
        var removed = 0
        for (row in downloadDao.completed(account.serverId, account.userId)) {
            val progress = progressDao.get(
                account.serverId,
                account.userId,
                row.libraryItemId,
                row.episodeKey,
            ) ?: continue
            if (!progress.isFinished || progress.lastUpdateMs > cutoff) continue
            remove(account, row.libraryItemId, row.episodeKey.ifEmpty { null })
            removed += 1
        }
        return removed
    }

    /**
     * Size to charge against the cap: the files about to be fetched, and only those.
     *
     * This used to prefer the item's own `media.size`, which is a different number than
     * it looks. On a podcast it is the entire feed — one 56 MB episode of an 18 GB show
     * was charged the whole 18 GB, so an empty 8 GB cap refused the first episode
     * anyone tapped. On a book it also counts the ebook and any file flagged `exclude`,
     * neither of which is downloaded. The manifest already knows what is coming down;
     * asking it is both correct and narrower.
     *
     * Per track, the fallback ladder runs size → bitrate (both resolved when the
     * manifest was built) → duration at a typical spoken-word bitrate.
     */
    private fun estimateBytes(manifest: DownloadManifest, durationSec: Double): Long {
        val total = manifest.tracks.sumOf { track ->
            track.sizeBytes?.takeIf { it > 0 }
                ?: (track.durationSec * ASSUMED_BYTES_PER_SECOND).toLong()
        }
        if (total > 0) return total
        return (durationSec * ASSUMED_BYTES_PER_SECOND).toLong().coerceAtLeast(1)
    }

    private fun titleFor(dto: io.github.lightheaded.lugu.core.api.LibraryItemDto, episodeId: String?): String? {
        if (episodeId == null) return null
        return dto.media?.episodes.orEmpty().firstOrNull { it.id == episodeId }?.title
    }

    private fun DownloadEntity.toStatus() = DownloadStatus(
        libraryItemId = libraryItemId,
        episodeId = episodeKey.ifEmpty { null },
        title = title,
        author = author,
        state = state,
        percent = percent,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        error = error,
    )

    private companion object {
        /** ~64 kbps, the usual ballpark for a spoken-word m4b. */
        const val ASSUMED_BYTES_PER_SECOND = 8_000

        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
