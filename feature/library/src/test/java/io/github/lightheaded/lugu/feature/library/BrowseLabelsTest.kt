package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.sync.BrowseKind
import org.junit.Test

/**
 * The words the browse pages put on screen.
 *
 * Pinned because each of them is a claim about the library: a count that says "books" in a
 * podcast library, or a series card that invents a volume number, is wrong in a way nobody
 * would notice until they trusted it.
 */
class BrowseLabelsTest {

    @Test
    fun `a count says what the library holds`() {
        assertThat(groupCountLine(12, MediaType.BOOK)).isEqualTo("12 books")
        assertThat(groupCountLine(12, MediaType.PODCAST)).isEqualTo("12 podcasts")
    }

    @Test
    fun `one of something is not one books`() {
        assertThat(groupCountLine(1, MediaType.BOOK)).isEqualTo("1 book")
        assertThat(groupCountLine(1, MediaType.PODCAST)).isEqualTo("1 podcast")
    }

    @Test
    fun `a large count is grouped so it can be read at a glance`() {
        assertThat(groupCountLine(1204, MediaType.BOOK)).isEqualTo("1,204 books")
    }

    @Test
    fun `a whole sequence loses its decimal point`() {
        assertThat(seriesSequenceLabel("The Breakwater #2")).isEqualTo("Book 2")
    }

    @Test
    fun `a half number survives, because novellas are numbered that way`() {
        assertThat(seriesSequenceLabel("Riverton #2.5")).isEqualTo("Book 2.5")
    }

    @Test
    fun `an entry with no number is left unnumbered rather than guessed at`() {
        assertThat(seriesSequenceLabel("The Tidelands")).isNull()
        assertThat(seriesSequenceLabel(null)).isNull()
    }

    @Test
    fun `an empty page says which kind of empty it is`() {
        assertThat(emptyBrowseLine(BrowseKind.SERIES, totalGroups = 0, query = ""))
            .contains("series information")
        assertThat(emptyBrowseLine(BrowseKind.SERIES, totalGroups = 40, query = "brea"))
            .isEqualTo("No series match that.")
    }
}
