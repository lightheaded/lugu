package io.github.lightheaded.lugu.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.toDomain
import io.github.lightheaded.lugu.core.download.DirectPlay
import io.github.lightheaded.lugu.core.download.DownloadKeys
import io.github.lightheaded.lugu.core.download.DownloadRepository
import io.github.lightheaded.lugu.core.download.toAudioTracks
import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.model.Chapters
import io.github.lightheaded.lugu.core.model.PlayMethod
import io.github.lightheaded.lugu.core.model.PlaybackSessionInfo
import io.github.lightheaded.lugu.core.db.episodeKeyOf
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.ProgressJump
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.SessionLedgerRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Everything needed to start playing one item, resolved in one place. */
data class ResolvedMedia(
    val session: PlaybackSessionInfo,
    val mediaItems: List<MediaItem>,
    val startPositionSec: Double,
    val ledgerId: String,
    val jump: ProgressJump?,
    /** True when nothing on this path needed the network. */
    val isOffline: Boolean = false,
)

/**
 * Turns "play this item" into a playlist ExoPlayer can take.
 *
 * Order matters here and is the point of the class:
 *  1. pull-before-push, so another device's newer position wins before we touch anything;
 *  2. if the item is downloaded, resolve entirely from disk and never touch the network;
 *  3. otherwise open the server session, which decides direct play versus transcode;
 *  4. open a ledger row, so the listening is recorded even if the network dies next.
 */
