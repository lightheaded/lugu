package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.ItemSort
import org.junit.Test

/**
 * The rail is only honest if its letters come from the list as ordered, and if it hides
 * itself on orderings that are not alphabetical at all. Both rules are pinned here because
 * both are invisible until the rail sends someone to the wrong row.
 */
class FastScrollRailTest {

    @Test
    fun `the initial is the first letter, whatever case it was written in`() {
        assertThat(initialOf("dune")).isEqualTo('D')
        assertThat(initialOf("Dune")).isEqualTo('D')
    }

    @Test
    fun `leading space does not become a letter of its own`() {
        assertThat(initialOf("  Dune")).isEqualTo('D')
    }

    @Test
    fun `anything that is not a letter shares one bucket`() {
        assertThat(initialOf("1984")).isEqualTo(OTHER_INITIAL)
        assertThat(initialOf("\"Repent, Harlequin!\"")).isEqualTo(OTHER_INITIAL)
        assertThat(initialOf("")).isEqualTo(OTHER_INITIAL)
    }

    @Test
    fun `letters keep the order the list is in`() {
        val letters = fastScrollLetters(listOf("Anathem", "Anvil", "Borne", "Dune"))
        assertThat(letters).containsExactly('A', 'B', 'D').inOrder()
    }

    @Test
    fun `an unattributed bucket sorted last stays last`() {
        val letters = fastScrollLetters(listOf("Adams", "Le Guin", ""))
        assertThat(letters).containsExactly('A', 'L', OTHER_INITIAL).inOrder()
    }

    @Test
    fun `a letter jumps to the first row filing under it`() {
        val keys = listOf("Anathem", "Anvil", "Borne", "Dune")
        assertThat(firstIndexOfLetter(keys, 'B')).isEqualTo(2)
        assertThat(firstIndexOfLetter(keys, 'A')).isEqualTo(0)
    }

    @Test
    fun `a letter nothing files under has nowhere to go`() {
        assertThat(firstIndexOfLetter(listOf("Dune"), 'Q')).isEqualTo(-1)
    }

    @Test
    fun `a long alphabetical list earns a rail`() {
        assertThat(earns(itemCount = 400, letterCount = 20, alphabetical = true)).isTrue()
    }

    @Test
    fun `an ordering that is not alphabetical never earns one`() {
        assertThat(earns(itemCount = 400, letterCount = 20, alphabetical = false)).isFalse()
    }

    @Test
    fun `a short list is quicker to flick through than to index`() {
        assertThat(earns(itemCount = 12, letterCount = 8, alphabetical = true)).isFalse()
    }

    @Test
    fun `a long list that all files under two letters is not worth indexing`() {
        assertThat(earns(itemCount = 400, letterCount = 2, alphabetical = true)).isFalse()
    }

    /**
     * Which of the grid's orderings count as alphabetical, since that is what the grid hands
     * the rule above. The browse lists have no sort to ask — a list of names is in name
     * order or it is nothing — so they answer the same question with a constant.
     */
    @Test
    fun `only title and author put the grid in letter order`() {
        assertThat(ItemSort.TITLE.isAlphabetical).isTrue()
        assertThat(ItemSort.AUTHOR.isAlphabetical).isTrue()
        assertThat(ItemSort.ADDED.isAlphabetical).isFalse()
        assertThat(ItemSort.DURATION.isAlphabetical).isFalse()
        assertThat(ItemSort.PROGRESS.isAlphabetical).isFalse()
    }

    private fun earns(itemCount: Int, letterCount: Int, alphabetical: Boolean): Boolean =
        fastScrollEarnsItsPlace(
            itemCount = itemCount,
            letterCount = letterCount,
            orderedAlphabetically = alphabetical,
        )
}
