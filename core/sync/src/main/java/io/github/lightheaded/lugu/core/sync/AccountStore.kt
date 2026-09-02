package io.github.lightheaded.lugu.core.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.api.ConnectionProfile
import io.github.lightheaded.lugu.core.api.ConnectionProfileSource
import io.github.lightheaded.lugu.core.api.ConnectionRace
import io.github.lightheaded.lugu.core.api.ServerUrlProvider
import io.github.lightheaded.lugu.core.api.TokenStore
import io.github.lightheaded.lugu.core.db.ServerDao
import io.github.lightheaded.lugu.core.model.AuthTokens
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Credentials at rest: the access token, the refresh token, and the expiry.
 *
 * Refresh tokens are valid for 30 days, so they get encrypted storage rather than
 * plain preferences. Non-secret state (which server is active) lives in Room instead,
 * because the UI needs to observe it.
 *
 * ## Why this still uses a deprecated library
 *
 * `androidx.security:security-crypto` is deprecated as a whole. Version 1.1.0 is the last
 * stable release, and this project holds it. There is no successor in AndroidX. The
 * artifact on disk carries `java.lang.Deprecated` on `EncryptedSharedPreferences` and on
 * `MasterKey` themselves, not on a method or two, so no version bump ends this.
 *
 * The decision is **keep it**, and make the failure path correct instead.
 *
 * **Trigger that forces a change.** Deprecated is not removed, and an `.aar` already in the
 * Gradle cache keeps working. Two events end that, and either one is the work item:
 * 1. A future `compileSdk` breaks the library at runtime. The library wraps a Tink keyset
 *    with an `AndroidKeyStore` AES key, so a keystore behaviour change is what to watch.
 * 2. The transitive `com.google.crypto.tink:tink-android` 1.8.0 gets an advisory. lugu does
 *    not pin it, so the fix would be a pin or a move.
 *
 * When either happens, the app must convert on the next launch: read through this class,
 * write through the replacement, and delete the old file only after the write succeeds. A
 * conversion that starts and stops loses a signed-in session on upgrade, which is worse
 * than the deprecation.
 *
 * ## Options rejected
 *
 * **An `AndroidKeyStore` key, and encryption by the app itself.** Rejected for now, not
 * forever. It is the eventual answer, and it moves four things into this app that the
 * library does today: key generation, a fresh initialization vector for every write, key
 * rotation, and the invalidated-key case. The last one is the reason to wait. A backup
 * restore or a lock-screen change throws `KeyPermanentlyInvalidatedException` on the first
 * decryption, and that path has to be right before the migration, not after it. So it is
 * built first, in [SecurePrefs] and [CredentialStoreRepair], under the library that is
 * here now. The migration then lands on a failure path that already works.
 *
 * **Store nothing that needs encryption.** Rejected. The refresh token is valid for 30
 * days and reaches the whole library, so a plain file would hand a 30-day session to
 * anything that reads app storage on a rooted or backed-up device. The sign-in screen does
 * warn that a plain-HTTP address carries the password in the clear, and that warning is
 * about one moment on one network. A token in a plain file is every moment. Worse, this
 * store also holds the connection headers and the client certificate password, which no
 * listener can re-enter from memory.
 *
 * **A different library.** Rejected on evidence, not on preference. Tink is the only
 * candidate on this machine, and only `tink-android` 1.8.0 is here, pulled in transitively.
 * That is not the latest stable, and AGENTS.md forbids a dependency below latest stable.
 * A version that cannot be read cannot be checked, so the swap is not takeable this round.
 *
 * ## What a decrypt failure must not do
 *
 * It must not crash, and it must not look like a plain sign-out. Both were true before:
 * `tokens()` threw straight out of a lazy initializer, `AuthRepository.isSignedIn` called
 * it, and `StartupViewModel` called that from an unguarded coroutine — a crash on the
 * splash screen, on every launch, with no launch able to clear it. [SecurePrefs] answers
 * with a fallback instead, and reports the loss to [CredentialLossReport] so the login
 * screen can say why it asks.
 */
