package io.github.lightheaded.lugu.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.qualifiers.ApplicationContext
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
import io.github.lightheaded.lugu.core.sync.PushHold
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
    /** Set when the item plays before the server was asked about its position. */
    val positionCheck: PositionCheck? = null,
)

/**
 * A start that played before the server was asked where the listener got to.
 *
 * The service asks once the item is loaded, and moves the player if another device is
 * ahead. Until then the item's pushes are held, so this device cannot overwrite the other
 * one's position. A class rather than a data class: each start is its own check, even
 * when the same book starts twice.
 */
class PositionCheck(val hold: PushHold)

/**
 * Turns "play this item" into a playlist ExoPlayer can take.
 *
 * Order matters here and is the point of the class:
 *  1. if the item is downloaded, resolve entirely from disk and never touch the network.
 *     Play starts at once from Room. The pull happens after the start, with the item's
 *     pushes held until it is done — see [PositionCheck];
 *  2. otherwise pull-before-push, so another device's newer position wins before we
 *     touch anything;
 *  3. then open the server session, which decides direct play versus transcode;
 *  4. open a ledger row, so the listening is recorded even if the network dies next.
 */
// setCustomCacheKey is still marked unstable in Media3 1.11, and it is the mechanism the
// whole download story rests on: it decouples a cached file from the URL it came from.
@OptIn(UnstableApi::class)
@Singleton
class MediaResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
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
        // A downloaded item never streams, never opens a server session, and does not
        // wait for the progress pull either. The pull used to come first, so a book that
        // was all on the phone waited for the server before a sound came out: seconds on a
        // slow network, ten on one that is connected but not working. The app keeps Room
        // up to date before a play press (RealtimeSync.catchUp), and the service asks the
        // server again just after the start, with this item's pushes held until it has.
        resolveFromDownload(account, itemId, episodeId)?.let { return@runCatching it }

        val jump = runCatching { progressRepository.startSession(account, itemId, episodeId) }.getOrNull()

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
        // Through lugu's own provider rather than as a server URL: the metadata on a session
        // is read by other processes — the car's now-playing screen most of all — and none of
        // them can authenticate to the server. See CoverProvider.
        val coverUri = CoverProvider.uri(context, itemId, width = COVER_WIDTH)

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
                        .setArtworkUri(coverUri)
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

        // Covers are not part of a download, so an offline book still needs the server for
        // its picture. That is a blank square, never a failure to play, and the provider
        // serves one it has already fetched when it has one.
        val coverUri = CoverProvider.uri(context, itemId, width = COVER_WIDTH)

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
                        .setArtworkUri(coverUri)
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
            jump = null,
            isOffline = true,
            // Taken last, after everything that can fail: a hold with no start to
            // release it would delay this item's pushes for nothing.
            positionCheck = PositionCheck(progressRepository.holdPushes(account, itemId, episodeId)),
        )
    }

    companion object {
        /**
         * Wide enough for a car's now-playing screen, which is the largest place this is
         * shown; the in-app player asks the server directly and picks its own size.
         */
        private const val COVER_WIDTH = 600

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
