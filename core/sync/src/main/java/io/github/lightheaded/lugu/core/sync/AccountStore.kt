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
@Singleton
class EncryptedTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
    losses: CredentialLossReport = CredentialLossReport(),
) : TokenStore {

    private val mutex = Mutex()

    private val prefs = SecurePrefs(context, FILE_NAME, CredentialKind.Tokens, losses)

    /**
     * The stored tokens, or null.
     *
     * Null covers two different states — never signed in, and stored but unreadable — and
     * that is deliberate: every caller treats both as "ask for a password". Which of the
     * two it was comes from [CredentialLossReport], not from here.
     */
    override suspend fun tokens(): AuthTokens? = mutex.withLock {
        prefs.read<AuthTokens?>(null) { store ->
            val access = store.getString(KEY_ACCESS, null)
            if (access == null) {
                null
            } else {
                AuthTokens(
                    accessToken = access,
                    refreshToken = store.getString(KEY_REFRESH, null),
                    accessTokenExpiresAtMs = store.getLong(KEY_EXPIRES, 0L),
                )
            }
        }
    }

    override suspend fun save(tokens: AuthTokens) = mutex.withLock {
        prefs.write {
            putString(KEY_ACCESS, tokens.accessToken)
            putString(KEY_REFRESH, tokens.refreshToken)
            putLong(KEY_EXPIRES, tokens.accessTokenExpiresAtMs)
        }
        Unit
    }

    override suspend fun clear() = mutex.withLock {
        prefs.write {
            remove(KEY_ACCESS)
            remove(KEY_REFRESH)
            remove(KEY_EXPIRES)
        }
        Unit
    }

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
