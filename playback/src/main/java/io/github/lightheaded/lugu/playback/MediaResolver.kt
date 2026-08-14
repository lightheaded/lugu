package io.github.lightheaded.lugu.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.toDomain
import io.github.lightheaded.lugu.core.model.PlayMethod
import io.github.lightheaded.lugu.core.model.PlaybackSessionInfo
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
)

/**
 * Turns "play this item" into a playlist ExoPlayer can take.
 *
 * Order matters here and is the point of the class:
 *  1. pull-before-push, so another device's newer position wins before we touch anything;
 *  2. open the server session, which is what decides direct play versus transcode;
 *  3. open a ledger row, so the listening is recorded even if the network dies next.
 */
@Singleton
class MediaResolver @Inject constructor(
    private val client: AbsClient,
    private val progressRepository: ProgressRepository,
    private val sessionLedgerRepository: SessionLedgerRepository,
    private val libraryRepository: LibraryRepository,
) {
    suspend fun resolve(
        account: ActiveAccount,
        itemId: String,
        episodeId: String?,
    ): Result<ResolvedMedia> = runCatching {
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
        val coverUrl = runCatching { client.coverUrl(itemId, width = 600) }.getOrNull()

        val items = session.tracks.mapIndexed { index, track ->
            MediaItem.Builder()
                .setMediaId(mediaIdOf(itemId, episodeId, index))
                .setUri(Uri.parse(client.absoluteUrl(track.contentUrl)))
                .setMimeType(track.mimeType.takeIf { it.isNotBlank() })
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

    companion object {
        /**
         * What the server compares the source codec against to pick direct play. Direct
         * play means byte-range requests and sample-accurate seeking; HLS transcode
         * means ~6s seek granularity, so the list is deliberately generous.
         */
        val SUPPORTED_MIME_TYPES = listOf(
            "audio/flac",
            "audio/mpeg",
            "audio/mp4",
            "audio/aac",
            "audio/ogg",
            "audio/opus",
            "audio/webm",
            "audio/wav",
            "audio/x-wav",
            "audio/aiff",
            "audio/x-aiff",
            "audio/x-m4a",
            "audio/x-m4b",
        )

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
