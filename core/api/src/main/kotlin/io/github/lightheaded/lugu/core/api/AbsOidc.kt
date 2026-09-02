package io.github.lightheaded.lugu.core.api

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Signing in through an identity provider, the way Audiobookshelf actually does it.
 *
 * ## Read from the server source, not from the docs
 *
 * Every step below was read out of `server/Auth.js` and `server/auth/OidcAuthStrategy.js`
 * in `advplyr/audiobookshelf`, the same way the socket events were. The published API docs
 * say of themselves that they are unmaintained, and this flow is not in them at all.
 *
 * ## The flow, and the one part that surprises people
 *
 * 1. **lugu** calls `GET /auth/openid` itself, with `response_type=code`, its
 *    `redirect_uri`, a PKCE `code_challenge`, and a `state` it made up. It does **not**
 *    follow the redirect. The server answers `302` with the provider's authorize URL in
 *    `Location`, and with `Set-Cookie` headers.
 * 2. **The browser** opens that authorize URL. The person signs in with their provider.
 * 3. The provider sends the browser to `/auth/openid/mobile-redirect`, which sends it on
 *    to lugu's own `redirect_uri` with `code` and `state` on the query.
 * 4. **lugu** calls `GET /auth/openid/callback?code=…&state=…&code_verifier=…`, and gets
 *    the tokens as JSON.
 *
 * Step 1 belongs to lugu and not to the browser, and that is the part worth stating,
 * because doing it the obvious way does not work. `/auth/openid/callback` needs the
 * express session and the `auth_method` cookie that step 1 sets, and it is `auth_method`
 * being `openid-mobile` that makes the server answer with JSON instead of redirecting to a
 * web page. A Custom Tab keeps its cookies in the browser, where lugu's HTTP client cannot
 * reach them, so if the browser made the step-1 request the final call would arrive with
 * no session and the server would answer "No session". lugu therefore holds those cookies
 * itself and sends only the provider's page to the browser.
 *
 * ## What the server has to be told first
 *
 * `redirect_uri` is checked against the server's `authOpenIDMobileRedirectURIs` list, so
 * [LUGU_REDIRECT_URI] has to be added there by an administrator — unless that list is the
 * single entry `*`. There is no way for a client to register itself, so a sign-in that
 * fails with "Invalid redirect_uri" is a server setting and not a bug here.
 *
 * ## Not proven against a real provider
 *
 * Nothing in this file has run against an identity provider. The construction, the state
 * check and the parsing are covered by `AbsOidcTest`; whether a real Keycloak or Authentik
 * accepts the result is not something this repository can answer. See docs/M4-PLAN.md.
 */
object AbsOidc {

    /**
     * Where the provider sends the browser back to.
     *
     * A private-use scheme rather than an `https` App Link. An App Link needs a domain
     * lugu controls and a `assetlinks.json` served from it, and lugu is an app for
     * somebody else's server — there is no such domain. The trade is that another app can
     * register the same scheme and intercept the redirect; PKCE is what makes that safe,
     * because the `code` is worthless without the verifier, which never leaves this
     * process.
     */
    const val LUGU_REDIRECT_URI: String = "lugu://oauth"

    /** Only S256 is accepted by the server. It rejects anything else outright. */
    private const val CHALLENGE_METHOD = "S256"

    private const val VERIFIER_BYTES = 64

    /**
     * One attempt at signing in, held between the two halves of the flow.
     *
     * [codeVerifier] never leaves the device and is the whole of the security here.
     * [cookies] are the server's session, which the final call cannot do without.
     */
    data class Attempt(
        val authorizationUrl: String,
        val state: String,
        val codeVerifier: String,
        val cookies: List<String>,
    )

    /** What the provider sent back on lugu's own redirect. */
    sealed interface Redirect {
        data class Code(val code: String, val state: String) : Redirect

        /** The provider or the server refused, and said why on the query string. */
        data class Failed(val error: String) : Redirect
    }

    @OptIn(ExperimentalEncodingApi::class)
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /**
     * A fresh PKCE verifier.
     *
     * 64 random bytes, base64url encoded, which lands inside RFC 7636's 43-to-128
     * character range with room to spare. [SecureRandom] rather than [kotlin.random.Random]
     * because this value is the only thing standing between an intercepted `code` and
     * somebody else's library.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun newVerifier(random: SecureRandom = SecureRandom()): String =
        base64Url.encode(ByteArray(VERIFIER_BYTES).also(random::nextBytes))

    /** A fresh `state`, which is what ties a redirect back to the attempt that started it. */
    @OptIn(ExperimentalEncodingApi::class)
    fun newState(random: SecureRandom = SecureRandom()): String =
        base64Url.encode(ByteArray(16).also(random::nextBytes))

