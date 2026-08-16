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
        assertThat(seriesSequenceLabel(2.0)).isEqualTo("Book 2")
        assertThat(seriesSequenceLabelWithin("The Breakwater #2", "The Breakwater"))
            .isEqualTo("Book 2")
    }

    @Test
    fun `a half number survives, because novellas are numbered that way`() {
        assertThat(seriesSequenceLabel(2.5)).isEqualTo("Book 2.5")
        assertThat(seriesSequenceLabelWithin("Riverton #2.5", "Riverton")).isEqualTo("Book 2.5")
    }

    @Test
    fun `an entry with no number is left unnumbered rather than guessed at`() {
        assertThat(seriesSequenceLabel(null)).isNull()
        assertThat(seriesSequenceLabelWithin("The Tidelands", "The Tidelands")).isNull()
        assertThat(seriesSequenceLabelWithin(null, "The Breakwater")).isNull()
    }

    /**
     * The bug this page had, on the one screen whose whole job is putting a series in
     * order. The server joins every series a book is in into one field, so reading the
     * trailing number labels a two-series book with the *other* series' position — and it
     * does so on the page of the series it is not the number for.
     */
    @Test
    fun `a book in two series is numbered for the series being looked at`() {
        val joined = "The Breakwater #1, The Tidelands #3"

        assertThat(seriesSequenceLabelWithin(joined, "The Breakwater")).isEqualTo("Book 1")
        assertThat(seriesSequenceLabelWithin(joined, "The Tidelands")).isEqualTo("Book 3")
    }

    @Test
    fun `an empty page says which kind of empty it is`() {
        assertThat(emptyBrowseLine(BrowseKind.SERIES, totalGroups = 0, query = ""))
            .contains("series information")
        assertThat(emptyBrowseLine(BrowseKind.SERIES, totalGroups = 40, query = "brea"))
            .isEqualTo("No series match that.")
    }
}
