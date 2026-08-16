package io.github.lightheaded.lugu.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormattingTest {

    @Test
    fun `a place under an hour drops the hour field rather than showing zero`() {
        assertThat(formatClock(0.0)).isEqualTo("0:00")
        assertThat(formatClock(64.0)).isEqualTo("1:04")
        assertThat(formatClock(3599.0)).isEqualTo("59:59")
    }

    @Test
    fun `an hour brings the field back, with both smaller fields padded`() {
        assertThat(formatClock(3600.0)).isEqualTo("1:00:00")
        assertThat(formatClock(3723.0)).isEqualTo("1:02:03")
    }

    /**
     * A negative position is not a display problem to solve prettily — it is a bug
     * upstream. Clamping means the screen shows the start of the book rather than
     * "-1:-1", which is at least a place that exists.
     */
    @Test
    fun `a position before the start reads as the start`() {
        assertThat(formatClock(-30.0)).isEqualTo("0:00")
        assertThat(formatLength(-30.0)).isEqualTo("0 s")
        assertThat(formatLengthCompact(-30.0)).isEqualTo("0s")
    }

    /**
     * Truncation rather than rounding, and the same truncation in both. A length that
     * rounds up reads as one second longer than the scrubber will ever reach.
     */
    @Test
    fun `a fraction of a second is dropped, not rounded up`() {
        assertThat(formatClock(59.9)).isEqualTo("0:59")
        assertThat(formatLength(3599.9)).isEqualTo("59 min")
    }

    @Test
    fun `a length says its units, and omits the ones that are zero`() {
        assertThat(formatLength(4800.0)).isEqualTo("1 h 20 min")
        assertThat(formatLength(7200.0)).isEqualTo("2 h")
        assertThat(formatLength(1200.0)).isEqualTo("20 min")
        assertThat(formatLength(45.0)).isEqualTo("45 s")
    }

    /**
     * The compact form keeps the minutes even when they are zero, because it is used in a
     * subline beside other facts and "2h" next to "2h 0m" in the next row reads as two
     * different kinds of measurement rather than as one being rounder than the other.
     */
    @Test
    fun `the compact form is the same length with the spaces squeezed out`() {
        assertThat(formatLengthCompact(4800.0)).isEqualTo("1h 20m")
        assertThat(formatLengthCompact(7200.0)).isEqualTo("2h 0m")
        assertThat(formatLengthCompact(1200.0)).isEqualTo("20m")
        assertThat(formatLengthCompact(45.0)).isEqualTo("45s")
    }

    @Test
    fun `a settings figure in whole minutes says minutes`() {
        assertThat(formatShortSeconds(300)).isEqualTo("5 min")
        assertThat(formatShortSeconds(60)).isEqualTo("1 min")
        assertThat(formatShortSeconds(90)).isEqualTo("90s")
        assertThat(formatShortSeconds(30)).isEqualTo("30s")
        assertThat(formatShortSeconds(-5)).isEqualTo("0s")
    }

    /**
     * The reason the speed formatter exists: "2.0x" wraps a narrow chip onto two lines
     * and says nothing "2x" does not.
     */
    @Test
    fun `a whole speed loses its decimal point`() {
        assertThat(formatSpeedNumber(2.0f)).isEqualTo("2")
        assertThat(formatSpeed(2.0f)).isEqualTo("2x")
        assertThat(formatSpeed(1.0f)).isEqualTo("1x")
    }

    @Test
    fun `a fractional speed keeps the digits that mean something`() {
        assertThat(formatSpeed(1.25f)).isEqualTo("1.25x")
        assertThat(formatSpeed(1.2f)).isEqualTo("1.2x")
        assertThat(formatSpeed(1.5f)).isEqualTo("1.5x")
    }

    /**
     * The reason this rounds rather than truncates, and a bug that shipped.
     *
     * 0.05 has no exact binary representation, so stepping up from 1.0 lands on
     * 1.7999992 after sixteen presses and 1.9999990 after twenty. The old truncating
     * formatter printed those as "1.79x" and "1.99x" — a chip disagreeing with the button
     * that had just been pressed, which reads as the press not having taken.
     */
    @Test
    fun `a speed that accumulated float error still reads cleanly`() {
        var accumulated = 1.0f
        repeat(16) { accumulated += SpeedStep }
        assertThat(accumulated).isLessThan(1.8f)
        assertThat(formatSpeed(accumulated)).isEqualTo("1.8x")

        var toWhole = 1.0f
        repeat(20) { toWhole += SpeedStep }
        assertThat(toWhole).isLessThan(2.0f)
        assertThat(formatSpeed(toWhole)).isEqualTo("2x")
    }

    /** Rounding stops at hundredths, so a genuinely odd speed is not silently tidied away. */
    @Test
    fun `rounding does not reach far enough to hide a real difference`() {
        assertThat(formatSpeed(1.33f)).isEqualTo("1.33x")
        assertThat(formatSpeed(1.05f)).isEqualTo("1.05x")
    }

    private companion object {
        /** `SpeedSettings.STEP` lives in `:core:sync`, which this module must not depend on. */
        const val SpeedStep = 0.05f
    }

    @Test
    fun `an episode names itself and lets the show be the subtitle`() {
        assertThat(ContinueLabel.title("The Tidewatch Hour", "Salt and Sediment"))
            .isEqualTo("Salt and Sediment")
        assertThat(ContinueLabel.subtitle("The Tidewatch Hour", "Marisol Fen", "Salt and Sediment"))
            .isEqualTo("The Tidewatch Hour")
    }

    @Test
    fun `a book keeps its own title and its author`() {
        assertThat(ContinueLabel.title("Lighthouse Wakes", null)).isEqualTo("Lighthouse Wakes")
        assertThat(ContinueLabel.subtitle("Lighthouse Wakes", "James T. R. Corven", null))
            .isEqualTo("James T. R. Corven")
    }

    /**
     * The mirror can hold a progress row for an episode it has not yet stored the details
     * of. A row that reads imprecisely is better than one that cannot be read at all,
     * which is what a blank title in a car amounts to.
     */
    @Test
    fun `an episode with no title falls back to the item rather than to a blank`() {
        assertThat(ContinueLabel.title("The Tidewatch Hour", "")).isEqualTo("The Tidewatch Hour")
        assertThat(ContinueLabel.title("The Tidewatch Hour", "   ")).isEqualTo("The Tidewatch Hour")
        assertThat(ContinueLabel.subtitle("The Tidewatch Hour", "Marisol Fen", ""))
            .isEqualTo("Marisol Fen")
    }
}
