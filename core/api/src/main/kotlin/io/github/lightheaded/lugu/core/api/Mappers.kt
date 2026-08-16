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
import io.github.lightheaded.lugu.core.model.Series
import io.github.lightheaded.lugu.core.model.SeriesRef

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

/**
 * The series this item is in, from whichever of the two sources the payload carries.
 *
 * The structured array is preferred whenever it is there, because it is the server's own
 * join table and needs no interpretation: a name, an id and a sequence per membership,
 * however many there are. It only ships on `?expanded=1`.
 *
 * The joined string is the fallback, and it yields at most one membership on purpose. It
 * is the *rendering* of the array, and a book in two series renders as one string with
 * both names in it — from which the only honest recovery is the last-resort parse, which
 * cannot tell "The Breakwater #1, The Tidelands #3" from a series whose name happens to
 * contain a comma and a hash. Where the name of the series is already known from
 * somewhere else, [seriesRefFor] does much better.
 */
fun LibraryItemDto.seriesRefs(): List<SeriesRef> {
    val metadata = media?.metadata ?: return emptyList()
    val structured = metadata.series
        .filter { it.name.isNotBlank() }
        .map { SeriesRef(id = it.id, name = it.name.trim(), sequence = Series.parseSequence(it.sequence)) }
    if (structured.isNotEmpty()) return structured

    val name = Series.titleOf(metadata.seriesName) ?: return emptyList()
    return listOf(SeriesRef(id = null, name = name, sequence = Series.sequenceOf(metadata.seriesName)))
}

/**
 * This item's membership of one series already known by name.
 *
 * What the library-series listing needs. That listing states the membership itself — the
 * book is in the array of a named series, which is a fact and not a parse — and the only
 * thing left to recover is the sequence, which its minified members do not carry. Reading
 * it back out of the joined string anchored on the name it belongs to is unambiguous in
 * every case the blind parse gets wrong, and returns null rather than a number wherever
 * it is not.
 */
fun LibraryItemDto.seriesRefFor(seriesId: String?, seriesName: String): SeriesRef {
    val metadata = media?.metadata
    val structured = metadata?.series?.firstOrNull { it.name.equals(seriesName, ignoreCase = true) }
    return SeriesRef(
        id = seriesId ?: structured?.id,
        name = seriesName,
        sequence = structured?.let { Series.parseSequence(it.sequence) }
            ?: Series.sequenceWithin(metadata?.seriesName, seriesName),
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