    /** `BASE64URL(SHA256(ASCII(verifier)))`, which is what `S256` means. */
    @OptIn(ExperimentalEncodingApi::class)
    fun challengeFor(verifier: String): String =
        base64Url.encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /**
     * The `/auth/openid` URL for step 1.
     *
     * `response_type=code` is what puts the server into its mobile flow. Two other things
     * do as well — a `redirect_uri` or a `code_challenge` — and all three are sent anyway,
     * so the flow does not depend on which of them the server happens to check first.
     */
    fun startUrl(
        baseUrl: String,
        state: String,
        challenge: String,
        redirectUri: String = LUGU_REDIRECT_URI,
    ): String = buildString {
        append(baseUrl.trimEnd('/'))
        append("/auth/openid")
        append("?response_type=code")
        append("&client_id=lugu")
        append("&redirect_uri=").append(urlEncode(redirectUri))
        append("&code_challenge=").append(urlEncode(challenge))
        append("&code_challenge_method=").append(CHALLENGE_METHOD)
        append("&state=").append(urlEncode(state))
    }

    /** The `/auth/openid/callback` URL for step 4. */
    fun callbackUrl(baseUrl: String, code: String, state: String, verifier: String): String = buildString {
        append(baseUrl.trimEnd('/'))
        append("/auth/openid/callback")
        append("?code=").append(urlEncode(code))
        append("&state=").append(urlEncode(state))
        append("&code_verifier=").append(urlEncode(verifier))
    }

    /**
     * Reads what came back on `lugu://oauth`.
     *
     * **The state is checked against the attempt, and a mismatch is refused.** That check
     * is the reason `state` exists: without it, anything able to open a `lugu://` URL
     * could hand this app a `code` of its own and have it exchanged. A redirect that
     * arrives with no attempt in progress is refused for the same reason.
     */
    fun readRedirect(uri: String, attempt: Attempt?): Redirect {
        val query = uri.substringAfter('?', missingDelimiterValue = "")
        val params = query.split('&')
            .mapNotNull { pair ->
                val name = pair.substringBefore('=', missingDelimiterValue = "")
                if (name.isEmpty()) null else name to urlDecode(pair.substringAfter('=', ""))
            }
            .toMap()

        params["error"]?.let { return Redirect.Failed(it) }

        val code = params["code"]
        val state = params["state"]
        return when {
            code.isNullOrEmpty() -> Redirect.Failed("The sign-in came back with no code")
            attempt == null -> Redirect.Failed("That sign-in did not start in this app")
            state != attempt.state -> Redirect.Failed("That sign-in did not start in this app")
            else -> Redirect.Code(code = code, state = state)
        }
    }

    /**
     * The cookies to send back, as one `Cookie` header value.
     *
     * Only the name and the value of each. Everything else a `Set-Cookie` carries —
     * `Path`, `Max-Age`, `HttpOnly` — is an instruction to a browser about storing it, and
     * sending any of it back would make the header invalid.
     */
    fun cookieHeader(setCookies: List<String>): String =
        setCookies.mapNotNull { it.substringBefore(';').trim().takeIf { pair -> pair.contains('=') } }
            .joinToString("; ")

    /**
     * Percent-encoding, written out rather than borrowed.
     *
     * `java.net.URLEncoder` is form encoding, not URL encoding: it turns a space into `+`
     * and leaves `*` alone. A `+` inside a `code_challenge` is a different challenge, and
     * the sign-in then fails at the provider with nothing to say why.
     */
    internal fun urlEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val char = byte.toInt().toChar()
            if (char.isUnreserved()) append(char) else append('%').append("%02X".format(byte))
        }
    }

    internal fun urlDecode(value: String): String {
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '%' && index + 2 < value.length -> {
                    val hex = value.substring(index + 1, index + 3).toIntOrNull(16)
                    if (hex == null) {
                        bytes += char.code.toByte()
                        index += 1
                    } else {
                        bytes += hex.toByte()
                        index += 3
                    }
                }
                // A literal plus is a plus. This is a URL, not a form body.
                else -> {
                    bytes += char.code.toByte()
                    index += 1
                }
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun Char.isUnreserved(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-_.~"
}
