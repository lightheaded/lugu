package io.github.lightheaded.lugu.core.api

import io.github.lightheaded.lugu.core.model.AuthTokens

/**
 * Where the client reads and writes credentials. Implemented on encrypted storage in `:core:sync`.
 *
 * [tokens], [save] and [clear] mean "the active account", whichever that is at the moment of
 * the call. That is right for a single read and wrong for a refresh, which is a read, a
 * network call, and then a write: the active account can change in between. So the refresh
 * uses [read], [replace] and [discard] instead. [read] returns a [StoredTokens] that names
 * the slot it came from, and the other two act on that slot only, and only while it still
 * holds what [read] found.
 *
 * The slot is opaque here. `:core:api` knows nothing about servers or accounts, and it does
 * not need to: it only gives the slot back to the store that made it.
 */
interface TokenStore {
    suspend fun tokens(): AuthTokens?

    suspend fun save(tokens: AuthTokens)

    suspend fun clear()

    /** The active account's tokens, with the slot they came from, or null. */
    suspend fun read(): StoredTokens? = tokens()?.let { StoredTokens(it, slot = null) }

    /**
     * Writes [tokens] into the slot that [previous] came from, if that slot still holds
     * [previous]. Returns false and writes nothing otherwise.
     *
     * "Still holds" is what keeps an ended sign-in ended. If the account signed out while
     * the refresh was in flight, its slot is empty, and a write here would sign it back in.
     * If the account signed in again, its slot holds newer tokens than these.
     *
     * The default suits a store with one slot. A store with one slot per account overrides
     * it, and must do the comparison and the write as one step.
     */
    suspend fun replace(previous: StoredTokens, tokens: AuthTokens): Boolean {
        if (tokens() != previous.tokens) return false
        save(tokens)
        return true
    }

    /**
     * Empties the slot that [previous] came from, if that slot still holds [previous].
     * Returns whether it did. The client calls this when the server rejects a refresh.
     */
    suspend fun discard(previous: StoredTokens): Boolean {
        if (tokens() != previous.tokens) return false
        clear()
        return true
    }
}

/**
 * Tokens as a [TokenStore] read them, and the slot they came from.
 *
 * [slot] means something only to the store that made it. A store with one slot uses null.
 */
data class StoredTokens(val tokens: AuthTokens, val slot: String?)

/** In-memory [TokenStore], used by tests and as a default before storage is wired up. */
class InMemoryTokenStore(initial: AuthTokens? = null) : TokenStore {
    private val lock = Any()

    @Volatile private var current: AuthTokens? = initial

    override suspend fun tokens(): AuthTokens? = current

    override suspend fun save(tokens: AuthTokens) {
        current = tokens
    }

    override suspend fun clear() {
        current = null
    }

    override suspend fun replace(previous: StoredTokens, tokens: AuthTokens): Boolean = synchronized(lock) {
        if (current != previous.tokens) return false
        current = tokens
        true
    }

    override suspend fun discard(previous: StoredTokens): Boolean = synchronized(lock) {
        if (current != previous.tokens) return false
        current = null
        true
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
