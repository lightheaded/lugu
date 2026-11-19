package io.github.lightheaded.lugu.feature.settings

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.api.ConnectionHeader
import org.junit.Test

/**
 * Saved proxy headers belong to one address, and the sign-in screen must not carry them to
 * another. A pre-filled address that is edited into a different host used to keep its
 * headers, and the sign-in then saved them under the new host and sent them there.
 */
class LoginHeadersFollowAddressTest {

    private val stored = mapOf(
        HOME to listOf(ConnectionHeader("CF-Access-Client-Id", "home-id")),
        OTHER to listOf(ConnectionHeader("X-Other", "other-value")),
    )
    private val read: (String) -> List<ConnectionHeader> = { stored[it].orEmpty() }

    private val prefilled = LoginUiState(
        serverUrl = HOME,
        headers = stored.getValue(HOME),
        headersAddress = HOME,
    )

    @Test
    fun `editing the address into another host drops the old host's headers`() {
        val edited = followAddress(prefilled, "https://elsewhere.example", read)

        assertThat(edited.headers).isEmpty()
        assertThat(edited.headersAddress).isEqualTo("https://elsewhere.example")
    }

    @Test
    fun `an address with headers of its own brings them in`() {
        val edited = followAddress(prefilled, OTHER, read)

        assertThat(edited.headers).isEqualTo(stored.getValue(OTHER))
    }

    @Test
    fun `typing back to the first address brings its headers back`() {
        val away = followAddress(prefilled, "https://books.exampl", read)
        val back = followAddress(away, "https://books.example", read)

        assertThat(away.headers).isEmpty()
        assertThat(back.headers).isEqualTo(stored.getValue(HOME))
    }

    /** "books.example/" and "https://books.example" are one address. */
    @Test
    fun `the same address written differently keeps the headers`() {
        val edited = followAddress(prefilled, "books.example/", read)

        assertThat(edited.headers).isEqualTo(stored.getValue(HOME))
    }

    /** What the listener typed into the header dialog is their work, wherever it goes. */
    @Test
    fun `a header typed by hand stays when the address changes`() {
        val own = ConnectionHeader("X-Typed", "typed-here")
        val edited = followAddress(
            prefilled.copy(headers = listOf(own), typedHeaders = setOf("X-Typed")),
            "https://elsewhere.example",
            read,
        )

        assertThat(edited.headers).containsExactly(own)
    }

    /** One typed header must not carry the stored secret of the first address along. */
    @Test
    fun `a typed header does not take the first address's stored headers with it`() {
        val own = ConnectionHeader("X-Typed", "typed-here")
        val edited = followAddress(
            prefilled.copy(headers = stored.getValue(HOME) + own, typedHeaders = setOf("X-Typed")),
            OTHER,
            read,
        )

        assertThat(edited.headers).containsExactly(stored.getValue(OTHER).single(), own)
    }

    @Test
    fun `a change of address forgets what the check found`() {
        val found = prefilled.copy(serverFound = true, serverVersionHint = "2.30.0")

        val edited = followAddress(found, "https://books.example/x", read)

        assertThat(edited.serverFound).isFalse()
        assertThat(edited.serverVersionHint).isNull()
    }

    private companion object {
        const val HOME = "https://books.example"
        const val OTHER = "https://shelf.example"
    }
}
