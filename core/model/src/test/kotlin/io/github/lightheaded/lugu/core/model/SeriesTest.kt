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

    /**
     * The failure the structured membership exists to fix, stated as a test so it stays
     * fixed. A book in two series renders as one string with both in it, and the plain
     * parse reads the *last* number in it — which belongs to the other series — and calls
     * everything before it the name.
     */
    @Test
    fun `the plain parse cannot read a book that is in two series`() {
        val joined = "The Breakwater #1, The Tidelands #3"

        assertThat(Series.sequenceOf(joined)).isEqualTo(3.0)
        assertThat(Series.titleOf(joined)).isEqualTo("The Breakwater #1, The Tidelands")
    }

    @Test
    fun `knowing the series name resolves what the plain parse cannot`() {
        val joined = "The Breakwater #1, The Tidelands #3"

        assertThat(Series.sequenceWithin(joined, "The Breakwater")).isEqualTo(1.0)
        assertThat(Series.sequenceWithin(joined, "The Tidelands")).isEqualTo(3.0)
    }

    /**
     * The other direction the plain parse gets wrong: a series whose own name has a comma
     * in it is one series, not two, and matching the name whole is what tells them apart.
     */
    @Test
    fun `a series name with a comma in it keeps its number`() {
        assertThat(Series.sequenceWithin("Riverton, The #2", "Riverton, The")).isEqualTo(2.0)
    }

    @Test
    fun `a name that merely starts another one does not steal its number`() {
        val joined = "The Breakwater, The Breakwater Companion #4"

        assertThat(Series.sequenceWithin(joined, "The Breakwater")).isNull()
        assertThat(Series.sequenceWithin(joined, "The Breakwater Companion")).isEqualTo(4.0)
    }

    @Test
    fun `a series with no number in the string has none, not a guess`() {
        assertThat(Series.sequenceWithin("The Tidelands", "The Tidelands")).isNull()
        assertThat(Series.sequenceWithin("The Breakwater #1, The Tidelands", "The Tidelands")).isNull()
        assertThat(Series.sequenceWithin("The Breakwater #1", "Riverton")).isNull()
        assertThat(Series.sequenceWithin(null, "The Breakwater")).isNull()
    }

    /**
     * The server's own sequence column is free text, and the server reads it with
     * `CAST(sequence AS FLOAT)` — which turns "Book Two" into zero and would put it at the
     * front of the series. Nothing but a plain number is a position here.
     */
    @Test
    fun `only a plain number is read as a position`() {
        assertThat(Series.parseSequence("2")).isEqualTo(2.0)
        assertThat(Series.parseSequence(" 2.5 ")).isEqualTo(2.5)
        assertThat(Series.parseSequence("Book Two")).isNull()
        assertThat(Series.parseSequence("2a")).isNull()
        assertThat(Series.parseSequence("IV")).isNull()
        assertThat(Series.parseSequence("")).isNull()
        assertThat(Series.parseSequence(null)).isNull()
    }

    @Test
    fun `describes a volume the way a reader would say it`() {
        assertThat(Series.describe("The Breakwater #2")).isEqualTo("Book 2 of The Breakwater")
        assertThat(Series.describe("Discworld #16.5")).isEqualTo("Book 16.5 of Discworld")
        assertThat(Series.describe("Sprawl")).isEqualTo("Sprawl")
        assertThat(Series.describe(null)).isNull()
    }
}
