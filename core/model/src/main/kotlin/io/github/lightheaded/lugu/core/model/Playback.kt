package io.github.lightheaded.lugu.core.model

/** How the server decided to serve the audio. Values match the server's PlayMethod enum. */
enum class PlayMethod(val wire: Int) {
    DIRECT_PLAY(0),
    DIRECT_STREAM(1),
    TRANSCODE(2),
    LOCAL(3),
    ;

    companion object {
        fun fromWire(value: Int?): PlayMethod = entries.firstOrNull { it.wire == value } ?: DIRECT_PLAY
    }
}

/**
 * A playback session opened on the server, resolved into everything the player needs.
 *
 * Chapters here are already normalised (sorted by start, gaps closed) — see
 * [Chapters.normalise].
 */
data class PlaybackSessionInfo(
    val sessionId: String,
    val libraryItemId: String,
    val episodeId: String?,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val playMethod: PlayMethod,
    val durationSec: Double,
    val startTimeSec: Double,
    val tracks: List<AudioTrack>,
    val chapters: List<Chapter>,
)

object Chapters {
    /**
     * Server chapter arrays come back sorted by internal id, not by timestamp
     * (server #3007, #4603). Sort by start, drop degenerate entries, and repair
     * end times so chapter maths downstream can trust the list.
     */
    fun normalise(raw: List<Chapter>, totalDurationSec: Double): List<Chapter> {
        if (raw.isEmpty()) return emptyList()
        val sorted = raw
            .filter { it.startSec.isFinite() && it.startSec >= 0.0 }
            .sortedBy { it.startSec }
            .distinctBy { it.startSec }

        return sorted.mapIndexed { index, chapter ->
            val nextStart = sorted.getOrNull(index + 1)?.startSec ?: totalDurationSec
            val end = when {
                chapter.endSec > chapter.startSec && chapter.endSec <= nextStart + 1.0 -> chapter.endSec
                else -> nextStart
            }
            chapter.copy(
                id = index,
                endSec = end,
                title = chapter.title.ifBlank { "Chapter ${index + 1}" },
            )
        }
    }

    /**
     * Fallback when an item has no usable chapters at all: synthesise even chapters.
     * The server has an open request to do this itself (#4225); lugu does it client-side.
     */
    fun synthesise(totalDurationSec: Double, everySec: Double = 600.0): List<Chapter> {
        if (totalDurationSec <= 0.0 || everySec <= 0.0) return emptyList()
        val count = kotlin.math.ceil(totalDurationSec / everySec).toInt().coerceAtLeast(1)
        if (count <= 1) return emptyList()
        return (0 until count).map { index ->
            val start = index * everySec
            Chapter(
                id = index,
                startSec = start,
                endSec = minOf(start + everySec, totalDurationSec),
                title = "Part ${index + 1}",
            )
        }
    }

    /** The chapter containing [positionSec], or null when there are no chapters. */
    fun at(chapters: List<Chapter>, positionSec: Double): Chapter? =
        chapters.lastOrNull { positionSec >= it.startSec }
}
