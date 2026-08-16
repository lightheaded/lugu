package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The car's Continue node offers episodes rather than shows, and a row that says only the
 * show's name would give back exactly what that change was made to remove.
 */
class ContinueRowsTest {

    @Test
    fun `an episode names itself and the show is the subtitle`() {
        assertThat(ContinueRows.title("The Rest Is History", "Cleopatra, part two"))
            .isEqualTo("Cleopatra, part two")
        assertThat(
            ContinueRows.subtitle(
                itemTitle = "The Rest Is History",
                author = "Goalhanger",
                episodeTitle = "Cleopatra, part two",
            ),
        ).isEqualTo("The Rest Is History")
    }

    @Test
    fun `a book keeps its own title and its author`() {
        assertThat(ContinueRows.title("Piranesi", null)).isEqualTo("Piranesi")
        assertThat(
            ContinueRows.subtitle(itemTitle = "Piranesi", author = "Susanna Clarke", episodeTitle = null),
        ).isEqualTo("Susanna Clarke")
    }

    /** An episode the mirror has not seen yet must still produce a row that can be read. */
    @Test
    fun `an episode with no title falls back to the show`() {
        assertThat(ContinueRows.title("The Rest Is History", " ")).isEqualTo("The Rest Is History")
        assertThat(
            ContinueRows.subtitle(itemTitle = "The Rest Is History", author = "Goalhanger", episodeTitle = " "),
        ).isEqualTo("Goalhanger")
    }
}
