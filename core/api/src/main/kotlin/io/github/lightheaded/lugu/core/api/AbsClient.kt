package io.github.lightheaded.lugu.core.api

import io.github.lightheaded.lugu.core.model.AuthTokens
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

val AbsJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * HTTP access to one Audiobookshelf server.
 *
 * Auth is the v2.26+ JWT model: ~1h access tokens, 30d refresh tokens, no permanent
 * tokens. Refresh is *proactive* (fired when under five minutes remain) and
 * single-flight (a mutex plus a re-read inside the lock), so a burst of parallel
 * requests on app start produces one refresh, not twenty — and never a thundering
 * herd of 401s.
 */
class AbsClient(
    private val serverUrlProvider: ServerUrlProvider,
    private val tokenStore: TokenStore,
    private val deviceInfo: DeviceInfoDto,
    engineFactory: HttpClientConfig<*>.() -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val http: HttpClient = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) { json(AbsJson) }
        engineFactory()
    },
) {
    private val refreshMutex = Mutex()

    private suspend fun requireBaseUrl(): String =
        serverUrlProvider.baseUrl() ?: throw AuthExpiredException("No server configured")

    // region auth

    /**
     * Username/password login. `x-return-tokens: true` makes the server put the refresh
     * token in the response body instead of an httpOnly cookie — the only workable
     * shape for a native client.
     */
    suspend fun login(baseUrl: String, username: String, password: String): LoginResult {
        val response = http.request("$baseUrl/login") {
            method = HttpMethod.Post
            header("x-return-tokens", "true")
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }
        if (!response.status.isSuccess()) {
            throw when (response.status.value) {
                401 -> AuthExpiredException("Wrong username or password")
                // A 404 here means something answered but it was not Audiobookshelf —
                // usually a reverse proxy with no route for that hostname. Echoing the
                // server's own body ("404 page not found") tells the user nothing.
                404 -> AbsHttpException(404, NOT_A_SERVER)
                429 -> AbsHttpException(429, "Too many sign-in attempts. Wait a few minutes.")
                else -> AbsHttpException(
                    response.status.value,
                    "The server refused the sign-in (HTTP ${response.status.value})",
                )
            }
        }
        val body: LoginResponse = response.body()
        val user = body.user ?: throw AbsHttpException(200, "Login response had no user")
        val access = user.accessToken ?: throw AbsHttpException(200, "Login response had no access token")
        return LoginResult(
            tokens = AuthTokens(
                accessToken = access,
                refreshToken = user.refreshToken,
                accessTokenExpiresAtMs = Jwt.expiresAtMs(access) ?: (nowMs() + DEFAULT_ACCESS_TTL_MS),
            ),
            userId = user.id,
            username = user.username,
            defaultLibraryId = body.userDefaultLibraryId,
            progress = user.mediaProgress,
        )
    }

    /**
     * Unauthenticated probe of `/status`, used to tell "wrong address" apart from
     * "wrong password" before the user is asked to doubt their credentials.
     */
    suspend fun status(baseUrl: String): ServerStatusDto {
        val response = try {
            http.request("$baseUrl/status") { method = HttpMethod.Get }
        } catch (e: Exception) {
            throw AbsHttpException(0, "Could not reach $baseUrl — ${e.message ?: "no response"}")
        }
        if (!response.status.isSuccess()) {
            throw AbsHttpException(response.status.value, NOT_A_SERVER)
        }
        // A proxy error page can still return 200, so parsing is not enough: a real
        // server identifies itself with app="audiobookshelf" (verified live on 2.36.0).
        val status = try {
            response.body<ServerStatusDto>()
        } catch (e: Exception) {
            throw AbsHttpException(response.status.value, NOT_A_SERVER)
        }
        if (!status.app.equals(APP_NAME, ignoreCase = true)) {
            throw AbsHttpException(response.status.value, NOT_A_SERVER)
        }
        return status
    }

    /**
     * Exchanges the refresh token for a new pair. The server reads the refresh token
     * from `x-refresh-token` when present, which also makes it return the rotated
     * refresh token in the body rather than as a cookie.
     */
    private suspend fun refreshLocked(current: AuthTokens): AuthTokens {
        val refresh = current.refreshToken ?: throw AuthExpiredException("No refresh token stored")
        val response = http.request("${requireBaseUrl()}/auth/refresh") {
            method = HttpMethod.Post
            header("x-refresh-token", refresh)
        }
        if (!response.status.isSuccess()) {
            tokenStore.clear()
            throw AuthExpiredException("Refresh rejected (${response.status.value})")
        }
        val user = response.body<RefreshResponse>().user
        val access = user?.accessToken ?: run {
            tokenStore.clear()
            throw AuthExpiredException("Refresh response had no access token")
        }
        val tokens = AuthTokens(
            accessToken = access,
            // The server rotates refresh tokens; keep the old one if it did not send a new one.
            refreshToken = user.refreshToken ?: refresh,
            accessTokenExpiresAtMs = Jwt.expiresAtMs(access) ?: (nowMs() + DEFAULT_ACCESS_TTL_MS),
        )
        tokenStore.save(tokens)
        return tokens
    }

    /** Current access token, refreshed first if it is close to expiry. */
    suspend fun validAccessToken(): String {
        val current = tokenStore.tokens() ?: throw AuthExpiredException("Not signed in")
        if (!current.needsRefresh(nowMs())) return current.accessToken

        return refreshMutex.withLock {
            // Re-read inside the lock: a request that queued behind the refresh must
            // use the new token rather than trigger a second refresh.
            val latest = tokenStore.tokens() ?: throw AuthExpiredException("Not signed in")
            if (!latest.needsRefresh(nowMs())) latest.accessToken else refreshLocked(latest).accessToken
        }
    }

    private suspend fun forceRefresh(): String = refreshMutex.withLock {
        val latest = tokenStore.tokens() ?: throw AuthExpiredException("Not signed in")
        refreshLocked(latest).accessToken
    }

    // endregion

    private suspend inline fun <reified T> authed(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val response = send(path, method, block)
        if (!response.status.isSuccess()) {
            throw AbsHttpException(response.status.value, response.bodyAsText().take(300))
        }
        return response.body()
    }

    suspend fun send(
        path: String,
        method: HttpMethod,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val base = requireBaseUrl()
        val url = if (path.startsWith("http")) path else "$base$path"

        var response = http.request(url) {
            this.method = method
            header("Authorization", "Bearer ${validAccessToken()}")
            block()
        }
        // Proactive refresh should mean we rarely land here, but clock skew and
        // server-side revocation are real; retry exactly once.
        if (response.status.value == 401) {
            val token = forceRefresh()
            response = http.request(url) {
                this.method = method
                header("Authorization", "Bearer $token")
                block()
            }
        }
        return response
    }

    // region library

    suspend fun libraries(): List<LibraryDto> = authed<LibrariesResponse>("/api/libraries").libraries

    /**
     * One page of a library. `limit` is always explicit: `limit=0` means "every row"
     * on this server, which is a footgun on a large library.
     */
    suspend fun libraryItems(
        libraryId: String,
        page: Int,
        limit: Int = DEFAULT_PAGE_SIZE,
        minified: Boolean = true,
    ): LibraryItemsResponse {
        require(limit > 0) { "limit=0 asks the server for the entire library" }
        return authed("/api/libraries/$libraryId/items?limit=$limit&page=$page&minified=${if (minified) 1 else 0}")
    }

    suspend fun item(itemId: String, expanded: Boolean = true): LibraryItemDto =
        authed("/api/items/$itemId?expanded=${if (expanded) 1 else 0}")

    suspend fun personalized(libraryId: String): List<PersonalizedShelfDto> =
        authed("/api/libraries/$libraryId/personalized")

    // endregion

    // region progress and sessions

    suspend fun allProgress(): List<MediaProgressDto> =
        authed<MediaProgressListResponse>("/api/me").mediaProgress

    /** Bookmarks arrive with the user, not from an endpoint of their own. */
    suspend fun allBookmarks(): List<BookmarkDto> =
        authed<MediaProgressListResponse>("/api/me").bookmarks

    suspend fun createBookmark(itemId: String, timeSec: Long, title: String): BookmarkDto =
        authed("/api/me/item/$itemId/bookmark", HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(BookmarkRequest(timeSec, title))
        }

    /** Renames the bookmark at this exact time; the time is the identity, so it cannot move. */
    suspend fun updateBookmark(itemId: String, timeSec: Long, title: String): BookmarkDto =
        authed("/api/me/item/$itemId/bookmark", HttpMethod.Patch) {
            contentType(ContentType.Application.Json)
            setBody(BookmarkRequest(timeSec, title))
        }

    suspend fun deleteBookmark(itemId: String, timeSec: Long) {
        val response = send("/api/me/item/$itemId/bookmark/$timeSec", HttpMethod.Delete)
        // A bookmark the server has already lost is the state the caller wanted anyway.
        if (!response.status.isSuccess() && response.status.value != 404) {
            throw AbsHttpException(response.status.value, response.bodyAsText().take(300))
        }
    }

    suspend fun progress(itemId: String, episodeId: String? = null): MediaProgressDto? {
        val path = "/api/me/progress/$itemId" + (episodeId?.let { "/$it" } ?: "")
        val response = send(path, HttpMethod.Get)
        // The server 404s when it has never seen progress for the item, which is a
        // normal "you have not started this" answer rather than an error.
        if (response.status.value == 404) return null
        if (!response.status.isSuccess()) {
            throw AbsHttpException(response.status.value, response.bodyAsText().take(300))
        }
        return response.body()
    }

    suspend fun updateProgress(itemId: String, episodeId: String?, body: ProgressUpdateRequest) {
        val path = "/api/me/progress/$itemId" + (episodeId?.let { "/$it" } ?: "")
        val response = send(path, HttpMethod.Patch) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw AbsHttpException(response.status.value, response.bodyAsText().take(300))
        }
    }

    suspend fun play(
        itemId: String,
        episodeId: String? = null,
        supportedMimeTypes: List<String>,
        forceTranscode: Boolean = false,
    ): PlaybackSessionDto {
        val path = "/api/items/$itemId/play" + (episodeId?.let { "/$it" } ?: "")
        return authed(path, HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(
                PlayRequest(
                    deviceInfo = deviceInfo,
                    supportedMimeTypes = supportedMimeTypes,
                    forceTranscode = forceTranscode,
                ),
            )
        }
    }

    suspend fun syncSession(sessionId: String, body: SessionSyncRequest) {
        val response = send("/api/session/$sessionId/sync", HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw AbsHttpException(response.status.value, response.bodyAsText().take(300))
        }
    }

    suspend fun closeSession(sessionId: String) {
        send("/api/session/$sessionId/close", HttpMethod.Post)
    }

    /** Replays sessions recorded while offline. Idempotent server-side on the client UUID. */
    suspend fun uploadLocalSessions(sessions: List<LocalSessionDto>) {
        if (sessions.isEmpty()) return
        val response = send("/api/session/local-all", HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(LocalSessionBatch(deviceInfo = deviceInfo, sessions = sessions))
        }
        if (!response.status.isSuccess()) {
            throw AbsHttpException(response.status.value, response.bodyAsText().take(300))
        }
    }

    // endregion

    /** Absolute URL for an item cover. Needs the auth header, so it is fetched through our stack. */
    suspend fun coverUrl(itemId: String, width: Int = 400): String =
        "${requireBaseUrl()}/api/items/$itemId/cover?width=$width"

    suspend fun absoluteUrl(contentUrl: String): String =
        if (contentUrl.startsWith("http")) contentUrl else "${requireBaseUrl()}$contentUrl"

    companion object {
        const val DEFAULT_PAGE_SIZE = 200

        private const val APP_NAME = "audiobookshelf"

        internal const val NOT_A_SERVER =
            "That address answered, but it is not an Audiobookshelf server. " +
                "Check the host, port and any subpath."
        private const val DEFAULT_ACCESS_TTL_MS = 55 * 60 * 1000L
    }
}

data class LoginResult(
    val tokens: AuthTokens,
    val userId: String,
    val username: String,
    val defaultLibraryId: String?,
    val progress: List<MediaProgressDto>,
)
