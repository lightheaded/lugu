package io.github.lightheaded.lugu.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The addresses handed to a browser.
 *
 * Small, and worth pinning anyway: these are the one kind of URL in the app nothing else
 * checks. Every other address is used by lugu itself, so a mistake in it fails loudly on the
 * next request — a bad link opens a browser on a broken page and reports nothing at all.
 */
class WebClientTest {

    @Test
    fun `an item links to its own page`() {
        assertThat(WebClient.item("https://books.example", "li_a3f9x2"))
            .isEqualTo("https://books.example/item/li_a3f9x2")
    }

    /**
     * Addresses are stored as typed, and people type trailing slashes. Two of them in the
     * middle of a URL is the kind of thing a server may or may not forgive.
     */
    @Test
    fun `a trailing slash does not become a double one`() {
        assertThat(WebClient.item("https://books.example/", "li_x"))
            .isEqualTo("https://books.example/item/li_x")
        assertThat(WebClient.home("https://books.example/")).isEqualTo("https://books.example")
    }

    /** A subpath install keeps its subpath: the web client lives under it, not beside it. */
    @Test
    fun `a server under a subpath keeps it`() {
        assertThat(WebClient.item("https://example.test/books", "li_x"))
            .isEqualTo("https://example.test/books/item/li_x")
    }

    /** A port is part of the address and is not a path, so nothing here should touch it. */
    @Test
    fun `a port survives`() {
        assertThat(WebClient.home("http://192.168.1.10:13378"))
            .isEqualTo("http://192.168.1.10:13378")
    }
}
