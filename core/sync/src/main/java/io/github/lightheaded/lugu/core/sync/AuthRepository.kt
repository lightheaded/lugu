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
)

@Singleton
class AuthRepository @Inject constructor(
    private val client: AbsClient,
    private val serverDao: ServerDao,
    private val tokenStore: TokenStore,
    private val progressRepository: ProgressRepository,
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
        val server = ServerEntity(
            // One row per (server, user): signing in as a different user on the same
            // server is a different account, not an overwrite.
            serverId = "${url}#${result.userId}",
            baseUrl = url,
            userId = result.userId,
            username = result.username,
            defaultLibraryId = result.defaultLibraryId,
            serverVersion = status.serverVersion,
            isActive = true,
        )
        serverDao.setActive(server)

        // The login response already carries every progress row; seeding Room from it
        // means the first library screen has continue-listening without another call.
        progressRepository.seedFromServer(server.toAccount(), result.progress)

        server.toAccount()
    }

    suspend fun logout() {
        val active = serverDao.active()
        runCatching { client.send("/logout", io.ktor.http.HttpMethod.Post) }
        tokenStore.clear()
        active?.let { serverDao.delete(it.serverId) }
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
)
