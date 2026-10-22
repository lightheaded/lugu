package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The position history recorded every seek as "seek", so a skip, a chapter skip and a drag
 * of the scrubber all looked the same there. The clock is a number the test moves by hand.
 */
class SeekReasonsTest {

    private var now = 1_000_000L
    private val reasons = SeekReasons { now }

    @Test
    fun `a seek that lands where the note said gets the note's reason`() {
        reasons.expect(SeekReasons.CHAPTER_NEXT, 4200.0)

        assertThat(reasons.reasonFor(4200.0)).isEqualTo(SeekReasons.CHAPTER_NEXT)
    }

    @Test
    fun `a seek with no note is a plain seek`() {
        assertThat(reasons.reasonFor(120.0)).isEqualTo(SeekReasons.SEEK)
    }

    /** One note, one seek. A second seek to the same place is somebody else's. */
    @Test
    fun `a note is spent by the seek it was for`() {
        reasons.expect(SeekReasons.SKIP_BACK, 985.0)

        reasons.reasonFor(985.0)

        assertThat(reasons.reasonFor(985.0)).isEqualTo(SeekReasons.SEEK)
    }

    /**
     * A car's scrubber writes no note. Its seek must not take the reason of a skip that is
     * still on its way, and the skip must still get its reason when it lands.
     */
    @Test
    fun `a seek somewhere else does not take the note, and the note waits`() {
        reasons.expect(SeekReasons.SKIP_FORWARD, 1030.0)

        assertThat(reasons.reasonFor(5000.0)).isEqualTo(SeekReasons.SEEK)
        assertThat(reasons.reasonFor(1030.0)).isEqualTo(SeekReasons.SKIP_FORWARD)
    }

    @Test
    fun `a note that is too old is not used`() {
        reasons.expect(SeekReasons.SKIP_BACK, 985.0)

        now += SeekReasons.WINDOW_MS + 1

        assertThat(reasons.reasonFor(985.0)).isEqualTo(SeekReasons.SEEK)
    }

    /** Milliseconds and a track boundary move the landing a little. That is still a match. */
    @Test
    fun `a landing within the tolerance matches`() {
        reasons.expect(SeekReasons.SKIP_BACK, 985.0)

        assertThat(reasons.reasonFor(985.4)).isEqualTo(SeekReasons.SKIP_BACK)
    }

    /** A skip back near the start asks for a negative position and lands on zero. */
    @Test
    fun `a target before the start matches a landing at zero`() {
        reasons.expect(SeekReasons.SKIP_BACK, -10.0)

        assertThat(reasons.reasonFor(0.0)).isEqualTo(SeekReasons.SKIP_BACK)
    }

    /** The newest note is the one that counts: presses come faster than seeks land. */
    @Test
    fun `a newer note replaces an older one`() {
        reasons.expect(SeekReasons.SKIP_BACK, 985.0)
        reasons.expect(SeekReasons.CHAPTER_PREVIOUS, 600.0)

        assertThat(reasons.reasonFor(600.0)).isEqualTo(SeekReasons.CHAPTER_PREVIOUS)
    }
}
