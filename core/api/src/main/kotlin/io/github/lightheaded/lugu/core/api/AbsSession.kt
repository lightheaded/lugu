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
        val scheme = value.substringBefore("://")
        if (scheme != "http" && scheme != "https") return null
        if (value.substringAfter("://").isBlank()) return null
        return value
    }
}

/** Raised when the server rejects our credentials and a fresh login is required. */
class AuthExpiredException(message: String = "Session expired") : Exception(message)

/** Raised for non-2xx responses that are not an auth problem. */
class AbsHttpException(val status: Int, message: String) : Exception(message)
