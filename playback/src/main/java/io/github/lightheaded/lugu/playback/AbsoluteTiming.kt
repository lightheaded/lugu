package io.github.lightheaded.lugu.playback

import io.github.lightheaded.lugu.core.model.AudioTrack

/**
 * Maps between "position in the book" and "position in track N".
 *
 * A book is often many files. The server, the progress API and the user all think in
 * whole-book seconds; ExoPlayer thinks in (window index, position). Every conversion
 * goes through here so the two never drift — drift is what makes a resumed book start
 * in the wrong chapter.
 */
object AbsoluteTiming {

    data class TrackPosition(val trackIndex: Int, val positionMs: Long)

    /** Whole-book seconds → the track and offset ExoPlayer should seek to. */
    fun toTrack(tracks: List<AudioTrack>, absoluteSec: Double): TrackPosition {
        if (tracks.isEmpty()) return TrackPosition(0, 0)
        val clamped = absoluteSec.coerceAtLeast(0.0)

        val index = tracks.indexOfLast { it.startOffsetSec <= clamped + EPSILON }
            .takeIf { it >= 0 } ?: 0
        val track = tracks[index]
        val within = (clamped - track.startOffsetSec).coerceIn(0.0, maxOf(track.durationSec, 0.0))
        return TrackPosition(index, (within * 1000).toLong())
    }

    /** The reverse: (track, offset) → whole-book seconds. */
    fun toAbsoluteSec(tracks: List<AudioTrack>, trackIndex: Int, positionMs: Long): Double {
        if (tracks.isEmpty()) return positionMs / 1000.0
        val track = tracks.getOrNull(trackIndex) ?: return positionMs / 1000.0
        return track.startOffsetSec + positionMs / 1000.0
    }

    /** Total book duration; falls back to summing tracks when the server reports none. */
    fun totalDurationSec(tracks: List<AudioTrack>, reportedSec: Double): Double =
        if (reportedSec > 0) reportedSec else tracks.sumOf { it.durationSec }

    private const val EPSILON = 0.001
}
