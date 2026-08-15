package io.github.lightheaded.lugu.core.download

import io.github.lightheaded.lugu.core.api.LibraryItemDto
import io.github.lightheaded.lugu.core.model.AudioTrack
import kotlinx.serialization.Serializable

/**
 * One downloaded audio file, with everything needed to place it in a book.
 *
 * [startOffsetSec] is why this is stored rather than recomputed: a multi-file book is a
 * single timeline to the listener, and the offsets that make it one normally arrive from
 * the server's play session. Offline there is no play session, so the offsets have to
 * have been written down at download time or the book plays as a pile of unordered
 * files.
 */
@Serializable
data class DownloadTrack(
    val index: Int,
    val startOffsetSec: Double,
    val durationSec: Double,
    /** Absolute URL at the time of download; kept for re-fetching, not for identity. */
    val url: String,
    val mimeType: String,
    /**
     * Identity of the cached bytes, and deliberately not derived from the URL: someone
     * who moves their server to a new hostname should not silently lose every download.
     */
    val cacheKey: String,
)

@Serializable
data class DownloadManifest(val tracks: List<DownloadTrack>)

object DownloadKeys {
    /** Stable across server address changes, unique across items and episodes. */
    fun cacheKey(itemId: String, episodeKey: String, trackIndex: Int): String =
        "$itemId|$episodeKey|$trackIndex"

    /** Reverses [cacheKey]; null when the id did not come from us. */
    fun parse(cacheKey: String): Triple<String, String, Int>? {
        val parts = cacheKey.split('|')
        if (parts.size != 3) return null
        val index = parts[2].toIntOrNull() ?: return null
        return Triple(parts[0], parts[1], index)
    }
}

/** The manifest as the player wants it: domain tracks, ordered along the book. */
fun DownloadManifest.toAudioTracks(): List<AudioTrack> = tracks
    .sortedBy { it.startOffsetSec }
    .map {
        AudioTrack(
            index = it.index,
            startOffsetSec = it.startOffsetSec,
            durationSec = it.durationSec,
            contentUrl = it.url,
            mimeType = it.mimeType,
        )
    }

/**
 * Builds a manifest straight from the item payload, without opening a play session.
 *
 * `POST /api/items/:id/play` is the usual source of playable URLs, but it also starts a
 * listening session on the server — which is wrong for a download, and unavailable when
 * the whole point is preparing to be offline. `GET /api/items/:id?expanded=1` carries
 * the same timeline in `media.tracks`.
 *
 * Preferring `media.tracks` over `media.audioFiles` is not a stylistic choice. Verified
 * against a live 2.36.0 server: `tracks` is the list the server itself would play, with
 * `startOffset` already computed and files flagged `exclude` already dropped. Rebuilding
 * that from `audioFiles` means re-deriving offsets by hand and risking a file the server
 * deliberately left out — which would play as a stretch of the wrong audio partway
 * through a book. `audioFiles` remains the fallback for a payload that has no tracks.
 */
object ManifestBuilder {
    fun forBook(dto: LibraryItemDto, baseUrl: String): DownloadManifest {
        val serverTracks = dto.media?.tracks.orEmpty()
        if (serverTracks.isNotEmpty()) {
            return DownloadManifest(
                serverTracks.sortedBy { it.startOffset }.map { track ->
                    DownloadTrack(
                        index = track.index,
                        startOffsetSec = track.startOffset,
                        durationSec = track.duration,
                        url = absolute(track.contentUrl, baseUrl),
                        mimeType = track.mimeType.ifBlank { DEFAULT_MIME },
                        cacheKey = DownloadKeys.cacheKey(dto.id, "", track.index),
                    )
                },
            )
        }

        val files = dto.media?.audioFiles.orEmpty().filterNot { it.exclude }.sortedBy { it.index }
        var offset = 0.0
        val tracks = files.map { file ->
            val track = DownloadTrack(
                index = file.index,
                startOffsetSec = offset,
                durationSec = file.duration,
                url = "$baseUrl/api/items/${dto.id}/file/${file.ino}",
                mimeType = file.mimeType.orEmpty().ifBlank { DEFAULT_MIME },
                cacheKey = DownloadKeys.cacheKey(dto.id, "", file.index),
            )
            offset += file.duration
            track
        }
        return DownloadManifest(tracks)
    }

    fun forEpisode(dto: LibraryItemDto, episodeId: String, baseUrl: String): DownloadManifest? {
        val episode = dto.media?.episodes.orEmpty().firstOrNull { it.id == episodeId } ?: return null

        // An episode carries its own single-entry track, complete with contentUrl.
        val track = episode.audioTrack
        val file = episode.audioFile
        val url = when {
            track != null && track.contentUrl.isNotBlank() -> absolute(track.contentUrl, baseUrl)
            file != null && file.ino.isNotBlank() -> "$baseUrl/api/items/${dto.id}/file/${file.ino}"
            else -> return null
        }

        return DownloadManifest(
            listOf(
                DownloadTrack(
                    index = 0,
                    startOffsetSec = 0.0,
                    durationSec = track?.duration ?: file?.duration ?: 0.0,
                    url = url,
                    mimeType = track?.mimeType?.ifBlank { null }
                        ?: file?.mimeType?.ifBlank { null }
                        ?: DEFAULT_MIME,
                    cacheKey = DownloadKeys.cacheKey(dto.id, episodeId, 0),
                ),
            ),
        )
    }

    private fun absolute(contentUrl: String, baseUrl: String): String =
        if (contentUrl.startsWith("http")) contentUrl else "$baseUrl$contentUrl"

    private const val DEFAULT_MIME = "audio/mpeg"
}
