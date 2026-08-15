@file:Suppress("PropertyName")

package io.github.lightheaded.lugu.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Wire shapes for the Audiobookshelf API (server v2.36 era).
 *
 * Everything here is tolerant by design: the server ships new fields regularly and a
 * client that throws on an unknown key is a client that breaks on server upgrade day.
 * Unknown keys are ignored globally (see [AbsJson]) and near enough every field is
 * nullable with a default.
 */

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(
    val user: UserDto? = null,
    val userDefaultLibraryId: String? = null,
    val serverSettings: JsonElement? = null,
)

@Serializable
data class UserDto(
    val id: String = "",
    val username: String = "",
    val type: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val mediaProgress: List<MediaProgressDto> = emptyList(),
)

@Serializable
data class RefreshResponse(val user: UserDto? = null)

@Serializable
data class ServerStatusDto(
    /** Literally "audiobookshelf" on a real server — the cheapest identity check there is. */
    val app: String? = null,
    val isInit: Boolean = true,
    val serverVersion: String? = null,
    val language: String? = null,
    val authMethods: List<String> = emptyList(),
)

@Serializable
data class LibrariesResponse(val libraries: List<LibraryDto> = emptyList())

@Serializable
data class LibraryDto(
    val id: String = "",
    val name: String = "",
    val mediaType: String? = null,
    val displayOrder: Int = 0,
    val icon: String? = null,
)

@Serializable
data class LibraryItemsResponse(
    val results: List<LibraryItemDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val page: Int = 0,
)

@Serializable
data class LibraryItemDto(
    val id: String = "",
    val libraryId: String = "",
    val mediaType: String? = null,
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    val isMissing: Boolean = false,
    val isInvalid: Boolean = false,
    val media: MediaDto? = null,
)

