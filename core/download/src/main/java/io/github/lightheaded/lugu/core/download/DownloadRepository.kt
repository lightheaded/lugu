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
import kotlinx.coroutines.withContext
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

    /**
     * The item's audio is in a format this device cannot decode, so the server would only
     * ever serve it as a transcode — and a transcode is not a thing that can be kept.
     *
     * ## Why HLS downloading is not implemented
     *
     * Media3 can download HLS: [androidx.media3.exoplayer.offline.DownloadHelper] walks a
     * playlist and fetches its segments, and on paper that closes this gap. It is not
     * done here, and the reasons are worth writing down once so the question does not
     * have to be reopened from nothing.
     *
     * The playlist is not addressable. Audiobookshelf mints a transcode against a play
     * session, and the session expires and takes its segment URLs with it. Every other
     * download in lugu is keyed by item and track index precisely so that the bytes
     * survive a changed server address; an HLS download would instead be pinned to a
     * session id that is dead within the hour, which is the shape of the orphaned-download
     * bug the whole cache-key scheme exists to avoid.
     *
     * It cannot be verified. Every other download is checked by byte count against a size
     * the server reported before the first request. A transcode has no size until it has
     * been produced, so a truncated one is indistinguishable from a complete one — and a
     * download that cannot be told apart from a broken download is worse than no download,
     * because the failure surfaces in a tunnel rather than on the Downloads screen.
     *
     * It is the wrong copy. The result would be a re-encode, at a bitrate the server
     * chose, of a file the server already holds intact. Storing a worse copy of something
     * that exists is an odd thing for an offline mode to spend a phone's storage on, and
     * seeking within it is limited to the six-second segment grid.
     *
     * The direction that actually helps is the other one: making fewer items transcode in
     * the first place, which is what [DirectPlay.SUPPORTED_MIME_TYPES] is for. What is
     * left after that is a genuinely undecodable file, and the honest answer to it is to
     * say so rather than to keep a copy that cannot be played.
     */
    data class TranscodeOnly(val mimeTypes: List<String>) : DownloadRefusal
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

            // Named formats rather than "unsupported", for the same reason the cap
            // refusal prints its numbers: the listener can go and look at the file. It
            // does not say the download failed, because nothing was attempted and nothing
            // went wrong — the item is simply not one that can be kept.
            is DownloadRefusal.TranscodeOnly ->
                "The server would send this as a transcoded stream rather than as a " +
                    "file, because its audio is ${andList(refusal.mimeTypes.map(DirectPlay::describe))} " +
                    "and this device has no decoder for it. A transcode is made for one " +
                    "play session and expires with it, so there is nothing stable to " +
                    "keep. Converting the file on the server to a format this device " +
                    "decodes — MP3, M4B, FLAC, Opus or WAV — is what would make it " +
                    "downloadable."
        }

        /** "a", "a and b", "a, b and c" — a sentence, not a debug dump. */
        fun andList(parts: List<String>): String = when (parts.size) {
            0, 1 -> parts.joinToString()
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
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
    private val coverStore: CoverStore,
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
     *
     * Bytes behind a pending-delete row are subtracted, for the same reason the cap
     * check in [download] subtracts them: the delete is the outcome a tap already
     * asked for, so the readout should say so at once rather than waiting for the
     * undo window to close.
     */
    fun observeBytesUsed(account: ActiveAccount): Flow<Long> =
        downloadDao.observeBytesUsed(account.serverId, account.userId)
            .map { StorageCap.chargeableBytes(downloadCache.bytesUsed(), downloadDao.pendingDeleteBytes()) }
            // Off the main thread: reading the cache blocks until it has finished
            // indexing, which on a phone full of books is not instant.
            .flowOn(Dispatchers.IO)

    /**
     * Bytes held by audio that was streamed rather than downloaded.
     *
     * Deliberately its own figure, never added to [observeBytesUsed]. The two are different
     * kinds of thing: a download was asked for, counts against the cap and is never evicted,
     * while retained streamed audio is disposable and is dropped oldest-first the moment it
     * reaches its own bound. Summing them would put a number next to the cap that the cap
     * does not govern, which is exactly the two-numbers-for-one-quantity mistake that once
     * made a correct storage refusal look like a lie.
     */
    suspend fun retainedStreamBytes(): Long =
        withContext(Dispatchers.IO) { downloadCache.retainedStreamBytes() }

    suspend fun status(account: ActiveAccount, itemId: String, episodeId: String?): DownloadStatus? =
        downloadDao.get(account.serverId, account.userId, itemId, episodeKeyOf(episodeId))
            ?.takeIf { it.state != DownloadState.PENDING_DELETE }
            ?.toStatus()

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

        // Before the cap, deliberately. The file endpoint would hand over the original
        // bytes of a transcode-only item quite happily, and they would sit there charged
        // against the cap until someone pressed play in a tunnel and found the item
        // silent. Refusing on format first also keeps the cap message honest: "there is
        // no room for this" would be the wrong reason.
        val transcodeOnly = DirectPlay.transcodeOnlyMimeTypes(manifest)
        if (transcodeOnly.isNotEmpty()) {
            throw DownloadRefusedException(DownloadRefusal.TranscodeOnly(transcodeOnly))
        }

        val estimatedBytes = estimateBytes(manifest, domain.durationSec)
        // Pending-delete bytes are not charged: those files are on their way out, and a
        // download waiting on the same space a delete just freed must not be refused for
        // bytes that are, in effect, already gone.
        val usedBytes = StorageCap.chargeableBytes(downloadCache.bytesUsed(), downloadDao.pendingDeleteBytes())
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

        // The picture, kept for as long as the audio is. Last, and unable to fail the
        // download: a cover is how a book is recognised offline, but a book with no cover
        // still plays, and an item whose server simply has no artwork is an ordinary state
        // rather than an error. Doing it here rather than in the engine is deliberate — this
        // is the one place that already holds the item id and knows a download was accepted.
        runCatching { coverStore.fetch(itemId) }
    }

    /**
     * Removes the bytes and the row at once, with no way back. Progress is untouched —
     * deleting a file is not forgetting a book.
     *
     * Screens that offer no undo of their own call this directly, which is correct: a
     * download still running has no finished file to defer in the first place, so
     * cancelling one goes through here too, immediately. A completed download's own
     * delete control instead goes through [deferDelete], and this is only reached for it
     * once [finalizeDeferred] decides the undo window has closed with no undo taken.
     */
    suspend fun remove(account: ActiveAccount, itemId: String, episodeId: String?) {
        val episodeKey = episodeKeyOf(episodeId)
        val row = downloadDao.get(account.serverId, account.userId, itemId, episodeKey) ?: return
        removeRow(row)
    }

    /**
     * Marks a completed download pending-delete, in place of removing it.
     *
     * Nothing leaves disk here — the row keeps its bytes and its manifest, only its
     * `state` changes. That is what lets [restoreDeferred] put it back exactly and at
     * once if the undo window is used, and it is why the item stops appearing as
     * downloaded on every screen the instant this returns: [observeAll] and
     * [observeForItem] both exclude a pending-delete row.
     *
     * Returns the row as it stood before the mark, which is the caller's only copy of it
     * — the one thing [restoreDeferred] needs. Returns null when there was nothing
     * complete to defer, which the caller should not see: an active download has no
     * pending-delete state to enter and cancels through [remove] instead.
     */
    suspend fun deferDelete(account: ActiveAccount, itemId: String, episodeId: String?): DownloadEntity? {
        val episodeKey = episodeKeyOf(episodeId)
        val row = downloadDao.get(account.serverId, account.userId, itemId, episodeKey) ?: return null
        if (row.state != DownloadState.COMPLETED) return null
        downloadDao.updateState(
            serverId = account.serverId,
            userId = account.userId,
            itemId = itemId,
            episodeKey = episodeKey,
            state = DownloadState.PENDING_DELETE,
            bytesDownloaded = row.bytesDownloaded,
            bytesTotal = row.bytesTotal,
            percent = row.percent,
            completedAtMs = row.completedAtMs,
            error = null,
        )
        return row
    }

    /**
     * Undoes [deferDelete]: puts [snapshot] back exactly, because nothing about it was
     * ever touched but its `state`.
     *
     * Guarded against the row having moved on: if it is no longer pending-delete — a
     * fresh download landed on the same item and episode while the undo was on offer,
     * or the window already closed and [finalizeDeferred] beat this call to it — this
     * does nothing, rather than resurrecting a row a newer one has replaced.
     */
    suspend fun restoreDeferred(account: ActiveAccount, snapshot: DownloadEntity) {
        val current = downloadDao.get(account.serverId, account.userId, snapshot.libraryItemId, snapshot.episodeKey)
        if (current?.state != DownloadState.PENDING_DELETE) return
        downloadDao.upsert(snapshot)
    }

    /**
     * Finishes what [deferDelete] deferred, once the undo window has closed with no undo.
     *
     * Guarded the same way [restoreDeferred] is, and for the same reason: only a row
     * still pending-delete is this call's to remove. A row a fresh download has since
     * reused belongs to that download, not to the one this window was offering to undo.
     */
    suspend fun finalizeDeferred(account: ActiveAccount, snapshot: DownloadEntity) {
        val current = downloadDao.get(account.serverId, account.userId, snapshot.libraryItemId, snapshot.episodeKey)
        if (current?.state != DownloadState.PENDING_DELETE) return
        removeRow(current)
    }

    /**
     * Finalises every pending-delete row still on the device, and returns how many.
     *
     * Meant for startup, not for the undo path -- [finalizeDeferred] is what a live undo
     * window uses. A row still pending-delete when the app starts has already lost its undo
     * by definition: the snackbar that offered it cannot have survived the process dying or
     * the screen that held it being left, so nothing here can still be waiting on a tap.
     *
     * Left stranded, such a row would stay invisible in three places at once -- excluded
     * from [observeAll] and [observeForItem] so no screen offers it back, excluded from the
     * engine's `unfinished` so the download engine never revisits it, and subtracted from
     * the storage readout so its bytes read as free when they are still on disk. This is the
     * one pass that actually removes them.
     *
     * Takes no [ActiveAccount]: a stranded row can belong to any account on the device, and
     * [removeRow] needs nothing from one that the row itself does not already carry.
     */
    suspend fun sweepPendingDeletes(): Int {
        var swept = 0
        for (row in downloadDao.pendingDelete()) {
            // Re-read before the delete, and guard exactly as restoreDeferred and
            // finalizeDeferred do. The query above is a snapshot, and a fresh download can
            // claim the same key between that snapshot and this iteration -- a cold start
            // is precisely when a listener reopens an item and taps download again. Without
            // this check the sweep matches by key and removes a row that belongs to the new
            // download, and cancels the Media3 requests it just issued.
            val current = downloadDao.get(row.serverId, row.userId, row.libraryItemId, row.episodeKey)
            if (current?.state != DownloadState.PENDING_DELETE) continue
            removeRow(current)
            swept += 1
        }
        return swept
    }

    /** The actual deletion: the engine's bytes for every track, then the row, then the cover. */
    private suspend fun removeRow(row: DownloadEntity) {
        val manifest = runCatching {
            AbsJson.decodeFromString(DownloadManifest.serializer(), row.tracksJson)
        }.getOrNull()
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
        downloadDao.delete(row.serverId, row.userId, row.libraryItemId, row.episodeKey)
        if (downloadDao.countForItem(row.libraryItemId) == 0) coverStore.remove(row.libraryItemId)
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
     * Drops cover files whose downloads are gone, and returns how many.
     *
     * Run at startup rather than at every deletion, because the deletions worth catching are
     * the ones that do not go through [remove] at all — a sign-out, or anything cascading from
     * the server row. Costs a directory listing and one query.
     */
    suspend fun sweepCovers(): Int = coverStore.retainOnly(downloadDao.itemsWithDownloads().toSet())

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
