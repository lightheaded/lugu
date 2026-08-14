package io.github.lightheaded.lugu.core.api

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.AuthTokens
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/**
 * The refresh path is the gate to every other request, so it gets tested directly:
 * a burst of parallel calls on app start must produce exactly one refresh, not one
 * per call, and a 401 must be retried once rather than surfacing to the user.
 */
class AbsClientAuthTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(
        tokenStore: TokenStore,
        nowMs: () -> Long = { 1_000_000L },
        handler: MockEngine,
    ) = AbsClient(
        serverUrlProvider = StaticServerUrlProvider("https://books.example"),
        tokenStore = tokenStore,
        deviceInfo = DeviceInfoDto(deviceId = "test-device"),
        nowMs = nowMs,
        http = HttpClient(handler) {
            expectSuccess = false
            install(ContentNegotiation) { json(AbsJson) }
        },
    )

    private fun refreshResponse(access: String, refresh: String) =
        """{"user":{"id":"u1","username":"tom","accessToken":"$access","refreshToken":"$refresh"}}"""

    @Test
    fun `an expiring token is refreshed once even under parallel load`() = runTest {
        val refreshes = AtomicInteger(0)
        val store = InMemoryTokenStore(
            AuthTokens(
                accessToken = "old",
                refreshToken = "refresh-1",
                // Already inside the five-minute refresh margin.
                accessTokenExpiresAtMs = 1_000_000L + 60_000L,
            ),
        )

        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/auth/refresh")) {
                refreshes.incrementAndGet()
                // Hold the lock long enough that a broken implementation would let
                // other callers slip past and refresh again.
                delay(50)
                respond(refreshResponse("new", "refresh-2"), HttpStatusCode.OK, jsonHeaders)
            } else {
                respond("""{"libraries":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val absClient = client(store, handler = engine)

        withContext(Dispatchers.Default) {
            (1..20).map { async { absClient.validAccessToken() } }.awaitAll()
        }

        assertThat(refreshes.get()).isEqualTo(1)
        assertThat(store.tokens()?.accessToken).isEqualTo("new")
        assertThat(store.tokens()?.refreshToken).isEqualTo("refresh-2")
    }

    @Test
    fun `a healthy token is used without touching the refresh endpoint`() = runTest {
        val refreshes = AtomicInteger(0)
        val store = InMemoryTokenStore(
            AuthTokens("good", "refresh-1", accessTokenExpiresAtMs = 1_000_000L + 3_600_000L),
        )
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/auth/refresh")) refreshes.incrementAndGet()
            respond("""{"libraries":[]}""", HttpStatusCode.OK, jsonHeaders)
        }

        assertThat(client(store, handler = engine).validAccessToken()).isEqualTo("good")
        assertThat(refreshes.get()).isEqualTo(0)
    }

    @Test
    fun `a 401 is refreshed and retried exactly once`() = runTest {
        val store = InMemoryTokenStore(
            AuthTokens("stale", "refresh-1", accessTokenExpiresAtMs = 1_000_000L + 3_600_000L),
        )
        val libraryCalls = AtomicInteger(0)

        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/auth/refresh") ->
                    respond(refreshResponse("fresh", "refresh-2"), HttpStatusCode.OK, jsonHeaders)

                else -> {
                    val attempt = libraryCalls.incrementAndGet()
                    val bearer = request.headers[HttpHeaders.Authorization]
                    // The server revoked the token server-side; only the retry succeeds.
                    if (attempt == 1 && bearer == "Bearer stale") {
                        respond("unauthorised", HttpStatusCode.Unauthorized)
                    } else {
                        respond("""{"libraries":[{"id":"l1","name":"Books"}]}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }
            }
        }

        val libraries = client(store, handler = engine).libraries()

        assertThat(libraries).hasSize(1)
        assertThat(libraryCalls.get()).isEqualTo(2)
        assertThat(store.tokens()?.accessToken).isEqualTo("fresh")
    }

    @Test
    fun `a rejected refresh clears the stored credentials`() = runTest {
        val store = InMemoryTokenStore(
            AuthTokens("old", "revoked", accessTokenExpiresAtMs = 0L),
        )
        val engine = MockEngine { respond("nope", HttpStatusCode.Unauthorized) }

        val failure = runCatching { client(store, handler = engine).validAccessToken() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AuthExpiredException::class.java)
        // Leaving a dead refresh token behind would make every later call fail slowly.
        assertThat(store.tokens()).isNull()
    }

    @Test
    fun `login asks for the refresh token in the body rather than a cookie`() = runTest {
        val store = InMemoryTokenStore()
        var sawHeader = false

        val engine = MockEngine { request ->
            sawHeader = request.headers["x-return-tokens"] == "true"
            respond(
                """{"user":{"id":"u1","username":"tom","accessToken":"a","refreshToken":"r"},
                   "userDefaultLibraryId":"lib1"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val result = client(store, handler = engine).login("https://books.example", "tom", "pw")

        assertThat(sawHeader).isTrue()
        assertThat(result.tokens.refreshToken).isEqualTo("r")
        assertThat(result.defaultLibraryId).isEqualTo("lib1")
    }

    /**
     * A reverse proxy with no route for the hostname answers every path with a plain
     * `404 page not found`. Echoing that back told a real user nothing and implied the
     * app was broken; worse, reaching login at all implies the address was fine and
     * invites them to doubt their password instead.
     */
    @Test
    fun `a 404 from a proxy is reported as a wrong address, not as its own body`() = runTest {
        val engine = MockEngine { respond("404 page not found", HttpStatusCode.NotFound) }

        val failure = runCatching {
            client(InMemoryTokenStore(), handler = engine).login("https://books.example", "tom", "pw")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AbsHttpException::class.java)
        assertThat(failure).hasMessageThat().contains("not an Audiobookshelf server")
        assertThat(failure).hasMessageThat().doesNotContain("404 page not found")
    }

    @Test
    fun `the status probe rejects a non-Audiobookshelf response`() = runTest {
        val engine = MockEngine { respond("404 page not found", HttpStatusCode.NotFound) }

        val failure = runCatching {
            client(InMemoryTokenStore(), handler = engine).status("https://books.example")
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("not an Audiobookshelf server")
    }

    @Test
    fun `the status probe rejects a 200 that is not a status payload`() = runTest {
        // Some proxies serve an HTML error page with a 200.
        val engine = MockEngine {
            respond("<html><body>Gateway</body></html>", HttpStatusCode.OK, jsonHeaders)
        }

        val failure = runCatching {
            client(InMemoryTokenStore(), handler = engine).status("https://books.example")
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("not an Audiobookshelf server")
    }

    @Test
    fun `the status probe rejects valid JSON from some other service`() = runTest {
        // Parsing is not identity: another JSON API on the same host would sail through
        // a shape-only check, since every field of ServerStatusDto has a default.
        val engine = MockEngine { respond("""{"ok":true,"service":"grafana"}""", HttpStatusCode.OK, jsonHeaders) }

        val failure = runCatching {
            client(InMemoryTokenStore(), handler = engine).status("https://books.example")
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("not an Audiobookshelf server")
    }

    /** Shape captured live from a v2.36.0 server. */
    @Test
    fun `a real status response is accepted`() = runTest {
        val engine = MockEngine {
            respond(
                """{"app":"audiobookshelf","serverVersion":"2.36.0","isInit":true,
                   "language":"en-us","authMethods":["local"],"authFormData":{"authLoginCustomMessage":""}}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val status = client(InMemoryTokenStore(), handler = engine).status("https://books.example")

        assertThat(status.serverVersion).isEqualTo("2.36.0")
        assertThat(status.authMethods).containsExactly("local")
    }

    @Test
    fun `wrong credentials stay reported as wrong credentials`() = runTest {
        val engine = MockEngine { respond("""{"error":"Invalid"}""", HttpStatusCode.Unauthorized, jsonHeaders) }

        val failure = runCatching {
            client(InMemoryTokenStore(), handler = engine).login("https://books.example", "tom", "pw")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AuthExpiredException::class.java)
        assertThat(failure).hasMessageThat().contains("username or password")
    }

    @Test
    fun `asking for every row in one page is refused`() = runTest {
        val store = InMemoryTokenStore(
            AuthTokens("good", "r", accessTokenExpiresAtMs = 1_000_000L + 3_600_000L),
        )
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }

        // limit=0 means "the entire library" to this server — a memory cliff, not a default.
        val failure = runCatching {
            client(store, handler = engine).libraryItems("lib1", page = 0, limit = 0)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }
}