/**
 * A [TokenStore] that can name the account a sign-in belongs to.
 *
 * `:core:api` deliberately knows nothing about servers, so [TokenStore] has no account in
 * its contract and its three methods mean "the active account". Everything that has to
 * reach a *particular* account — sign-in, which stores tokens before that account is
 * active, and the accounts screen, which asks whether each stored account can still reach
 * its server — needs this instead.
 *
 * An interface rather than the concrete class because the concrete class is encrypted
 * storage: it opens an `AndroidKeyStore` that Robolectric does not have, so anything
 * depending on the class itself cannot be tested off a device. See [InMemoryAccountTokens].
 */
interface AccountTokenStore : TokenStore {

    /** One account's tokens, whether or not it is the active one. */
    suspend fun tokensFor(serverId: String): AuthTokens?

    /** Writes one account's tokens by name. Sign-in must use this rather than `save`. */
    suspend fun saveFor(serverId: String, tokens: AuthTokens)

    /** Forgets one account's sign-in and leaves every other account signed in. */
    suspend fun clearFor(serverId: String)
}

/**
 * [AccountTokenStore] in memory, for tests and for screens photographed without a device.
 *
 * It keeps the same shape as the real one — `save` and `clear` act on whichever account
 * the caller last named — but it holds nothing at rest and encrypts nothing.
 */
class InMemoryAccountTokens(private val activeServerId: String? = null) : AccountTokenStore {

    private val slots = mutableMapOf<String, AuthTokens>()

    override suspend fun tokens(): AuthTokens? = activeServerId?.let { slots[it] }

    override suspend fun tokensFor(serverId: String): AuthTokens? = slots[serverId]

    override suspend fun save(tokens: AuthTokens) {
        activeServerId?.let { slots[it] = tokens }
    }

    override suspend fun saveFor(serverId: String, tokens: AuthTokens) {
        slots[serverId] = tokens
    }

    override suspend fun clear() {
        activeServerId?.let { slots.remove(it) }
    }

    override suspend fun clearFor(serverId: String) {
        slots.remove(serverId)
    }
}

