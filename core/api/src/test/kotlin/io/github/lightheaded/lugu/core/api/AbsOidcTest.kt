package io.github.lightheaded.lugu.core.api

import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.junit.Test

/**
 * The identity-provider sign-in, checked against the contract read out of the server.
 *
 * None of this has met a real provider, and it cannot: a provider is a service, not a
 * fixture. What can be checked here is everything the server refuses on, and there are
 * four such refusals in `OidcAuthStrategy.js`: a missing `code_challenge`, a
 * `code_challenge_method` other than `S256`, a `response_type` other than `code`, and a
 * `redirect_uri` that is not on the server's list.
 *
 * And one thing no server checks, which is the one that matters most: that a redirect
 * arriving with somebody else's `state` is refused here.
 */
class AbsOidcTest {

    @OptIn(ExperimentalEncodingApi::class)
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private fun attempt(state: String = "state-1", verifier: String = "verifier-1") =
        AbsOidc.Attempt(
            authorizationUrl = "https://idp.example/authorize",
            state = state,
            codeVerifier = verifier,
            cookies = emptyList(),
        )

    // region what the server refuses on

    @Test
    fun `the start url carries everything the server needs to enter its mobile flow`() {
        val url = AbsOidc.startUrl(
            baseUrl = "https://books.example",
            state = "st",
            challenge = "ch",
        )

        assertThat(url).startsWith("https://books.example/auth/openid?")
        // Any one of these three puts the server into the mobile flow. All three are sent,
        // so the flow does not rest on which one it checks first.
        assertThat(url).contains("response_type=code")
        assertThat(url).contains("redirect_uri=lugu%3A%2F%2Foauth")
        assertThat(url).contains("code_challenge=ch")
        // The server rejects anything but S256 outright.
        assertThat(url).contains("code_challenge_method=S256")
        assertThat(url).contains("state=st")
    }

    @Test
    fun `a trailing slash on the address does not double up`() {
        val url = AbsOidc.startUrl("https://books.example/", state = "st", challenge = "ch")

        assertThat(url).startsWith("https://books.example/auth/openid?")
    }

    /**
     * The challenge has to be exactly `BASE64URL(SHA256(ASCII(verifier)))`, computed here
     * against a digest taken independently. A wrong challenge fails at the provider, after
     * the person has already typed their password, with nothing on screen to say why.
     */
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `the challenge is the S256 of the verifier`() {
        val verifier = AbsOidc.newVerifier()

        val expected = base64Url.encode(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )

        assertThat(AbsOidc.challengeFor(verifier)).isEqualTo(expected)
    }

    /** RFC 7636 wants 43 to 128 characters, and unreserved ones only. */
    @Test
    fun `a verifier is long enough and has nothing in it that needs encoding`() {
        repeat(50) {
            val verifier = AbsOidc.newVerifier()
            assertThat(verifier.length).isAtLeast(43)
            assertThat(verifier.length).isAtMost(128)
            assertThat(AbsOidc.urlEncode(verifier)).isEqualTo(verifier)
        }
    }

    @Test
    fun `two attempts never share a verifier or a state`() {
        val verifiers = List(200) { AbsOidc.newVerifier() }
        val states = List(200) { AbsOidc.newState() }

        assertThat(verifiers.toSet()).hasSize(200)
        assertThat(states.toSet()).hasSize(200)
    }

    // endregion

    // region the state check, which is lugu's own

    /**
     * The reason `state` exists. Any app on the phone can register `lugu://` and any app
     * can open one, so a redirect can arrive that this app never started. Exchanging its
     * `code` would sign somebody into an account they did not choose.
     */
    @Test
    fun `a redirect with the wrong state is refused`() {
        val result = AbsOidc.readRedirect("lugu://oauth?code=abc&state=somebody-elses", attempt())

        assertThat(result).isInstanceOf(AbsOidc.Redirect.Failed::class.java)
    }

