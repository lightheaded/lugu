package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.AbsJson
import io.github.lightheaded.lugu.core.api.AuthExpiredException
import io.github.lightheaded.lugu.core.api.DeviceInfoDto
import io.github.lightheaded.lugu.core.api.StaticServerUrlProvider
import io.github.lightheaded.lugu.core.model.AuthTokens
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/**
 * A token refresh is a read, a network call and a write, and somebody can switch accounts
 * or sign out in the middle. The write must go to the account the read came from, and it
 * must not bring back a sign-in that ended while the call was in flight.
 *
 * Each test holds the refresh at the server until the account change is done, so the
 * order is fixed rather than left to timing.
 */
class TokenRefreshAccountTest {

    private val now = 1_000_000L

    private val tokensA = AuthTokens("access-a", "refresh-a", accessTokenExpiresAtMs = now + 60_000L)
    private val tokensB = AuthTokens("access-b", "refresh-b", accessTokenExpiresAtMs = now + 3_600_000L)

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** A server whose refresh answers only when [release] completes. */
    private class HeldRefresh {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<HttpStatusCode>()
    }

    private fun client(store: InMemoryAccountTokens, held: HeldRefresh) = AbsClient(
        serverUrlProvider = StaticServerUrlProvider("https://books.example"),
        tokenStore = store,
        deviceInfo = DeviceInfoDto(deviceId = "test-device"),
        nowMs = { now },
        http = HttpClient(
            MockEngine { request ->
                check(request.url.encodedPath.endsWith("/auth/refresh"))
                held.started.complete(Unit)
                val status = held.release.await()
                if (status.value == 200) {
                    respond(
                        """{"user":{"id":"u1","username":"listener-a","accessToken":"access-a2","refreshToken":"refresh-a2"}}""",
                        status,
                        jsonHeaders,
                    )
                } else {
                    respond("nope", status)
                }
            },
        ) {
            expectSuccess = false
            install(ContentNegotiation) { json(AbsJson) }
        },
    )

    private suspend fun twoAccounts(): InMemoryAccountTokens = InMemoryAccountTokens(activeServerId = "a").apply {
        saveFor("a", tokensA)
        saveFor("b", tokensB)
    }

    @Test
    fun `a refresh that ends after a switch writes to the account it started on`() = runTest {
        val store = twoAccounts()
        val held = HeldRefresh()
        val absClient = client(store, held)

        val token = withContext(Dispatchers.Default) {
            val refresh = async { absClient.validAccessToken() }
            held.started.await()
            store.activeServerId = "b"
            held.release.complete(HttpStatusCode.OK)
            refresh.await()
        }

        assertThat(token).isEqualTo("access-a2")
        assertThat(store.tokensFor("a")?.accessToken).isEqualTo("access-a2")
        assertThat(store.tokensFor("a")?.refreshToken).isEqualTo("refresh-a2")
        assertThat(store.tokensFor("b")).isEqualTo(tokensB)
    }

    @Test
    fun `a rejected refresh after a switch ends only the account it started on`() = runTest {
        val store = twoAccounts()
        val held = HeldRefresh()
        val absClient = client(store, held)

        val failure = withContext(Dispatchers.Default) {
            val refresh = async { runCatching { absClient.validAccessToken() } }
            held.started.await()
            store.activeServerId = "b"
            held.release.complete(HttpStatusCode.Unauthorized)
            refresh.await().exceptionOrNull()
        }

        assertThat(failure).isInstanceOf(AuthExpiredException::class.java)
        assertThat(store.tokensFor("a")).isNull()
        assertThat(store.tokensFor("b")).isEqualTo(tokensB)
    }

    @Test
    fun `a refresh that ends after a sign-out does not sign the account back in`() = runTest {
        val store = twoAccounts()
        val held = HeldRefresh()
        val absClient = client(store, held)

        val failure = withContext(Dispatchers.Default) {
            val refresh = async { runCatching { absClient.validAccessToken() } }
            held.started.await()
            // What AuthRepository.signOutOf does to the store, with no account left active.
            store.activeServerId = null
            store.clearFor("a")
            held.release.complete(HttpStatusCode.OK)
            refresh.await().exceptionOrNull()
        }

        assertThat(failure).isInstanceOf(AuthExpiredException::class.java)
        assertThat(store.tokensFor("a")).isNull()
        assertThat(store.tokensFor("b")).isEqualTo(tokensB)
    }

    @Test
    fun `a refresh that ends after a new sign-in keeps the newer tokens`() = runTest {
        val store = twoAccounts()
        val held = HeldRefresh()
        val absClient = client(store, held)
        val signedInAgain = AuthTokens("access-a3", "refresh-a3", accessTokenExpiresAtMs = now + 3_600_000L)

        withContext(Dispatchers.Default) {
            val refresh = async { runCatching { absClient.validAccessToken() } }
            held.started.await()
            store.clearFor("a")
            store.saveFor("a", signedInAgain)
            held.release.complete(HttpStatusCode.OK)
            refresh.await()
        }

        assertThat(store.tokensFor("a")).isEqualTo(signedInAgain)
        assertThat(store.tokensFor("b")).isEqualTo(tokensB)
    }
}
