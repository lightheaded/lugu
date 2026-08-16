package io.github.lightheaded.lugu.core.api

/**
 * One custom request header, sent with every request to the server it was entered for.
 *
 * The reason this type exists at all is Cloudflare Access and the other identity-aware
 * proxies: they reject a request that does not carry `CF-Access-Client-Id` and
 * `CF-Access-Client-Secret`, and no amount of correct Audiobookshelf credentials gets
 * past them. A header that is only attached to the API calls and not to the audio would
 * produce a library that browses and a book that will not play, so the same list is
 * applied by both [AbsClient] and [AuthInterceptor].
 *
 * A header value is a credential. It is stored encrypted, never written to Room, and the
 * generated `toString` is replaced below rather than trusted — a data class prints its
 * fields, and printed fields end up in exception messages and crash reports by accident
 * rather than by decision.
 */
data class ConnectionHeader(val name: String, val value: String) {

    /** What the screen shows until somebody asks to see it. Fixed width, so it leaks no length. */
    val maskedValue: String
        get() = if (value.isEmpty()) "" else MASK

    override fun toString(): String = "ConnectionHeader($name)"

    private companion object {
        const val MASK = "••••••••"
    }
}

/**
 * What the HTTP layer needs to know about one server: which addresses are it, and what
 * has to be sent with every request to them.
 *
 * [addresses] holds both the address the account was created with and the local-network
 * address, because a media or cover URL may have been built from either one — download
 * manifests, for instance, keep the address that was current when the download was
 * queued. Matching on only the address in force would silently drop the token, and
 * therefore the headers, from requests that were built from the other one.
 */
data class ConnectionProfile(
    val addresses: List<String>,
    val headers: List<ConnectionHeader> = emptyList(),
) {
    fun matches(url: String): Boolean = addresses.any { it.isNotEmpty() && url.startsWith(it) }

    /** Counts rather than contents: neither an address nor a header value may be printed. */
    override fun toString(): String =
        "ConnectionProfile(addresses=${addresses.size}, headers=${headers.size})"

    companion object {
        val EMPTY = ConnectionProfile(addresses = emptyList())
    }
}

/**
 * Where the HTTP layer reads the per-server connection settings.
 *
 * A URL rather than a server id, because the two callers only ever have a URL: the
 * interceptor sees an outgoing OkHttp request, and [AbsClient] probes and signs in
 * against an address that has no account behind it yet. That second case is the point —
 * somebody behind an identity-aware proxy cannot reach `/status`, let alone `/login`,
 * without their headers, so the headers have to be resolvable before the first
 * successful sign-in.
 *
 * Returns null when the URL belongs to nothing lugu is configured for, which is the
 * signal to send it neither headers nor a token.
 */
fun interface ConnectionProfileSource {
    suspend fun profileFor(url: String): ConnectionProfile?
}

/**
 * The fallback source: the configured base URL and nothing else.
 *
 * It exists so that a [ServerUrlProvider] which knows nothing about connection settings
 * — the in-memory one used by tests, or any future implementation — keeps exactly the
 * behaviour the auth interceptor had before custom headers existed, rather than
 * silently losing its token.
 */
class ServerUrlProfileSource(private val provider: ServerUrlProvider) : ConnectionProfileSource {
    override suspend fun profileFor(url: String): ConnectionProfile? {
        val base = provider.baseUrl() ?: return null
        return if (url.startsWith(base)) ConnectionProfile(addresses = listOf(base)) else null
    }
}

/**
 * What a header is allowed to be.
 *
 * Validation is not tidiness here. A newline in a value is a request-splitting attack on
 * the user's own proxy, and OkHttp throws on a name or value it considers illegal — from
 * a background thread, mid-playback, where the exception reads as "the book is broken"
 * rather than "that header is malformed". Both are better caught in the text field.
 */
object ConnectionHeaders {

    /**
     * Names lugu sets itself. Letting one be overridden would mean a typo in this screen
     * could strip the bearer token off every request, which looks exactly like an expired
     * session and is very hard to connect back to a setting.
     */
    private val RESERVED = setOf("authorization", "host", "content-length", "connection")

    private val NAME = Regex("""[!#$%&'*+\-.^_`|~0-9A-Za-z]+""")

    /** Human-readable reason this pair cannot be used, or null when it can. */
    fun problemWith(name: String, value: String): String? = when {
        name.isBlank() -> "Give the header a name."
        !NAME.matches(name) -> "A header name can only contain letters, digits and - _ . symbols."
        name.lowercase() in RESERVED -> "lugu sets ${name.trim()} itself, so it cannot be overridden."
        value.any { it == '\n' || it == '\r' } -> "A header value cannot contain a line break."
        value.any { it.code < 0x20 || it.code > 0x7E } ->
            "A header value can only contain plain ASCII characters."
        else -> null
    }

    /**
     * Later entries win, matching how a person reads a list they have edited: the one
     * further down is the one they most recently meant.
     */
    fun deduplicate(headers: List<ConnectionHeader>): List<ConnectionHeader> =
        headers.associateBy { it.name.lowercase() }.values.toList()
}
