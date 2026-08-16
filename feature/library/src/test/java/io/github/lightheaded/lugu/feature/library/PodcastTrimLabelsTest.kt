package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.PodcastTrim
import org.junit.Test

/**
 * The one line that says what a show is skipping, and on whose say-so.
 *
 * Pinned in words because the picture cannot prove it: a show trimmed to nothing and a show
 * following a default of nothing draw the same controls, and the only thing separating them
 * is this sentence. Get it wrong and "I turned that off for this show" and "nobody has
 * touched this" become the same state — until the default changes and only one of them
 * moves.
 */
class PodcastTrimLabelsTest {

    @Test
    fun `a show on the default says so, even when the default does nothing`() {
        assertThat(trimStatusLine(PodcastTrim.NONE, isOwn = false))
            .isEqualTo("Following the default — nothing skipped")
    }

    @Test
    fun `a show trimmed to nothing is not a show on the default`() {
        assertThat(trimStatusLine(PodcastTrim.NONE, isOwn = true))
            .isEqualTo("Set for this show — nothing skipped")
    }

    @Test
    fun `the line names what is being cut, in the order it is cut`() {
        val trim = PodcastTrim(introSec = 15, outroSec = 30, skipMarkedAdverts = true)
        assertThat(trimStatusLine(trim, isOwn = true))
            .isEqualTo("Set for this show — 15s intro, 30s outro, marked adverts")
    }

    @Test
    fun `only the parts that are set are mentioned`() {
        assertThat(trimStatusLine(PodcastTrim(introSec = 20), isOwn = true))
            .isEqualTo("Set for this show — 20s intro")
        assertThat(trimStatusLine(PodcastTrim(skipMarkedAdverts = true), isOwn = false))
            .isEqualTo("Following the default — marked adverts")
    }

    @Test
    fun `a whole minute is said as one, not as sixty`() {
        assertThat(trimStatusLine(PodcastTrim(outroSec = 60), isOwn = true))
            .isEqualTo("Set for this show — 1 min outro")
    }

    @Test
    fun `the off choice is worded as off rather than as a length`() {
        assertThat(trimChoiceLabel(0)).isEqualTo("None")
        assertThat(trimChoiceLabel(15)).isEqualTo("15s")
        assertThat(trimChoiceLabel(60)).isEqualTo("1 min")
    }
}
