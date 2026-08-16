package io.github.lightheaded.lugu.feature.player

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.model.Chapters
import io.github.lightheaded.lugu.core.sync.SleepSettings
import org.junit.Test

class SleepChaptersTest {

    @Test
    fun `an invented chapter list is not offered as something to count`() {
        // Exactly what the player receives for an item the server has no chapters for.
        val synthetic = Chapters.synthesise(totalDurationSec = 5_400.0, everySec = 600.0)

        assertThat(synthetic).hasSize(9)
        assertThat(hasRealChapters(synthetic)).isFalse()
    }

    @Test
    fun `a chapter list from the book is offered`() {
        val chapters = listOf(
            Chapter(0, 0.0, 1_812.0, "The lamp room"),
            Chapter(1, 1_812.0, 3_004.0, "Spring tide"),
            Chapter(2, 3_004.0, 4_200.0, "The breakwater"),
        )

        assertThat(hasRealChapters(chapters)).isTrue()
    }

    @Test
    fun `a book of even chapters is still a book`() {
        // Half the fingerprint on its own is not enough: a novel really can run to
        // twenty-minute chapters, and refusing to count those would be a worse mistake
        // than the one being avoided.
        val even = (0 until 5).map { index ->
            Chapter(index, index * 1_200.0, (index + 1) * 1_200.0, "Chapter ${index + 1}")
        }

        assertThat(hasRealChapters(even)).isTrue()
    }

    @Test
    fun `parts of unequal length are the book's own parts`() {
        // The other half on its own is not enough either: a book whose divisions are
        // genuinely called Part 1 and Part 2 keeps its count offer, because real parts do
        // not come out to the same length.
        val parts = listOf(
            Chapter(0, 0.0, 3_600.0, "Part 1"),
            Chapter(1, 3_600.0, 8_100.0, "Part 2"),
            Chapter(2, 8_100.0, 10_000.0, "Part 3"),
        )

        assertThat(hasRealChapters(parts)).isTrue()
    }

    @Test
    fun `there is nothing to count in one chapter or none`() {
        assertThat(hasRealChapters(emptyList())).isFalse()
        assertThat(hasRealChapters(listOf(Chapter(0, 0.0, 3_600.0, "The lamp room")))).isFalse()
    }

    @Test
    fun `a timer that outlives a pause says so`() {
        assertThat(sleepPauseExplanation(SleepSettings(survivesPause = true)))
            .isEqualTo("Stays armed if you pause")
        assertThat(sleepPauseExplanation(SleepSettings(survivesPause = false))).isNull()
    }
}
