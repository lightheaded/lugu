package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Selection is shared by three lists, so its rules are pinned here rather than being
 * rediscovered on each screen — in particular that the mode outlives an empty set, and
 * that rows which leave the screen leave the selection with it.
 */
class SelectionTest {

    @Test
    fun `the first toggle enters the mode`() {
        val picked = Selection().toggle("a")
        assertThat(picked.active).isTrue()
        assertThat(picked.ids).containsExactly("a")
    }

    @Test
    fun `toggling the last row leaves the mode running`() {
        val picked = Selection().toggle("a").toggle("a")
        assertThat(picked.ids).isEmpty()
        assertThat(picked.active).isTrue()
    }

    @Test
    fun `select-all takes what is visible, and only that`() {
        val picked = Selection().toggle("gone").selectAll(listOf("a", "b"))
        assertThat(picked.ids).containsExactly("a", "b")
    }

    @Test
    fun `rows taken off the screen stop counting`() {
        val picked = Selection().toggle("a").toggle("b").retaining(listOf("b", "c"))
        assertThat(picked.ids).containsExactly("b")
    }

    @Test
    fun `retaining does nothing outside the mode`() {
        assertThat(Selection().retaining(listOf("a"))).isEqualTo(Selection())
    }

    @Test
    fun `clearing leaves the mode as well as the set`() {
        val picked = Selection().toggle("a").cleared()
        assertThat(picked.active).isFalse()
        assertThat(picked.ids).isEmpty()
    }

    @Test
    fun `entering from a menu starts with nothing picked`() {
        val picked = Selection().entered()
        assertThat(picked.active).isTrue()
        assertThat(picked.ids).isEmpty()
    }
}
