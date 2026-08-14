package io.github.lightheaded.lugu.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChaptersTest {

    @Test
    fun `chapters arriving out of order are sorted by start`() {
        val raw = listOf(
            Chapter(id = 0, startSec = 600.0, endSec = 1_200.0, title = "Two"),
            Chapter(id = 1, startSec = 0.0, endSec = 600.0, title = "One"),
            Chapter(id = 2, startSec = 1_200.0, endSec = 1_800.0, title = "Three"),
        )

        val normalised = Chapters.normalise(raw, totalDurationSec = 1_800.0)

        assertThat(normalised.map { it.title }).containsExactly("One", "Two", "Three").inOrder()
        assertThat(normalised.map { it.id }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `broken end times are repaired from the next chapter start`() {
        val raw = listOf(
            Chapter(id = 0, startSec = 0.0, endSec = 0.0, title = "One"),
            Chapter(id = 1, startSec = 600.0, endSec = 0.0, title = "Two"),
        )

        val normalised = Chapters.normalise(raw, totalDurationSec = 1_000.0)

        assertThat(normalised[0].endSec).isEqualTo(600.0)
        assertThat(normalised[1].endSec).isEqualTo(1_000.0)
    }

    @Test
    fun `blank titles get a positional fallback`() {
        val raw = listOf(Chapter(id = 0, startSec = 0.0, endSec = 10.0, title = "  "))

        assertThat(Chapters.normalise(raw, 10.0).single().title).isEqualTo("Chapter 1")
    }

    @Test
    fun `duplicate and negative starts are dropped`() {
        val raw = listOf(
            Chapter(id = 0, startSec = -5.0, endSec = 10.0, title = "Bad"),
            Chapter(id = 1, startSec = 0.0, endSec = 10.0, title = "One"),
            Chapter(id = 2, startSec = 0.0, endSec = 10.0, title = "Duplicate"),
        )

        assertThat(Chapters.normalise(raw, 20.0)).hasSize(1)
    }

    @Test
    fun `synthesised chapters cover the whole duration`() {
        val synthetic = Chapters.synthesise(totalDurationSec = 3_000.0, everySec = 600.0)

        assertThat(synthetic).hasSize(5)
        assertThat(synthetic.first().startSec).isEqualTo(0.0)
        assertThat(synthetic.last().endSec).isEqualTo(3_000.0)
    }

    @Test
    fun `short items are not chopped into synthetic chapters`() {
        assertThat(Chapters.synthesise(totalDurationSec = 300.0, everySec = 600.0)).isEmpty()
    }

    @Test
    fun `chapter lookup returns the containing chapter`() {
        val chapters = Chapters.synthesise(3_000.0, 600.0)

        assertThat(Chapters.at(chapters, 0.0)?.title).isEqualTo("Part 1")
        assertThat(Chapters.at(chapters, 599.9)?.title).isEqualTo("Part 1")
        assertThat(Chapters.at(chapters, 600.0)?.title).isEqualTo("Part 2")
        assertThat(Chapters.at(chapters, 2_999.0)?.title).isEqualTo("Part 5")
    }
}
