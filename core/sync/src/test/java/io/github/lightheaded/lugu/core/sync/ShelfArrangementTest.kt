package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * How a stored shelf order survives the app changing underneath it.
 *
 * The failure worth guarding against is not a wrong order today but a wrong order after
 * an upgrade: someone arranged six shelves a year ago, a seventh was added since, and the
 * stored list has never heard of it. Dropping it, or letting it take another shelf's
 * place, would both look like a bug in the shelves rather than in the preference.
 */
class ShelfArrangementTest {

    private val declared = ShelfKind.entries

    @Test
    fun `no stored order means the order they are declared in`() {
        val settings = LibrarySettings()

        assertThat(settings.arrangeShelves(declared) { it.name }).isEqualTo(declared)
    }

    @Test
    fun `a stored order is honoured`() {
        val settings = LibrarySettings(
            shelfOrder = listOf(ShelfKind.DOWNLOADED.name, ShelfKind.CONTINUE.name),
        )

        val arranged = settings.arrangeShelves(
            listOf(ShelfKind.CONTINUE, ShelfKind.DOWNLOADED),
        ) { it.name }

        assertThat(arranged).containsExactly(ShelfKind.DOWNLOADED, ShelfKind.CONTINUE).inOrder()
    }

    @Test
    fun `a shelf the stored order has never heard of still appears`() {
        // The stored list predates SHORT_LISTENS, which is what an upgrade looks like.
        val settings = LibrarySettings(
            shelfOrder = listOf(ShelfKind.DOWNLOADED.name, ShelfKind.CONTINUE.name),
        )

        val arranged = settings.arrangeShelves(declared) { it.name }

        assertThat(arranged).contains(ShelfKind.SHORT_LISTENS)
        assertThat(arranged).hasSize(declared.size)
        // The two that were arranged keep their arrangement.
        assertThat(arranged.indexOf(ShelfKind.DOWNLOADED))
            .isLessThan(arranged.indexOf(ShelfKind.CONTINUE))
    }

    @Test
    fun `a hidden shelf is not shown, whatever the order says`() {
        val settings = LibrarySettings(
            shelfOrder = declared.map { it.name },
            hiddenShelves = setOf(ShelfKind.SHORT_LISTENS.name),
        )

        val arranged = settings.arrangeShelves(declared) { it.name }

        assertThat(arranged).doesNotContain(ShelfKind.SHORT_LISTENS)
        assertThat(arranged).hasSize(declared.size - 1)
    }

    @Test
    fun `a stored name that no longer exists is ignored rather than fatal`() {
        val settings = LibrarySettings(
            shelfOrder = listOf("A_SHELF_THAT_WAS_REMOVED", ShelfKind.CONTINUE.name),
            hiddenShelves = setOf("ALSO_GONE"),
        )

        val arranged = settings.arrangeShelves(declared) { it.name }

        assertThat(arranged).hasSize(declared.size)
        assertThat(arranged).containsExactlyElementsIn(declared)
    }
}
