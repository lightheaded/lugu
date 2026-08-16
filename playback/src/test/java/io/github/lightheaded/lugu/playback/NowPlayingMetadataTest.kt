package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.Chapter
import org.junit.Test

/**
 * What a car is told, and how often it is told it.
 *
 * Both halves are load-bearing. The line has to be right, and it has to change only when
 * the chapter does — every write reaches every connected controller, and a metadata push
 * twice a second is a notification rebuilt twice a second.
 */
class NowPlayingMetadataTest {

    private val chapters = listOf(
        Chapter(0, 0.0, 600.0, "The Lamp Room"),
        Chapter(1, 600.0, 1_200.0, "Low Water"),
    )

    @Test
    fun `the chapter is named where the book is`() {
        val index = NowPlayingMetadata.chapterIndexAt(chapters, positionSec = 700.0)

        assertThat(index).isEqualTo(1)
        assertThat(NowPlayingMetadata.subtitleFor(chapters, index, author = "James T. R. Corven"))
            .isEqualTo("Low Water")
    }

    @Test
    fun `the answer only changes at a boundary`() {
        val within = (0..500 step 100).map { NowPlayingMetadata.chapterIndexAt(chapters, it.toDouble()) }

        assertThat(within.distinct()).containsExactly(0)
        assertThat(NowPlayingMetadata.chapterIndexAt(chapters, 600.0)).isEqualTo(1)
    }

    /** One chapter is the same as none: naming it says nothing the title has not said. */
    @Test
    fun `a single chapter is not worth a line`() {
        val one = listOf(Chapter(0, 0.0, 3_600.0, "Part 1"))

        assertThat(NowPlayingMetadata.chapterIndexAt(one, 100.0)).isEqualTo(-1)
        assertThat(NowPlayingMetadata.chapterIndexAt(emptyList(), 100.0)).isEqualTo(-1)
    }

    @Test
    fun `an item with no chapters falls back to the author`() {
        assertThat(NowPlayingMetadata.subtitleFor(emptyList(), -1, author = "James T. R. Corven"))
            .isEqualTo("James T. R. Corven")
    }

    /** A blank row in a car reads as something failing to load, so blanks fall through. */
    @Test
    fun `a blank chapter title is not preferred to the author`() {
        val blank = listOf(Chapter(0, 0.0, 600.0, " "), Chapter(1, 600.0, 1_200.0, "Low Water"))

        assertThat(NowPlayingMetadata.subtitleFor(blank, 0, author = "Jefferson Vale")).isEqualTo("Jefferson Vale")
    }

    @Test
    fun `nothing to say is said as nothing rather than as an empty line`() {
        assertThat(NowPlayingMetadata.subtitleFor(emptyList(), -1, author = null)).isNull()
        assertThat(NowPlayingMetadata.subtitleFor(emptyList(), -1, author = "")).isNull()
    }
}
