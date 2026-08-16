package io.github.lightheaded.lugu.playback

import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.model.Chapters

/**
 * What a car is told about the thing that is playing.
 *
 * A projection host draws its player screen from the platform session's metadata and
 * nothing else, so anything a driver is to know has to be said there. The line under the
 * title is the only room there is, and the chapter is the only "where am I" a driver can
 * use: the position is a number they cannot read at speed, and the book's own title is
 * already above it (upstream app#489).
 *
 * ## Why the chapters are not the session queue
 *
 * Upstream app#1673 asks for the chapters as a playlist a head unit can jump between, and
 * the answer here is no. It is worth writing down why, because the request is reasonable
 * and the reason it is refused is not obvious.
 *
 * The session queue is not a list lugu is free to compose. Media3 builds the platform
 * session's queue from the *player's timeline* — one entry per media item — and turns a
 * head unit's "skip to queue item" back into `seekToMediaItem(index)` on that same
 * timeline. A book already occupies that timeline: a single-file book is one item forty
 * hours long, and a multi-file book is one item per file. [AbsoluteTiming] exists to map
 * between whole-book seconds and that timeline, and every position lugu records, syncs and
 * resumes from passes through it.
 *
 * Putting chapters in the queue therefore means making the timeline a list of chapters,
 * which means the player's item index, its per-item duration and its per-item position all
 * stop meaning what the rest of lugu believes they mean. That is the same lie
 * [LuguNotificationProvider] refuses for the progress bar, and it is worse here, because a
 * queue is not a display: the seeks come back in. There is no per-controller queue and no
 * hook that runs only on the way to a car, so the lie could not be confined to the car
 * either.
 *
 * What a driver actually wants from that list — moving a chapter at a time — is reachable
 * without any of it: previous and next chapter are session commands offered to a car
 * whenever one is connected, and the stock transport is remapped so it cannot seek a book
 * to zero (see [ChapterAwarePlayer]). The chapter they are in is named here. What is lost
 * is jumping to an arbitrary chapter from the car's own list, which is a thing to do while
 * stopped, and the phone does it.
 */
object NowPlayingMetadata {

    /**
     * The chapter to name at [positionSec], or -1 when there is nothing worth naming.
     *
     * One chapter is the same as none: "Part 1" under the title of a book with one part
     * costs a line and says nothing. Synthesised chapters do count — on a chapterless book
     * they are the only landmark there is.
     */
    fun chapterIndexAt(chapters: List<Chapter>, positionSec: Double): Int =
        if (chapters.size <= 1) -1 else Chapters.indexAt(chapters, positionSec)

    /**
     * The line under the title: the chapter where there is one, the author otherwise.
     *
     * Never blank by preference. A car that is given nothing draws an empty row of the same
     * height, which reads as something failing to load rather than as nothing to say.
     */
    fun subtitleFor(chapters: List<Chapter>, chapterIndex: Int, author: String?): String? =
        chapters.getOrNull(chapterIndex)?.title?.takeIf { it.isNotBlank() }
            ?: author?.takeIf { it.isNotBlank() }
}