// setCustomCacheKey is still marked unstable in Media3 1.11, and it is the mechanism the
// whole download story rests on: it decouples a cached file from the URL it came from.
@OptIn(UnstableApi::class)
@Singleton
class MediaResolver @Inject constructor(
    private val client: AbsClient,
    private val progressRepository: ProgressRepository,
    private val sessionLedgerRepository: SessionLedgerRepository,
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
) {
    suspend fun resolve(
        account: ActiveAccount,
        itemId: String,
        episodeId: String?,
    ): Result<ResolvedMedia> = runCatching {
        val jump = runCatching { progressRepository.startSession(account, itemId, episodeId) }.getOrNull()

        // A downloaded item never streams and never opens a server session. Doing this
        // before the network call is what makes playback work in airplane mode, and it
        // also means pressing play on a downloaded book is instant rather than waiting
        // on a round trip that can only produce URLs we already have.
        resolveFromDownload(account, itemId, episodeId, jump)?.let { return@runCatching it }

        val dto = client.play(
            itemId = itemId,
            episodeId = episodeId,
            supportedMimeTypes = SUPPORTED_MIME_TYPES,
        )
        val session = dto.toDomain()

        // The server puts the resolved position on the session, but our own resolution
        // ran first and may have adopted a newer one; local Room is the source of truth.
        val localProgress = progressRepository.get(account, itemId, episodeId)
        val startSec = localProgress?.currentTimeSec ?: session.startTimeSec

        val duration = AbsoluteTiming.totalDurationSec(session.tracks, session.durationSec)
        val coverUrl = runCatching { client.coverUrl(itemId, width = 600) }.getOrNull()

        val episodeKey = episodeKeyOf(episodeId)
        val items = session.tracks.mapIndexed { index, track ->
            MediaItem.Builder()
                .setMediaId(mediaIdOf(itemId, episodeId, index))
                .setUri(Uri.parse(client.absoluteUrl(track.contentUrl)))
                .setMimeType(track.mimeType.takeIf { it.isNotBlank() })
                // Keyed the same way downloads are, so a streamed session still reads any
                // bytes already on disk instead of fetching them a second time.
                .setCustomCacheKey(DownloadKeys.cacheKey(itemId, episodeKey, track.index))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(session.title)
                        .setArtist(session.author)
                        .setAlbumTitle(session.title)
                        .setArtworkUri(coverUrl?.let(Uri::parse))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build(),
                )
                .build()
        }

        val ledgerId = sessionLedgerRepository.open(
            account = account,
            libraryItemId = itemId,
            episodeId = episodeId,
            title = session.title,
            author = session.author.orEmpty(),
            mediaType = if (episodeId != null) "podcast" else "book",
            startTimeSec = startSec,
            durationSec = duration,
            serverSessionId = session.sessionId.takeIf { it.isNotBlank() },
        )

        // Chapters came back on the session; cache them so the player screen has them
        // offline next time without another round trip.
        runCatching { libraryRepository.syncItemDetail(account, itemId) }

        ResolvedMedia(
            session = session.copy(durationSec = duration),
            mediaItems = items,
            startPositionSec = startSec,
            ledgerId = ledgerId,
            jump = jump,
        )
    }

    /**
     * Builds a playable session out of a completed download, with no network at all.
     *
     * Everything here comes from Room: the manifest written at download time, the
     * chapters cached with the item, and the position. The ledger row is opened with no
     * server session id, which marks it local — it is replayed to the server through
     * `/api/session/local-all` when there is a connection again, so a week of offline
     * listening still lands in the listening history.
     */
    private suspend fun resolveFromDownload(
        account: ActiveAccount,
        itemId: String,
        episodeId: String?,
        jump: ProgressJump?,
    ): ResolvedMedia? {
        val row = downloadRepository.completedRow(account, itemId, episodeId) ?: return null
        val manifest = downloadRepository.completedManifest(account, itemId, episodeId) ?: return null
        val tracks = manifest.toAudioTracks().takeIf { it.isNotEmpty() } ?: return null

        val duration = AbsoluteTiming.totalDurationSec(tracks, row.durationSec)
        val startSec = progressRepository.get(account, itemId, episodeId)?.currentTimeSec ?: 0.0

        val chapters = runCatching {
            libraryRepository.chapters(account, itemId).map {
                Chapter(it.chapterIndex, it.startSec, it.endSec, it.title)
            }
        }.getOrDefault(emptyList())

        // The cover is a server URL, so it may not load offline. That is a blank square,
        // not a failure to play, and Coil serves it from its own cache when it can.
        val coverUrl = runCatching { client.coverUrl(itemId, width = 600) }.getOrNull()

        val session = PlaybackSessionInfo(
            sessionId = "",
            libraryItemId = itemId,
            episodeId = episodeId,
            title = row.title,
            author = row.author,
            coverPath = null,
            playMethod = PlayMethod.LOCAL,
            durationSec = duration,
            startTimeSec = startSec,
            tracks = tracks,
            chapters = chapters.ifEmpty { Chapters.synthesise(duration) },
        )

        val episodeKey = episodeKeyOf(episodeId)
        val items = tracks.mapIndexed { index, track ->
            MediaItem.Builder()
                .setMediaId(mediaIdOf(itemId, episodeId, index))
                .setUri(Uri.parse(track.contentUrl))
                .setMimeType(track.mimeType.takeIf { it.isNotBlank() })
                // The bytes are found by this key, not by the URL — so a server that has
                // since moved to a new address does not orphan a download.
                .setCustomCacheKey(DownloadKeys.cacheKey(itemId, episodeKey, track.index))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(row.title)
                        .setArtist(row.author)
                        .setAlbumTitle(row.title)
                        .setArtworkUri(coverUrl?.let(Uri::parse))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build(),
                )
                .build()
        }

        val ledgerId = sessionLedgerRepository.open(
            account = account,
            libraryItemId = itemId,
            episodeId = episodeId,
            title = row.title,
            author = row.author.orEmpty(),
            mediaType = if (episodeId != null) "podcast" else "book",
            startTimeSec = startSec,
            durationSec = duration,
            serverSessionId = null,
        )

        return ResolvedMedia(
            session = session,
            mediaItems = items,
            startPositionSec = startSec,
            ledgerId = ledgerId,
            jump = jump,
            isOffline = true,
        )
    }

    companion object {
        /**
         * What the server compares the source codec against to pick direct play. Direct
         * play means byte-range requests and sample-accurate seeking; HLS transcode
         * means ~6s seek granularity, so the list is deliberately generous.
         *
         * It lives in `:core:download` rather than here because the download path has to
         * predict the same answer this call gets, in order to refuse an item the server
         * would only transcode. Two lists that could disagree would produce a download
         * refusing exactly what playback then plays directly — a contradiction nobody
         * could reproduce, because each half would be behaving correctly on its own terms.
         */
        val SUPPORTED_MIME_TYPES = DirectPlay.SUPPORTED_MIME_TYPES

        fun mediaIdOf(itemId: String, episodeId: String?, trackIndex: Int): String =
            "$itemId|${episodeId.orEmpty()}|$trackIndex"

        fun parseMediaId(mediaId: String): Triple<String, String?, Int>? {
            val parts = mediaId.split('|')
            if (parts.size != 3) return null
            return Triple(parts[0], parts[1].takeIf { it.isNotEmpty() }, parts[2].toIntOrNull() ?: 0)
        }
    }
}

/** True when the server gave us a transcode rather than the original file. */
val PlaybackSessionInfo.isTranscoded: Boolean
    get() = playMethod == PlayMethod.TRANSCODE
