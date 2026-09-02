package io.github.lightheaded.lugu.core.api

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The two requests lugu makes for an identity-provider sign-in.
 *
 * Both have a failure that would only ever show up against a real server, so both are
 * pinned here instead:
 *
 * - Step 1 must **not** follow the redirect. Following it sends lugu's HTTP client to a
 *   login page meant for a person, and drops the session cookies that step 4 cannot do
 *   without.
 * - Step 4 must send those cookies back. Without them the server answers "No session",
 *   and without `auth_method` among them it answers a web page instead of JSON.
 */
class AbsOidcClientTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(handler: MockEngine) = AbsClient(
        serverUrlProvider = StaticServerUrlProvider("https://books.example"),
        tokenStore = InMemoryTokenStore(),
        deviceInfo = DeviceInfoDto(deviceId = "test-device"),
        nowMs = { 1_000_000L },
        http = HttpClient(handler) {
            expectSuccess = false
            install(ContentNegotiation) { json(AbsJson) }
        },
    )

    @Test
    fun `the start request reads the redirect rather than following it`() = runTest {
        var authorizeRequests = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/auth/openid" -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(
                        HttpHeaders.Location to listOf("https://idp.example/authorize?foo=bar"),
                        HttpHeaders.SetCookie to listOf(
                            "connect.sid=s%3Aabc; Path=/; HttpOnly",
                            "auth_method=openid-mobile; Path=/; HttpOnly",
                        ),
                    ),
                )
                // The provider's page. Reaching it means the redirect was followed, which
                // is the failure this test exists to catch.
                else -> {
                    authorizeRequests += 1
                    respond("<html>sign in</html>", HttpStatusCode.OK)
                }
            }
        }

        val attempt = client(engine).beginOidc("https://books.example")

        assertThat(attempt.authorizationUrl).isEqualTo("https://idp.example/authorize?foo=bar")
        assertThat(authorizeRequests).isEqualTo(0)
        assertThat(attempt.cookies).hasSize(2)
        assertThat(attempt.codeVerifier).isNotEmpty()
        assertThat(attempt.state).isNotEmpty()
    }

    /**
     * The server's refusals here are the useful ones — an unlisted `redirect_uri`, or
     * OpenID not switched on — and it sends each as plain text with a 400. Replacing that
     * with a message of lugu's own would hide the only thing that says what to fix.
     */
    @Test
    fun `a refusal to start carries the server's own words`() = runTest {
        val engine = MockEngine { respond("Invalid redirect_uri", HttpStatusCode.BadRequest) }

        val failure = runCatching { client(engine).beginOidc("https://books.example") }
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(AbsHttpException::class.java)
        assertThat(failure).hasMessageThat().contains("Invalid redirect_uri")
    }

    @Test
    fun `a start that answers with no location at all is a failure rather than a crash`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.OK) }

        val failure = runCatching { client(engine).beginOidc("https://books.example") }
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(AbsHttpException::class.java)
    }

    @Test
    fun `the callback sends the session back and reads the tokens`() = runTest {
        var seenCookie: String? = null
        var seenQuery: String? = null
        val engine = MockEngine { request ->
            seenCookie = request.headers[HttpHeaders.Cookie]
            seenQuery = request.url.encodedQuery
            respond(
                content = """
                    {"user":{"id":"u9","username":"listener","accessToken":"access-1",
                     "refreshToken":"refresh-1"},"userDefaultLibraryId":"lib-9"}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val attempt = AbsOidc.Attempt(
            authorizationUrl = "https://idp.example/authorize",
            state = "st-1",
            codeVerifier = "verifier-1",
            cookies = listOf(
                "connect.sid=s%3Aabc; Path=/; HttpOnly",
                "auth_method=openid-mobile; Path=/; HttpOnly",
            ),
        )

        val result = client(engine).completeOidc("https://books.example", attempt, code = "code-1")

        assertThat(seenCookie).isEqualTo("connect.sid=s%3Aabc; auth_method=openid-mobile")
        // The verifier goes on this request and nowhere else. It is what proves the code
        // belongs to the challenge sent in step 1.
        assertThat(seenQuery).contains("code_verifier=verifier-1")
        assertThat(seenQuery).contains("code=code-1")
        assertThat(seenQuery).contains("state=st-1")

        assertThat(result.userId).isEqualTo("u9")
        assertThat(result.username).isEqualTo("listener")
        assertThat(result.tokens.accessToken).isEqualTo("access-1")
        assertThat(result.tokens.refreshToken).isEqualTo("refresh-1")
        assertThat(result.defaultLibraryId).isEqualTo("lib-9")
    }

    @Test
    fun `a provider that refuses reads as an expired sign-in rather than a server fault`() = runTest {
        val engine = MockEngine { respond("Unauthorized", HttpStatusCode.Unauthorized) }
        val attempt = AbsOidc.Attempt("https://idp.example", "st", "ver", emptyList())

        val failure = runCatching {
            client(engine).completeOidc("https://books.example", attempt, code = "c")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AuthExpiredException::class.java)
    }

    /**
     * The server answers "No session" with a 400 when the cookies did not arrive. It has
     * to reach the person as words rather than as a status code, because the fix — start
     * the sign-in again — is not guessable from "HTTP 400".
     */
    @Test
    fun `a lost session says what the server said`() = runTest {
        val engine = MockEngine { respond("No session", HttpStatusCode.BadRequest) }
        val attempt = AbsOidc.Attempt("https://idp.example", "st", "ver", emptyList())

        val failure = runCatching {
            client(engine).completeOidc("https://books.example", attempt, code = "c")
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("No session")
    }
}
