package io.github.lightheaded.lugu.core.api

import io.github.lightheaded.lugu.core.model.AudioTrack
import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.model.Chapters
import io.github.lightheaded.lugu.core.model.Library
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.model.PlayMethod
import io.github.lightheaded.lugu.core.model.PlaybackSessionInfo
import io.github.lightheaded.lugu.core.model.PodcastEpisode

fun LibraryDto.toDomain(): Library = Library(
    id = id,
    name = name,
    mediaType = MediaType.fromWire(mediaType),
    displayOrder = displayOrder,
)

fun LibraryItemDto.toDomain(): LibraryItem {
    val metadata = media?.metadata
    return LibraryItem(
        id = id,
        libraryId = libraryId,
        mediaType = MediaType.fromWire(mediaType),
        title = metadata?.title?.takeIf { it.isNotBlank() } ?: "Untitled",
        subtitle = metadata?.subtitle,
        // Podcasts carry the creator in `author`, books in `authorName`.
        authorName = metadata?.authorName ?: metadata?.author,
        narratorName = metadata?.narratorName,
        seriesName = metadata?.seriesName,
        description = metadata?.description,
        durationSec = media?.duration ?: 0.0,
        sizeBytes = media?.size ?: 0L,
        numEpisodes = media?.numEpisodes ?: 0,
        addedAtMs = addedAt,
        updatedAtMs = updatedAt,
        coverPath = media?.coverPath,
    )
}

fun EpisodeDto.toDomain(fallbackItemId: String): PodcastEpisode = PodcastEpisode(
    id = id,
    libraryItemId = libraryItemId.ifBlank { fallbackItemId },
    title = title.ifBlank { "Untitled episode" },
    subtitle = subtitle,
    description = description,
    episodeNumber = episode,
    season = season,
    publishedAtMs = publishedAt ?: 0L,
    durationSec = audioTrack?.duration ?: audioFile?.duration ?: 0.0,
    index = index,
)

fun ChapterDto.toDomain(): Chapter = Chapter(id = id, startSec = start, endSec = end, title = title)

fun AudioTrackDto.toDomain(): AudioTrack = AudioTrack(
    index = index,
    startOffsetSec = startOffset,
    durationSec = duration,
    contentUrl = contentUrl,
    mimeType = mimeType,
    title = title,
)

fun MediaProgressDto.toDomain(): MediaProgress = MediaProgress(
    libraryItemId = libraryItemId,
    episodeId = episodeId,
    currentTimeSec = currentTime,
    durationSec = duration,
    progress = progress,
    isFinished = isFinished,
    lastUpdateMs = lastUpdate,
    startedAtMs = startedAt,
)

/**
 * Turns a server playback session into something the player can trust: chapters are
 * normalised (the server sorts them by internal id, not by start — #3007), and an
 * item with no usable chapters gets synthetic ones rather than none.
 */
fun PlaybackSessionDto.toDomain(): PlaybackSessionInfo {
    val normalised = Chapters.normalise(chapters.map { it.toDomain() }, duration)
    return PlaybackSessionInfo(
        sessionId = id,
        libraryItemId = libraryItemId,
        episodeId = episodeId,
        title = displayTitle.orEmpty().ifBlank { "Untitled" },
        author = displayAuthor,
        coverPath = coverPath,
        playMethod = PlayMethod.fromWire(playMethod),
        durationSec = duration,
        startTimeSec = currentTime,
        tracks = audioTracks.map { it.toDomain() }.sortedBy { it.startOffsetSec },
        chapters = normalised.ifEmpty { Chapters.synthesise(duration) },
    )
}
