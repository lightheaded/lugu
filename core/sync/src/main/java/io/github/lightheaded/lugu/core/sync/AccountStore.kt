package io.github.lightheaded.lugu.core.sync

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
 * Credentials at rest.
 *
 * Refresh tokens are valid for 30 days, so they get encrypted storage rather than
 * plain preferences. Non-secret state (which server is active) lives in Room instead,
 * because the UI needs to observe it.
 */
@Singleton
class EncryptedTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : TokenStore {

    private val mutex = Mutex()

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun tokens(): AuthTokens? = mutex.withLock {
        val access = prefs.getString(KEY_ACCESS, null) ?: return@withLock null
        AuthTokens(
            accessToken = access,
            refreshToken = prefs.getString(KEY_REFRESH, null),
            accessTokenExpiresAtMs = prefs.getLong(KEY_EXPIRES, 0L),
        )
    }

    override suspend fun save(tokens: AuthTokens) = mutex.withLock {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putLong(KEY_EXPIRES, tokens.accessTokenExpiresAtMs)
            .commit()
        Unit
    }

    override suspend fun clear() = mutex.withLock {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES).commit()
        Unit
    }

    /**
     * Stable per-install id, reported to the server as the device identity so its
     * session list stays meaningful. Not derived from any hardware identifier.
     */
    fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).commit()
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
