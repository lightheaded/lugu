package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.AbsOidc
import io.github.lightheaded.lugu.core.api.AuthExpiredException
import io.github.lightheaded.lugu.core.api.LoginResult
import io.github.lightheaded.lugu.core.api.ServerUrl
import io.github.lightheaded.lugu.core.db.AccountDataDao
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

private data class PendingOidc(val baseUrl: String, val attempt: AbsOidc.Attempt)

/** One stored account, and whether it can still reach its server without a new password. */
data class StoredAccount(
    val account: ActiveAccount,
    val isActive: Boolean,
    val isSignedIn: Boolean,
)

@Singleton
class AuthRepository @Inject constructor(
    private val client: AbsClient,
    private val serverDao: ServerDao,
    private val accountDataDao: AccountDataDao,
    private val tokenStore: AccountTokenStore,
    private val progressRepository: ProgressRepository,
    private val serverUrlProvider: ActiveServerUrlProvider,
) {
    /**
     * The identity-provider sign-in in progress, if any.
     *
     * In memory only, and deliberately: it holds the PKCE verifier, which is the single
     * secret that makes the whole flow safe. Writing it to storage so that it survived a
     * process death would put it on disk to save one tap.
     */
    @Volatile
    private var pendingOidc: PendingOidc? = null

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

        val serverId = "${url}#${result.userId}"
        // Stored under the account it belongs to, before that account is made active.
        // `save` resolves the active account at write time, and at this moment the active
        // account is either nothing at all or the one being signed in *beside*, so it
        // would put these tokens on the wrong account. See EncryptedTokenStore.saveFor.
        tokenStore.saveFor(serverId, result.tokens)
        // Signing in again on the same account must not throw away a second address that
        // was already configured: a re-login after an expired refresh token is routine.
        // Looked up by id and not through `active()`, because with more than one account
        // the one being signed into is usually not the active one.
        val existingLan = serverDao.byId(serverId)?.lanBaseUrl
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
     * Writes the account row for a sign-in that has already succeeded.
     *
     * Shared by the password route and the identity-provider route, because what happens
     * after a `LoginResult` arrives does not depend on how it was earned.
     */
    private suspend fun adoptSignedInUser(url: String, result: LoginResult): ActiveAccount {
        val serverId = "${url}#${result.userId}"
        tokenStore.saveFor(serverId, result.tokens)
        val existing = serverDao.byId(serverId)
        val server = ServerEntity(
            serverId = serverId,
            baseUrl = url,
            userId = result.userId,
            username = result.username,
            defaultLibraryId = result.defaultLibraryId,
            serverVersion = existing?.serverVersion,
            isActive = true,
            lanBaseUrl = existing?.lanBaseUrl,
        )
        serverDao.setActive(server)
        progressRepository.seedFromServer(server.toAccount(), result.progress)
        return server.toAccount()
    }

    /**
     * Starts an identity-provider sign-in, and answers with the page to open in a browser.
     *
     * The attempt is held in memory until the redirect comes back. **A process death in
     * between loses it, and the redirect is then refused.** That is the safe direction: the
     * attempt holds the PKCE verifier, and without the verifier the code cannot be
     * exchanged by anybody, including lugu. The person taps the button again.
     *
     * One attempt at a time. A second start replaces the first, so a redirect belonging to
     * an abandoned attempt no longer matches and is refused by its state.
     */
    suspend fun beginOidcSignIn(rawUrl: String): Result<AbsOidc.Attempt> = runCatching {
        val url = ServerUrl.normalise(rawUrl) ?: error("That does not look like a server address")
        // Probed first for the same reason a password sign-in is: a typo has to read as
        // "that is not an Audiobookshelf server" rather than as an OpenID failure.
        client.status(url)
        val attempt = client.beginOidc(url)
        pendingOidc = PendingOidc(baseUrl = url, attempt = attempt)
        attempt
    }

    /**
     * Finishes the sign-in from whatever came back on `lugu://oauth`.
     *
     * Everything after the state check is the password sign-in's own path: the same
     * `LoginResult`, the same server row, the same progress seed. Two sign-in routes that
     * ended in two different pieces of code would be two places for the account row to be
     * wrong, and only one of them could ever be exercised here.
     */
    suspend fun completeOidcSignIn(redirectUri: String): Result<ActiveAccount> = runCatching {
        val pending = pendingOidc ?: error("That sign-in did not start in this app")
        when (val redirect = AbsOidc.readRedirect(redirectUri, pending.attempt)) {
            is AbsOidc.Redirect.Failed -> error(redirect.error)
            is AbsOidc.Redirect.Code -> {
                val result = client.completeOidc(pending.baseUrl, pending.attempt, redirect.code)
                pendingOidc = null
                adoptSignedInUser(pending.baseUrl, result)
            }
        }
    }

    /** Called when a sign-in is abandoned, so a stale attempt cannot be completed later. */
    fun forgetOidcAttempt() {
        pendingOidc = null
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
        active?.let {
            tokenStore.clearFor(it.serverId)
            accountDataDao.purge(it.serverId)
            serverDao.delete(it.serverId)
        }
        // The custom headers deliberately survive a sign-out. They are a property of the
        // address rather than of the account, and behind an identity-aware proxy they are
        // what makes signing back in possible at all — clearing them here would strand
        // somebody on a login screen that cannot reach the server. The connection screen
        // deletes them explicitly instead, which is a decision rather than a side effect.
        serverUrlProvider.forgetAddressDecision()
    }

    /**
     * Every account on this device, active one first.
     *
     * [StoredAccount.isSignedIn] is read from the token store rather than assumed from the
     * row. A row with no tokens is a real state: the refresh token expires after thirty
     * days, and encrypted storage can be rebuilt by a device restore without the row
     * going anywhere. An accounts list that showed such a row as signed in would send
     * somebody to a library that cannot load.
     */
    fun observeAccounts(): Flow<List<StoredAccount>> = serverDao.observeAll().map { rows ->
        rows.map { server ->
            StoredAccount(
                account = server.toAccount(),
                isActive = server.isActive,
                isSignedIn = tokenStore.tokensFor(server.serverId) != null,
            )
        }
    }

    /**
     * Makes a stored account the active one.
     *
     * Nothing is fetched and nothing is deleted. Every user-scoped row is keyed by server
     * id, so the whole UI follows the active row on its own — which is the change schema
     * v1 was shaped for. A switch to an account whose sign-in has lapsed still succeeds:
     * it lands on that account with a message asking for the password, which is a better
     * answer than refusing to switch and leaving no way to fix it.
     */
    suspend fun switchTo(serverId: String): Result<ActiveAccount> = runCatching {
        val server = serverDao.byId(serverId) ?: error("That account is not on this device")
        serverDao.activate(serverId)
        // The remembered address decision belonged to the previous account.
        serverUrlProvider.forgetAddressDecision()
        server.toAccount()
    }

    /**
     * Signs out of one account and leaves the others alone.
     *
     * **The caller must remove that account's downloads first**, through
     * `DownloadRepository.removeAllFor`. `:core:sync` cannot reach `:core:download`, and
     * inverting that dependency for a sign-out would be the wrong repair. Bytes left
     * behind here are unreachable and still counted against the storage cap, so the
     * accounts screen does that step before this one.
     *
     * Signing out of the active account leaves no account active. That is deliberate: the
     * alternative is to promote another one silently, and a person who signs out expects
     * to be asked where to go next rather than to land in a different library.
     */
    suspend fun signOutOf(serverId: String): Result<Unit> = runCatching {
        val server = serverDao.byId(serverId) ?: return@runCatching
        // Only tell the server when it is the one this client is pointed at. A logout call
        // aimed at the active account would end the wrong session.
        if (server.isActive) {
            runCatching { client.send("/logout", io.ktor.http.HttpMethod.Post) }
        }
        tokenStore.clearFor(serverId)
        accountDataDao.purge(serverId)
        serverDao.delete(serverId)
        if (server.isActive) serverUrlProvider.forgetAddressDecision()
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
