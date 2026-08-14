package io.github.lightheaded.lugu.core.model

/**
 * A library item as lugu renders it. Deliberately flat: the UI reads this from Room,
 * never from the network. Fields the UI does not use yet live in the raw payload column
 * in `:core:db` rather than here.
 */
data class LibraryItem(
    val id: String,
    val libraryId: String,
    val mediaType: MediaType,
    val title: String,
    val subtitle: String? = null,
    val authorName: String? = null,
    val narratorName: String? = null,
    val seriesName: String? = null,
    val description: String? = null,
    val durationSec: Double = 0.0,
    val sizeBytes: Long = 0L,
    val numEpisodes: Int = 0,
    val addedAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val coverPath: String? = null,
)

/** A podcast episode. Episodes are addressed as (libraryItemId, episodeId) everywhere. */
data class PodcastEpisode(
    val id: String,
    val libraryItemId: String,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val episodeNumber: String? = null,
    val season: String? = null,
    val publishedAtMs: Long = 0L,
    val durationSec: Double = 0.0,
    val index: Int = 0,
)

/**
 * A chapter. lugu holds the invariant that a chapter list is sorted by [startSec] —
 * the server sorts by internal id and gets this wrong (server #3007, #4603).
 */
data class Chapter(
    val id: Int,
    val startSec: Double,
    val endSec: Double,
    val title: String,
)

/** One playable audio file of an item, with its offset into the whole book. */
data class AudioTrack(
    val index: Int,
    val startOffsetSec: Double,
    val durationSec: Double,
    val contentUrl: String,
    val mimeType: String,
    val title: String? = null,
)
