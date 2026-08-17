package io.github.lightheaded.lugu.core.api

import io.github.lightheaded.lugu.core.model.AuthTokens

/** Where the client reads and writes credentials. Implemented on encrypted storage in `:core:sync`. */
interface TokenStore {
    suspend fun tokens(): AuthTokens?

    suspend fun save(tokens: AuthTokens)

    suspend fun clear()
}

/** In-memory [TokenStore], used by tests and as a default before storage is wired up. */
class InMemoryTokenStore(initial: AuthTokens? = null) : TokenStore {
    @Volatile private var current: AuthTokens? = initial

    override suspend fun tokens(): AuthTokens? = current

    override suspend fun save(tokens: AuthTokens) {
        current = tokens
    }

    override suspend fun clear() {
        current = null
    }
}

/** Where the client points. Separate from [TokenStore] so a server swap does not touch tokens. */
interface ServerUrlProvider {
    suspend fun baseUrl(): String?
}

class StaticServerUrlProvider(private val url: String?) : ServerUrlProvider {
    override suspend fun baseUrl(): String? = url
}

object ServerUrl {
    /**
     * Accept what people actually type: bare hosts, trailing slashes, a stray `/login`
     * pasted from a browser. Defaults to https when no scheme is given.
     */
    fun normalise(input: String): String? {
        var value = input.trim()
        if (value.isEmpty()) return null
        if (!value.contains("://")) value = "https://$value"
        value = value.trimEnd('/')
        value = value.removeSuffix("/login").removeSuffix("/audiobookshelf/login").trimEnd('/')
        // The scheme is case-insensitive by the URL spec and a keyboard that has just
        // auto-capitalised is the ordinary way to arrive at "Https://". Rejecting that as
        // "not a server address" would be blaming the listener for their keyboard. The
        // rest is left alone: a path can be case-sensitive and this is not the place to
        // decide that it is not.
        val scheme = value.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = value.substringAfter("://")
        if (rest.isBlank()) return null
        return "$scheme://$rest"
    }

    /**
     * Whether talking to this address means talking in the clear.
     *
     * lugu permits cleartext at the platform level, because Audiobookshelf is mostly run at
     * home over plain HTTP and refusing outright made those servers unreachable with an
     * error that blamed the server. The trade is that the app has to say so itself: this is
     * what the sign-in screen asks before it sends a password anywhere.
     *
     * A half-typed address is not yet a plain-HTTP one — nothing is claimed about something
     * that does not parse, and typing "http" on the way to "https" must not flash a warning.
     */
    fun isCleartext(input: String): Boolean = normalise(input)?.startsWith("http://") == true
}

/** Raised when the server rejects our credentials and a fresh login is required. */
class AuthExpiredException(message: String = "Session expired") : Exception(message)

/** Raised for non-2xx responses that are not an auth problem. */
class AbsHttpException(val status: Int, message: String) : Exception(message)