@Singleton
class EncryptedTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverDao: ServerDao,
    losses: CredentialLossReport = CredentialLossReport(),
) : AccountTokenStore {

    private val mutex = Mutex()

    private val prefs = SecurePrefs(context, FILE_NAME, CredentialKind.Tokens, losses)

    /**
     * The active account's tokens, or null.
     *
     * Null covers three states — never signed in, no account active, and stored but
     * unreadable — and that is deliberate: every caller treats all three as "ask for a
     * password". Which of them it was comes from [CredentialLossReport] and from
     * [ServerDao], not from here.
     */
    override suspend fun tokens(): AuthTokens? {
        val serverId = serverDao.active()?.serverId ?: return null
        return tokensFor(serverId)
    }

    /**
     * One account's tokens, whether or not it is the active one.
     *
     * Needed by the accounts screen, which has to say which of several accounts still
     * holds a usable sign-in without switching to each of them to find out.
     */
    override suspend fun tokensFor(serverId: String): AuthTokens? = mutex.withLock {
        readWhileLocked(serverId) ?: adoptLegacyWhileLocked(serverId)
    }

    /**
     * Writes the active account's tokens.
     *
     * **A refresh that lands during an account switch writes to the wrong account.** The
     * active account is resolved here, at write time, and a token refresh is a read
     * followed by a write. If the switch happens between the two, account A's tokens go
     * into account B's slot. The cost is bounded: B asks for a password again, A keeps
     * working, and nothing crosses between them in Room, where every row is keyed by
     * server id. Nothing is lost and nothing leaks.
     *
     * It is not fixed here because the fix is not here. The request would have to carry
     * the account it belongs to, which is a `:core:api` change — [TokenStore] has no
     * account in its contract, by design, because `:core:api` knows nothing about servers.
     * Sign-in uses [saveFor] and never this, so the only writer that can race is the
     * refresh, and only in the instant a person taps a different account.
     */
    override suspend fun save(tokens: AuthTokens) {
        val serverId = serverDao.active()?.serverId ?: return
        saveFor(serverId, tokens)
    }

    /**
     * Writes one account's tokens by name.
     *
     * Sign-in must use this rather than [save]. At the moment a first sign-in stores its
     * tokens there is no active account yet, and at the moment a second sign-in stores
     * its tokens the active account is still the previous one.
     */
    override suspend fun saveFor(serverId: String, tokens: AuthTokens) = mutex.withLock {
        prefs.write {
            putString(serverId.key(KEY_ACCESS), tokens.accessToken)
            putString(serverId.key(KEY_REFRESH), tokens.refreshToken)
            putLong(serverId.key(KEY_EXPIRES), tokens.accessTokenExpiresAtMs)
        }
        Unit
    }

    override suspend fun clear() {
        val serverId = serverDao.active()?.serverId ?: return
        clearFor(serverId)
    }

    /**
     * Forgets one account's sign-in and leaves every other account signed in.
     *
     * The legacy keys go too. They belong to whichever account was the only one before
     * this store learned to hold several, so leaving them would let a signed-out account
     * be adopted back by [adoptLegacyWhileLocked] on the next launch.
     */
    override suspend fun clearFor(serverId: String) = mutex.withLock {
        prefs.write {
            remove(serverId.key(KEY_ACCESS))
            remove(serverId.key(KEY_REFRESH))
            remove(serverId.key(KEY_EXPIRES))
            remove(KEY_ACCESS)
            remove(KEY_REFRESH)
            remove(KEY_EXPIRES)
        }
        Unit
    }

    private fun readWhileLocked(serverId: String): AuthTokens? =
        prefs.read<AuthTokens?>(null) { store ->
            val access = store.getString(serverId.key(KEY_ACCESS), null) ?: return@read null
            AuthTokens(
                accessToken = access,
                refreshToken = store.getString(serverId.key(KEY_REFRESH), null),
                accessTokenExpiresAtMs = store.getLong(serverId.key(KEY_EXPIRES), 0L),
            )
        }

    /**
     * Moves an install that predates per-account storage onto its account's keys.
     *
     * This is the upgrade path, and it is the one thing in this class that must not go
     * wrong: getting it wrong signs somebody out on update, which is exactly the failure
     * this class's own KDoc warns about. So the old value is read, written under the new
     * key, and only then removed — and the removal is skipped when the write did not land,
     * because a session kept under an old key beats a session deleted from under both.
     *
     * It runs at most once per install. After it, there are no legacy keys to find.
     */
    private fun adoptLegacyWhileLocked(serverId: String): AuthTokens? = LegacyTokenAdoption.adopt(
        legacy = prefs.read<AuthTokens?>(null) { store ->
            val access = store.getString(KEY_ACCESS, null) ?: return@read null
            AuthTokens(
                accessToken = access,
                refreshToken = store.getString(KEY_REFRESH, null),
                accessTokenExpiresAtMs = store.getLong(KEY_EXPIRES, 0L),
            )
        },
        write = { tokens ->
            prefs.write {
                putString(serverId.key(KEY_ACCESS), tokens.accessToken)
                putString(serverId.key(KEY_REFRESH), tokens.refreshToken)
                putLong(serverId.key(KEY_EXPIRES), tokens.accessTokenExpiresAtMs)
            }
        },
        removeLegacy = {
            prefs.write {
                remove(KEY_ACCESS)
                remove(KEY_REFRESH)
                remove(KEY_EXPIRES)
            }
        },
    )

    /**
     * One account's name for a stored value.
     *
     * The server id already reads `<address>#<user id>`, so it is unique on its own and
     * needs no hashing. The separator only has to be something an id cannot contain a
     * lone copy of, and a key in this file is encrypted anyway.
     */
    private fun String.key(name: String): String = "$name@@$this"

    /**
     * Stable per-install id, reported to the server as the device identity so its
     * session list stays meaningful. Not derived from any hardware identifier.
     *
     * A new id comes back when the store cannot be written. The server session list then
     * shows one extra device, which is a cosmetic fault. A throw here would instead take
     * down whatever asked, so this is the better of the two.
     */
    fun deviceId(): String {
        prefs.read<String?>(null) { it.getString(KEY_DEVICE_ID, null) }?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.write { putString(KEY_DEVICE_ID, generated) }
        return generated
    }

    private companion object {
        const val FILE_NAME = "lugu_auth"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "access_expires_at"
        const val KEY_DEVICE_ID = "device_id"
    }
}