@Serializable
data class MediaDto(
    val id: String? = null,
    val coverPath: String? = null,
    val duration: Double? = null,
    /**
     * Every byte the item owns, and never the size of a download.
     *
     * On a podcast this is the entire feed — 18 GB across 327 episodes on the test
     * server — not the episode someone tapped. On a book it includes the ebook and any
     * audio file flagged `exclude`. Sizes that gate a download come per file, from
     * [AudioFileDto.metadata] and [AudioTrackDto.metadata].
     */
    val size: Long? = null,
    val numTracks: Int? = null,
    val numChapters: Int? = null,
    val numEpisodes: Int? = null,
    val metadata: MetadataDto? = null,
    val chapters: List<ChapterDto> = emptyList(),
    val audioFiles: List<AudioFileDto> = emptyList(),
    /**
     * The playable timeline, present on `?expanded=1`.
     *
     * Preferred over [audioFiles] wherever both would do. Verified live on 2.36.0: the
     * server has already computed `startOffset` and `contentUrl` here, and — the part
     * that matters — it has already dropped any audio file flagged `exclude`. Building a
     * book out of [audioFiles] therefore risks including a file the server would never
     * play, at offsets derived by re-doing arithmetic the server already did.
     */
    val tracks: List<AudioTrackDto> = emptyList(),
    val episodes: List<EpisodeDto> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class MetadataDto(
    val title: String? = null,
    val subtitle: String? = null,
    val authorName: String? = null,
    val narratorName: String? = null,
    val seriesName: String? = null,
    val description: String? = null,
    val publishedYear: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val genres: List<String> = emptyList(),
    val explicit: Boolean = false,
    /** Podcast metadata uses `author` where books use `authorName`. */
    val author: String? = null,
)

@Serializable
data class ChapterDto(
    val id: Int = 0,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val title: String = "",
)

/**
 * The on-disk facts about one file. Only [size] is of interest here, and it is the one
 * byte count worth trusting: it is this file, not the item it belongs to.
 */
@Serializable
data class FileMetadataDto(val size: Long? = null)

@Serializable
data class AudioFileDto(
    /** Null on podcast episode files (verified live on 2.36.0), so it coerces to 0. */
    val index: Int = 0,
    val ino: String = "",
    val duration: Double = 0.0,
    val mimeType: String? = null,
    val codec: String? = null,
    val bitRate: Long? = null,
    val metadata: FileMetadataDto? = null,
    /** Files the server will not play. Excluded from `media.tracks` and from downloads. */
    val exclude: Boolean = false,
)

@Serializable
data class EpisodeDto(
    val id: String = "",
    val libraryItemId: String = "",
    val index: Int = 0,
    val season: String? = null,
    val episode: String? = null,
    val title: String = "",
    val subtitle: String? = null,
    val description: String? = null,
    val publishedAt: Long? = null,
    /** This episode's bytes — unlike `media.size`, which is the whole feed. */
    val size: Long? = null,
    val audioFile: AudioFileDto? = null,
    val audioTrack: AudioTrackDto? = null,
)

@Serializable
data class AudioTrackDto(
    val index: Int = 0,
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    val title: String? = null,
    val contentUrl: String = "",
    val mimeType: String = "",
    val codec: String? = null,
    val bitRate: Long? = null,
    val metadata: FileMetadataDto? = null,
)

@Serializable
data class MediaProgressDto(
    val id: String? = null,
    val libraryItemId: String = "",
    val episodeId: String? = null,
    val duration: Double = 0.0,
    val progress: Double = 0.0,
    val currentTime: Double = 0.0,
    val isFinished: Boolean = false,
    val hideFromContinueListening: Boolean = false,
    val lastUpdate: Long = 0,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
)

@Serializable
data class MediaProgressListResponse(val mediaProgress: List<MediaProgressDto> = emptyList())

@Serializable
data class ProgressUpdateRequest(
    val currentTime: Double,
    val duration: Double,
    val progress: Double,
    val isFinished: Boolean,
)

@Serializable
data class PlayRequest(
    val deviceInfo: DeviceInfoDto,
    val supportedMimeTypes: List<String>,
    val mediaPlayer: String = "exo-player",
    val forceDirectPlay: Boolean = false,
    val forceTranscode: Boolean = false,
)

@Serializable
data class DeviceInfoDto(
    val deviceId: String,
    val clientName: String = "lugu",
    val clientVersion: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val sdkVersion: Int? = null,
)

@Serializable
data class PlaybackSessionDto(
    val id: String = "",
    val userId: String? = null,
    val libraryItemId: String = "",
    val episodeId: String? = null,
    val mediaType: String? = null,
    val displayTitle: String? = null,
    val displayAuthor: String? = null,
    val coverPath: String? = null,
    val duration: Double = 0.0,
    val playMethod: Int = 0,
    val startTime: Double = 0.0,
    val currentTime: Double = 0.0,
    val timeListening: Double = 0.0,
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
    val chapters: List<ChapterDto> = emptyList(),
    val audioTracks: List<AudioTrackDto> = emptyList(),
)

@Serializable
data class SessionSyncRequest(
    val currentTime: Double,
    val timeListened: Double,
    val duration: Double,
)

/** Body for `POST /api/session/local` — an offline session replayed to the server. */
@Serializable
data class LocalSessionDto(
    val id: String,
    val libraryItemId: String,
    val episodeId: String? = null,
    val mediaPlayer: String = "exo-player",
    val deviceInfo: DeviceInfoDto,
    val startedAt: Long,
    val updatedAt: Long,
    val startTime: Double,
    val currentTime: Double,
    val timeListening: Double,
    val duration: Double,
    val mediaType: String = "book",
    val displayTitle: String = "",
    val displayAuthor: String = "",
)

/**
 * Body for `POST /api/session/local-all`. The server reads `req.body.sessions` and
 * `req.body.deviceInfo` (PlaybackSessionManager.syncLocalSessionsRequest).
 */
@Serializable
data class LocalSessionBatch(
    val deviceInfo: DeviceInfoDto,
    val sessions: List<LocalSessionDto>,
)

@Serializable
data class PersonalizedShelfDto(
    val id: String = "",
    val label: String = "",
    val labelStringKey: String? = null,
    val type: String = "",
    val entities: List<JsonElement> = emptyList(),
)
