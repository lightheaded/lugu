package io.github.lightheaded.lugu.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeriesTest {

    @Test
    fun `pulls the sequence out of the server's single string`() {
        assertThat(Series.sequenceOf("The Breakwater #2")).isEqualTo(2.0)
        assertThat(Series.sequenceOf("The Breakwater # 3")).isEqualTo(3.0)
        assertThat(Series.sequenceOf("Discworld #16.5")).isEqualTo(16.5)
    }

    /**
     * The reason this parsing exists at all. Ordered as text, "#10" comes before "#2" —
     * which is how a "next in series" shelf recommends book ten to someone who has just
     * finished book one.
     */
    @Test
    fun `numeric order disagrees with text order, and numeric order wins`() {
        val names = listOf("The Breakwater #10", "The Breakwater #2", "The Breakwater #1")

        assertThat(names.sorted().first()).isEqualTo("The Breakwater #1")
        assertThat(names.sorted()[1]).isEqualTo("The Breakwater #10")
        assertThat(names.sortedBy { Series.sequenceOf(it) }.map { Series.sequenceOf(it) })
            .containsExactly(1.0, 2.0, 10.0)
            .inOrder()
    }

    @Test
    fun `a name with no number parses as a name, not as a failure`() {
        assertThat(Series.titleOf("Sprawl")).isEqualTo("Sprawl")
        assertThat(Series.sequenceOf("Sprawl")).isNull()
    }

    @Test
    fun `an unparseable sequence is null rather than a guess`() {
        assertThat(Series.sequenceOf("Rivers of London #4-5")).isNull()
        assertThat(Series.sequenceOf("Some Series #two")).isNull()
        assertThat(Series.sequenceOf(null)).isNull()
        assertThat(Series.sequenceOf("")).isNull()
    }

    @Test
    fun `title drops the suffix so it can be displayed`() {
        assertThat(Series.titleOf("The Breakwater #2")).isEqualTo("The Breakwater")
        assertThat(Series.titleOf(null)).isNull()
        assertThat(Series.titleOf("   ")).isNull()
    }

    @Test
    fun `describes a volume the way a reader would say it`() {
        assertThat(Series.describe("The Breakwater #2")).isEqualTo("Book 2 of The Breakwater")
        assertThat(Series.describe("Discworld #16.5")).isEqualTo("Book 16.5 of Discworld")
        assertThat(Series.describe("Sprawl")).isEqualTo("Sprawl")
        assertThat(Series.describe(null)).isNull()
    }
}