    @Test
    fun `a redirect with no attempt in progress is refused`() {
        val result = AbsOidc.readRedirect("lugu://oauth?code=abc&state=state-1", attempt = null)

        assertThat(result).isInstanceOf(AbsOidc.Redirect.Failed::class.java)
    }

    @Test
    fun `a redirect that matches is read`() {
        val result = AbsOidc.readRedirect("lugu://oauth?code=abc123&state=state-1", attempt())

        assertThat(result).isEqualTo(AbsOidc.Redirect.Code(code = "abc123", state = "state-1"))
    }

    @Test
    fun `an error on the query is reported rather than treated as a code`() {
        val result = AbsOidc.readRedirect("lugu://oauth?error=access_denied", attempt())

        assertThat(result).isEqualTo(AbsOidc.Redirect.Failed("access_denied"))
    }

    @Test
    fun `a redirect with no code at all is refused`() {
        val result = AbsOidc.readRedirect("lugu://oauth?state=state-1", attempt())

        assertThat(result).isInstanceOf(AbsOidc.Redirect.Failed::class.java)
    }

    /** The server percent-encodes both values before it redirects, so both come back encoded. */
    @Test
    fun `an encoded code and state are decoded before they are compared`() {
        val result = AbsOidc.readRedirect(
            "lugu://oauth?code=a%2Fb%2Bc&state=st%2Fate",
            attempt(state = "st/ate"),
        )

        assertThat(result).isEqualTo(AbsOidc.Redirect.Code(code = "a/b+c", state = "st/ate"))
    }

    // endregion

    // region encoding

    /**
     * Written out rather than borrowed from `URLEncoder`, which is form encoding: it turns
     * a space into `+` and leaves `*` alone. A `+` inside a challenge is a different
     * challenge.
     */
    @Test
    fun `encoding is url encoding and not form encoding`() {
        assertThat(AbsOidc.urlEncode("a b")).isEqualTo("a%20b")
        assertThat(AbsOidc.urlEncode("a+b")).isEqualTo("a%2Bb")
        assertThat(AbsOidc.urlEncode("*")).isEqualTo("%2A")
        assertThat(AbsOidc.urlEncode("lugu://oauth")).isEqualTo("lugu%3A%2F%2Foauth")
        // Unreserved characters are left exactly as they are.
        assertThat(AbsOidc.urlEncode("aZ09-_.~")).isEqualTo("aZ09-_.~")
    }

    @Test
    fun `a plus in a redirect stays a plus`() {
        // The opposite of form decoding, and the reason decoding is written out too: a
        // base64url value has no plus in it, but a provider's opaque code may.
        assertThat(AbsOidc.urlDecode("a+b")).isEqualTo("a+b")
    }

    @Test
    fun `encoding survives a round trip through decoding`() {
        val values = listOf("a b", "a+b", "*", "lugu://oauth", "ünïcøde", "%%%", "")

        values.forEach { value ->
            assertThat(AbsOidc.urlDecode(AbsOidc.urlEncode(value))).isEqualTo(value)
        }
    }

    // endregion

    // region cookies

    /**
     * `/auth/openid/callback` answers "No session" with no cookies, and answers a web page
     * rather than JSON without `auth_method`. Sending back the storage instructions a
     * browser was given would make the header invalid, so only the pairs go.
     */
    @Test
    fun `only the name and value of each cookie go back`() {
        val header = AbsOidc.cookieHeader(
            listOf(
                "connect.sid=s%3Aabc; Path=/; HttpOnly",
                "auth_method=openid-mobile; Max-Age=315360000000; Path=/; HttpOnly",
            ),
        )

        assertThat(header).isEqualTo("connect.sid=s%3Aabc; auth_method=openid-mobile")
    }

    @Test
    fun `a header with nothing usable in it comes back empty rather than malformed`() {
        assertThat(AbsOidc.cookieHeader(emptyList())).isEmpty()
        assertThat(AbsOidc.cookieHeader(listOf("Path=/"))).isEqualTo("Path=/")
        assertThat(AbsOidc.cookieHeader(listOf("; HttpOnly"))).isEmpty()
    }

    // endregion
}
