package io.github.lightheaded.lugu.core.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What may be used as a cover's file name, and where an id hides in a URL.
 *
 * Both questions are asked in places where the answer arrives from outside this app — an
 * exported provider, and a URL a screen built — so the awkward cases are settled here rather
 * than discovered on a phone.
 */
class CoverIdTest {

    @Test
    fun `a server id is an id`() {
        assertThat(CoverId.isValid("li_a3f9x2")).isTrue()
        assertThat(CoverId.isValid("4c8e1b2a-7d31-4f60-9a55-0e2d6b8c1f43")).isTrue()
    }

    /**
     * The one that matters. An id becomes part of a path, so a segment that climbs out of the
     * directory is the difference between serving covers and serving files.
     */
    @Test
    fun `nothing that could leave the directory is an id`() {
        listOf("..", ".", "../secrets", "a/b", "a\\b", "", " ").forEach {
            assertThat(CoverId.isValid(it)).isFalse()
        }
    }

    /** A dot is excluded outright, which is what keeps `.part` files from being addressable. */
    @Test
    fun `a dot is not part of an id`() {
        assertThat(CoverId.isValid("item-1.part")).isFalse()
    }

    @Test
    fun `an absurdly long id is not one`() {
        assertThat(CoverId.isValid("a".repeat(129))).isFalse()
        assertThat(CoverId.isValid("a".repeat(128))).isTrue()
    }

    @Test
    fun `the id comes out of a cover url`() {
        assertThat(CoverId.itemIdIn("https://books.example/api/items/li_a3f9x2/cover?width=400"))
            .isEqualTo("li_a3f9x2")
    }

    /** A width is a query parameter, so a cover with no width is still a cover. */
    @Test
    fun `the width is not required`() {
        assertThat(CoverId.itemIdIn("https://books.example/api/items/li_a3f9x2/cover"))
            .isEqualTo("li_a3f9x2")
    }

    /**
     * A server installed under a subpath puts something before `/api`, and it is still the
     * same endpoint. Matching on where the path *starts* would miss every one of them.
     */
    @Test
    fun `a server behind a subpath is still matched`() {
        assertThat(CoverId.itemIdIn("https://example.test/books/api/items/li_x/cover"))
            .isEqualTo("li_x")
    }

    /**
     * Anything else falls through to the network, which is what happened before any of this
     * existed. A near miss is the case worth pinning: the item's own endpoint is not its cover.
     */
    @Test
    fun `only a cover url gives up an id`() {
        listOf(
            "https://books.example/api/items/li_a3f9x2",
            "https://books.example/api/items/li_a3f9x2/play",
            "https://books.example/api/items/li_a3f9x2/covers",
            "https://books.example/api/items//cover",
            "https://books.example/api/libraries/lib_1/cover",
            "https://example.test/logo.png",
        ).forEach { assertThat(CoverId.itemIdIn(it)).isNull() }
    }

    /** An id that would not be storable is not returned as one, however the URL was built. */
    @Test
    fun `a url carrying a bad id gives up nothing`() {
        assertThat(CoverId.itemIdIn("https://books.example/api/items/../cover")).isNull()
    }
}