/**
 * Moves one stored value from the key an older version wrote to the key this one reads.
 *
 * Pure on purpose, and for the same reason [CredentialStoreRepair] is: the storage needs a
 * device and an `AndroidKeyStore` that Robolectric does not have, but the *order of the
 * three steps* does not, and the order is the part that can be wrong. Getting it wrong
 * signs somebody out on an app update, which is the failure `EncryptedTokenStore`'s own
 * KDoc names as worse than the deprecation it lives with.
 *
 * The rule in one line: **the old copy goes only after the new copy has landed.** A write
 * that fails leaves the value under the old key, where the next launch will find it again.
 * The opposite order loses a thirty-day session to a full disk.
 */
internal object LegacyTokenAdoption {

    /**
     * @param legacy what the old key holds, or null when there is nothing to move.
     * @param write stores it under the new key. False means nothing was written.
     * @param removeLegacy deletes the old key. Called only after [write] returns true.
     * @return [legacy] either way, because a value that could not be moved is still the
     *   value in force. Refusing to return it would sign somebody out to tidy a key.
     */
    fun <T> adopt(legacy: T?, write: (T) -> Boolean, removeLegacy: () -> Unit): T? {
        if (legacy == null) return null
        if (write(legacy)) removeLegacy()
        return legacy
    }
}

/**
 * Points the HTTP client at whichever server is currently active, and tells it what has
 * to be sent with every request to that server.
 *
 * One class for both because both callers — [io.github.lightheaded.lugu.core.api.AbsClient]
 * and the OkHttp interceptor — already hold this, and neither is constructed anywhere this
 * code can reach to add a second dependency.
 *
 * When a second address is configured, [baseUrl] is whichever of the two answered a probe
 * most recently. That is safe here for a reason worth stating: progress is keyed by server
 * id and user id, and the server id is derived once, at sign-in, from the address that was
 * typed — [AuthRepository.login] builds it and nothing else ever does. Upstream keys
 * progress by connection, which is why the same feature there splits one book's history in
 * two when the address changes (audiobookshelf-app#1401). Racing addresses would be
 * dangerous under that design and is not under this one.
 */
@Singleton
class ActiveServerUrlProvider @Inject constructor(
    private val serverDao: ServerDao,
    private val connectionPrefs: ConnectionPrefs,
    private val race: ConnectionRace,
) : ServerUrlProvider, ConnectionProfileSource {

    override suspend fun baseUrl(): String? {
        val server = serverDao.active() ?: return null
        return race.preferred(
            primary = server.baseUrl,
            lan = server.lanBaseUrl,
            headers = connectionPrefs.headers(server.baseUrl),
        )
    }

    override suspend fun profileFor(url: String): ConnectionProfile? {
        val server = serverDao.active()
        if (server != null) {
            // Both addresses count as this server. A cover or media URL may have been built
            // from either — a download manifest keeps the address that was current when it
            // was queued — and matching only the one in force would quietly drop the token
            // and the headers from requests built from the other.
            val addresses = listOfNotNull(
                server.baseUrl,
                server.lanBaseUrl?.takeIf { it.isNotBlank() && it != server.baseUrl },
            )
            if (addresses.any { url.startsWith(it) }) {
                return ConnectionProfile(addresses, connectionPrefs.headers(server.baseUrl))
            }
        }
        // Before the first sign-in there is no server row at all, and that is exactly when
        // somebody behind an identity-aware proxy needs their headers most.
        return connectionPrefs.profileFor(url)
    }

    /** Called when the addresses change, so the next request decides again rather than recalls. */
    fun forgetAddressDecision() = race.forget()
}
