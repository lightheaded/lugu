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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * Custom headers are the most-asked-for thing in the upstream tracker for one reason:
 * without them, nobody behind Cloudflare Access can use the app at all. The tests here
 * are about the two ways that fails quietly — a header that reaches the API but not the
 * audio, and a header value that ends up somewhere a person can read it.
 */
class ConnectionHeadersTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val cloudflare = listOf(
        ConnectionHeader("CF-Access-Client-Id", "id-abc123"),
        ConnectionHeader("CF-Access-Client-Secret", "secret-shhh"),
    )

    private val profiles = ConnectionProfileSource { url ->
        if (url.startsWith(SERVER) || url.startsWith(LAN)) {
            ConnectionProfile(addresses = listOf(SERVER, LAN), headers = cloudflare)
        } else {
            null
        }
    }

    private fun client(engine: MockEngine, tokens: TokenStore) = AbsClient(
        serverUrlProvider = StaticServerUrlProvider(SERVER),
        tokenStore = tokens,
        deviceInfo = DeviceInfoDto(deviceId = "test-device"),
        connectionProfiles = profiles,
        nowMs = { 1_000_000L },
        http = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(AbsJson) }
        },
    )

    @Test
    fun `an api request carries the configured headers`() = runTest {
        var seen: Map<String, String> = emptyMap()
        val engine = MockEngine { request ->
            seen = request.headers.entries().associate { it.key to it.value.first() }
            respond("""{"libraries":[]}""", HttpStatusCode.OK, jsonHeaders)
        }
        val store = InMemoryTokenStore(AuthTokens("good", "r", 1_000_000L + 3_600_000L))

        client(engine, store).libraries()

        assertThat(seen["CF-Access-Client-Id"]).isEqualTo("id-abc123")
        assertThat(seen["CF-Access-Client-Secret"]).isEqualTo("secret-shhh")
    }

    /**
     * The one that decides whether somebody can use lugu at all: the address check and the
     * sign-in happen before any account exists, and an identity-aware proxy rejects both
     * without the headers.
     */
    @Test
    fun `the status probe and the login carry them too, before any account exists`() = runTest {
        val seen = mutableListOf<String?>()
        val engine = MockEngine { request ->
            seen += request.headers["CF-Access-Client-Id"]
            respond(
                """{"app":"audiobookshelf","serverVersion":"2.36.0",
                   "user":{"id":"u1","username":"tom","accessToken":"a","refreshToken":"r"}}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val absClient = client(engine, InMemoryTokenStore())

        absClient.status(SERVER)
        absClient.login(SERVER, "tom", "pw")

        assertThat(seen).containsExactly("id-abc123", "id-abc123")
    }

    @Test
    fun `the refresh request carries them, so a token renewal cannot be the thing that fails`() =
        runTest {
            var refreshHeader: String? = null
            val engine = MockEngine { request ->
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    refreshHeader = request.headers["CF-Access-Client-Secret"]
                }
                respond(
                    """{"user":{"id":"u1","username":"tom","accessToken":"new","refreshToken":"r2"}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            }
            val store = InMemoryTokenStore(AuthTokens("old", "r", accessTokenExpiresAtMs = 0L))

            client(engine, store).validAccessToken()

            assertThat(refreshHeader).isEqualTo("secret-shhh")
        }

    /**
     * The media data source, the downloader and Coil all go through one OkHttp client with
     * this interceptor on it. A header that reaches the API but not this path produces a
     * library that browses and a book that will not play.
     */
    @Test
    fun `a media request through the interceptor carries them and the token`() {
        val interceptor = AuthInterceptor(
            serverUrlProvider = StaticServerUrlProvider(SERVER),
            connectionProfiles = profiles,
            tokenProvider = { "token-xyz" },
        )

        val sent = sentThrough(interceptor, "$SERVER/api/items/abc/file/1")

        assertThat(sent.header("CF-Access-Client-Id")).isEqualTo("id-abc123")
        assertThat(sent.header("CF-Access-Client-Secret")).isEqualTo("secret-shhh")
        assertThat(sent.header("Authorization")).isEqualTo("Bearer token-xyz")
    }

    /**
     * A cover URL is built from the address the account was created with even while the
     * local address is the one in use, so both have to be recognised as this server.
     */
    @Test
    fun `a cover request built from the other address of the same server is still ours`() {
        val interceptor = AuthInterceptor(
            serverUrlProvider = StaticServerUrlProvider(LAN),
            connectionProfiles = profiles,
            tokenProvider = { "token-xyz" },
        )

        val sent = sentThrough(interceptor, "$SERVER/api/items/abc/cover?width=400")

        assertThat(sent.header("CF-Access-Client-Id")).isEqualTo("id-abc123")
        assertThat(sent.header("Authorization")).isEqualTo("Bearer token-xyz")
    }

    @Test
    fun `somewhere else on the internet gets neither a header nor a token`() {
        val interceptor = AuthInterceptor(
            serverUrlProvider = StaticServerUrlProvider(SERVER),
            connectionProfiles = profiles,
            tokenProvider = { "token-xyz" },
        )

        val sent = sentThrough(interceptor, "https://someone-elses.example/thing")

        assertThat(sent.header("CF-Access-Client-Id")).isNull()
        assertThat(sent.header("Authorization")).isNull()
    }

    /**
     * A provider that knows nothing about connection settings must keep the behaviour the
     * interceptor had before they existed, rather than quietly losing its token.
     */
    @Test
    fun `a plain url provider still gets its token`() {
        val interceptor = AuthInterceptor(
            serverUrlProvider = StaticServerUrlProvider(SERVER),
            tokenProvider = { "token-xyz" },
        )

        val sent = sentThrough(interceptor, "$SERVER/api/libraries")

        assertThat(sent.header("Authorization")).isEqualTo("Bearer token-xyz")
    }

    // region nothing sensitive in anything displayable

    @Test
    fun `a header does not print its value`() {
        val header = ConnectionHeader("CF-Access-Client-Secret", "secret-shhh")

        assertThat(header.toString()).doesNotContain("secret-shhh")
        assertThat(header.maskedValue).doesNotContain("secret-shhh")
        assertThat("$header").contains("CF-Access-Client-Secret")
    }

    @Test
    fun `a profile prints neither an address nor a value`() {
        val profile = ConnectionProfile(listOf(SERVER, LAN), cloudflare)

        assertThat(profile.toString()).doesNotContain("secret-shhh")
        assertThat(profile.toString()).doesNotContain("books.example")
        assertThat(profile.toString()).doesNotContain("192.168")
    }

    /**
     * The mask must not be as long as the value either: a fifty-character secret rendered
     * as fifty dots tells a shoulder-surfer which secret it is.
     */
    @Test
    fun `the mask is a fixed width`() {
        val short = ConnectionHeader("A", "x").maskedValue
        val long = ConnectionHeader("B", "x".repeat(64)).maskedValue

        assertThat(short).isEqualTo(long)
        assertThat(ConnectionHeader("C", "").maskedValue).isEmpty()
    }

    // endregion

    // region what a header is allowed to be

    @Test
    fun `a value with a line break is refused rather than sent`() {
        val problem = ConnectionHeaders.problemWith(
            "X-Thing",
            "value\r\nX-Injected: yes",
        )

        assertThat(problem).isNotNull()
    }

    @Test
    fun `the headers lugu sets itself cannot be overridden`() {
        assertThat(ConnectionHeaders.problemWith("Authorization", "Bearer nope")).isNotNull()
        assertThat(ConnectionHeaders.problemWith("authorization", "Bearer nope")).isNotNull()
        assertThat(ConnectionHeaders.problemWith("CF-Access-Client-Id", "fine")).isNull()
    }

    @Test
    fun `a name with a space in it is refused`() {
        assertThat(ConnectionHeaders.problemWith("X Thing", "value")).isNotNull()
        assertThat(ConnectionHeaders.problemWith("", "value")).isNotNull()
    }

    @Test
    fun `the same header twice keeps the later value`() {
        val deduplicated = ConnectionHeaders.deduplicate(
            listOf(ConnectionHeader("X-A", "old"), ConnectionHeader("x-a", "new")),
        )

        assertThat(deduplicated).hasSize(1)
        assertThat(deduplicated.single().value).isEqualTo("new")
    }

    // endregion

    private companion object {
        const val SERVER = "https://books.example"
        const val LAN = "http://192.168.1.10:13378"
    }
}

/**
 * Runs a request through a real OkHttp chain with [interceptor] on it, and returns the
 * request as it would have gone out. A second interceptor answers in place of the network,
 * so this exercises the real chain without one.
 */
private fun sentThrough(interceptor: Interceptor, url: String): Request {
    val captured = AtomicReference<Request>()
    val client = OkHttpClient.Builder()
        .addInterceptor(interceptor)
        .addInterceptor { chain ->
            captured.set(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }
        .build()

    client.newCall(Request.Builder().url(url).build()).execute().close()
    return captured.get()
}
