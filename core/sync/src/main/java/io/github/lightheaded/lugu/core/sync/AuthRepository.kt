package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.AuthExpiredException
import io.github.lightheaded.lugu.core.api.ServerUrl
import io.github.lightheaded.lugu.core.api.TokenStore
import io.github.lightheaded.lugu.core.db.ServerDao
import io.github.lightheaded.lugu.core.db.ServerEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The signed-in account, or null. The whole UI hangs off this. */
data class ActiveAccount(
    val serverId: String,
    val baseUrl: String,
    val userId: String,
    val username: String,
    val defaultLibraryId: String?,
    /** The second address, when one is configured. Not necessarily the one in use now. */
    val lanBaseUrl: String? = null,
)

@Singleton
class AuthRepository @Inject constructor(
    private val client: AbsClient,
    private val serverDao: ServerDao,
    private val tokenStore: TokenStore,
    private val progressRepository: ProgressRepository,
    private val serverUrlProvider: ActiveServerUrlProvider,
) {
    fun observeAccount(): Flow<ActiveAccount?> = serverDao.observeActive().map { it?.toAccount() }

    suspend fun account(): ActiveAccount? = serverDao.active()?.toAccount()

    /**
     * Checks a URL before asking for credentials, so a typo reads as "that is not an
     * Audiobookshelf server" rather than "wrong password".
     */
    suspend fun probe(rawUrl: String): Result<String> = runCatching {
        val url = ServerUrl.normalise(rawUrl) ?: error("That does not look like a server address")
        client.status(url)
        url
    }

    suspend fun login(rawUrl: String, username: String, password: String): Result<ActiveAccount> = runCatching {
        val url = ServerUrl.normalise(rawUrl) ?: error("That does not look like a server address")
        // Probe first, and let its error stand. Otherwise a wrong address surfaces as
        // whatever the login endpoint happened to say — "404 page not found" from a
        // proxy reads as an app bug, and worse, as doubt about the password.
        val status = client.status(url)
        val result = client.login(url, username, password)

        tokenStore.save(result.tokens)
        val serverId = "${url}#${result.userId}"
        // Signing in again on the same account must not throw away a second address that
        // was already configured: a re-login after an expired refresh token is routine.
        val existingLan = serverDao.active()?.takeIf { it.serverId == serverId }?.lanBaseUrl
        val server = ServerEntity(
            // One row per (server, user): signing in as a different user on the same
            // server is a different account, not an overwrite. Derived from the address
            // that was typed, once, and never from whichever address a later race picked —
            // that is what makes a second address safe here.
            serverId = serverId,
            baseUrl = url,
            userId = result.userId,
            username = result.username,
            defaultLibraryId = result.defaultLibraryId,
            serverVersion = status.serverVersion,
            isActive = true,
            lanBaseUrl = existingLan,
        )
        serverDao.setActive(server)

        // The login response already carries every progress row; seeding Room from it
        // means the first library screen has continue-listening without another call.
        progressRepository.seedFromServer(server.toAccount(), result.progress)

        server.toAccount()
    }

    /**
     * Sets or clears the second address. Normalised the same way as the first, so "192.168.1.4:13378"
     * is accepted, and the remembered race result is discarded because it answered a
     * different question.
     */
    suspend fun setLanBaseUrl(rawUrl: String?): Result<String?> = runCatching {
        val server = serverDao.active() ?: error("Not signed in")
        val normalised = rawUrl?.takeIf { it.isNotBlank() }?.let {
            ServerUrl.normalise(it) ?: error("That does not look like a server address")
        }
        require(normalised != server.baseUrl) {
            "That is the address you already sign in with."
        }
        serverDao.upsert(server.copy(lanBaseUrl = normalised))
        serverUrlProvider.forgetAddressDecision()
        normalised
    }

    suspend fun logout() {
        val active = serverDao.active()
        runCatching { client.send("/logout", io.ktor.http.HttpMethod.Post) }
        tokenStore.clear()
        active?.let { serverDao.delete(it.serverId) }
        // The custom headers deliberately survive a sign-out. They are a property of the
        // address rather than of the account, and behind an identity-aware proxy they are
        // what makes signing back in possible at all — clearing them here would strand
        // somebody on a login screen that cannot reach the server. The connection screen
        // deletes them explicitly instead, which is a decision rather than a side effect.
        serverUrlProvider.forgetAddressDecision()
    }

    /** True when we hold credentials that still stand a chance of working. */
    suspend fun isSignedIn(): Boolean = serverDao.active() != null && tokenStore.tokens() != null

    suspend fun ensureUsable(): Result<Unit> = runCatching {
        if (!isSignedIn()) throw AuthExpiredException("Not signed in")
        client.validAccessToken()
        Unit
    }
}

internal fun ServerEntity.toAccount() = ActiveAccount(
    serverId = serverId,
    baseUrl = baseUrl,
    userId = userId,
    username = username,
    defaultLibraryId = defaultLibraryId,
    lanBaseUrl = lanBaseUrl,
)
