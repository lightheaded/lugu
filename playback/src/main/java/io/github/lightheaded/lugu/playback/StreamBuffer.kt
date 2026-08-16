package io.github.lightheaded.lugu.playback

import io.github.lightheaded.lugu.core.sync.StreamSettings

/**
 * The numbers a `DefaultLoadControl` is built from, worked out where they can be tested.
 *
 * @param minBufferMs how much media the player keeps buffered at all times.
 * @param maxBufferMs where it stops reading ahead. Equal to [minBufferMs], as Media3's own
 *   defaults are: one figure is what the setting means, and a gap between the two only
 *   decides how much the loader oscillates around it.
 * @param targetBufferBytes the hard ceiling on what the buffer may allocate, whatever
 *   [minBufferMs] works out to in bytes.
 */
data class BufferPlan(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val targetBufferBytes: Int,
)

/**
 * How far ahead a streamed book reads, and what stops that becoming an out-of-memory kill.
 *
 * ## Why minutes are affordable here and nowhere else
 *
 * Media3's default is fifty seconds of buffer, which is sized for video. A tunnel drains it
 * and the book stops. Spoken word is the one media case where the obvious fix is also the
 * cheap one: an audiobook is encoded at a low bitrate, so a whole tunnel's worth of it is a
 * couple of megabytes. The same five minutes of 1080p video would be a gigabyte.
 *
 * ## The bitrate this encodes, and what happens when a file breaks it
 *
 * A duration alone is an unbounded promise: "five minutes" of a 320 kbps stereo m4b is five
 * times the memory of the same five minutes of a 64 kbps mono one, and nothing stops
 * somebody's library holding the former. So the plan carries a byte ceiling as well, and the
 * ceiling is what the two are reconciled through.
 *
 * The ceiling is sized at [ASSUMED_BYTES_PER_SECOND] — 32 kB/s, which is 256 kbps. That is
 * deliberately four times the 64 kbps that `DownloadRepository` assumes when it estimates a
 * download's size, and comfortably above every ordinary spoken-word encode: a 128 kbps
 * stereo MP3 audiobook is 16 kB/s, a 64 kbps mono m4b is 8 kB/s. So for anything a listener
 * is likely to own, the minutes setting is what binds and the ceiling is never reached — at
 * 64 kbps even the thirty-minute maximum is 14 MB.
 *
 * Past 256 kbps the ceiling binds instead, and the setting quietly buys fewer minutes rather
 * than more memory. That is the right way round: a lossless or wastefully encoded file is
 * exactly the case where honouring the duration literally would be the out-of-memory kill,
 * and a listener who cannot explain why one book stalls sooner than another is still better
 * off than one whose app is killed mid-chapter.
 *
 * Two further ceilings apply on top, and the smaller always wins:
 *
 *  - a share of the heap this process was actually given ([HEAP_SHARE_DIVISOR]), because a
 *    budget phone and a flagship do not have the same amount to spend and neither the
 *    setting nor a constant in this file knows which one is running;
 *  - an absolute [MAX_TARGET_BYTES], because a large heap is not a reason to spend it. The
 *    buffer is not the only thing in the process, and a large one competes with the bitmap
 *    cache behind the cover art.
 *
 * ## What Media3 does with the ceiling
 *
 * The byte ceiling is authoritative rather than advisory, and that is a property of leaving
 * `prioritizeTimeOverSizeThresholds` at its default of false for streaming. With it false,
 * `DefaultLoadControl.shouldContinueLoading` stops loading once the allocation reaches the
 * target *even while the buffered duration is still short of the minimum*. With it true, the
 * duration would win and the ceiling would be a suggestion. False is what makes the
 * paragraphs above true.
 */
object StreamBuffer {

    /**
     * Works out one plan.
     *
     * @param bufferAheadMinutes the listener's setting, clamped here rather than trusted,
     *   because it arrives from a preferences file that a restore from a backup can put any
     *   number into.
     * @param maxHeapBytes what `Runtime.maxMemory()` reports for this process.
     */
    fun planFor(bufferAheadMinutes: Int, maxHeapBytes: Long): BufferPlan {
        val minutes = bufferAheadMinutes.coerceIn(1, StreamSettings.MAX_BUFFER_MINUTES)
        val durationMs = minutes * MS_PER_MINUTE

        val wantedBytes = minutes.toLong() * SECONDS_PER_MINUTE * ASSUMED_BYTES_PER_SECOND
        val ceilingBytes = minOf(
            MAX_TARGET_BYTES.toLong(),
            (maxHeapBytes / HEAP_SHARE_DIVISOR).coerceAtLeast(0L),
        )
        // The floor is applied last, after both ceilings, because a target buffer of a few
        // hundred kilobytes is not a small buffer but a broken one: Media3 warns below half a
        // second of media, and a megabyte is over two minutes of speech at 64 kbps.
        val targetBytes = minOf(wantedBytes, ceilingBytes).coerceAtLeast(MIN_TARGET_BYTES.toLong())

        return BufferPlan(
            minBufferMs = durationMs,
            maxBufferMs = durationMs,
            targetBufferBytes = targetBytes.toInt(),
        )
    }

    /**
     * The bitrate the byte ceiling encodes: 32 kB/s, or 256 kbps.
     *
     * Four times `DownloadRepository.ASSUMED_BYTES_PER_SECOND`, which is the same repository's
     * estimate of a typical spoken-word m4b. The two are deliberately different numbers doing
     * different jobs — that one guesses what a file *will* weigh so a cap can be checked, this
     * one names the bitrate above which lugu stops taking the minutes setting literally.
     */
    const val ASSUMED_BYTES_PER_SECOND = 32_000

    /** No more than an eighth of whatever heap this process was granted. */
    const val HEAP_SHARE_DIVISOR = 8

    /** A large heap is not a reason to spend it; the cover art cache lives here too. */
    const val MAX_TARGET_BYTES = 48 * 1024 * 1024

    /** Below this a buffer stutters rather than being small. */
    const val MIN_TARGET_BYTES = 1024 * 1024

    /**
     * How much must be buffered before playback starts or resumes after a seek. Media3's own
     * default, and untouched on purpose: reading further ahead must not make pressing play
     * slower, and the whole point of the deep buffer is what happens after the first second.
     */
    const val BUFFER_FOR_PLAYBACK_MS = 1_000

    /**
     * How much must be buffered before playback resumes after the buffer ran dry. Media3's
     * default again. Longer would mean fewer repeated stutters on a bad connection but a
     * longer silence each time, and with minutes of buffer behind it a rebuffer is already
     * the rare case.
     */
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000

    private const val MS_PER_MINUTE = 60_000
    private const val SECONDS_PER_MINUTE = 60
}
