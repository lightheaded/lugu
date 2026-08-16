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
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The one call that can state series membership for a whole library at once.
 *
 * Its envelope is not the shape the documentation describes, and the two differences are
 * both things that would fail in the field rather than here: the handler echoes the raw
 * query string back as `limit` and `page`, so those come back quoted, and it echoes
 * `minified` without ever having read it.
 */
class LibrarySeriesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(handler: MockEngine) = AbsClient(
        serverUrlProvider = StaticServerUrlProvider("https://books.example"),
        tokenStore = InMemoryTokenStore(
            AuthTokens(accessToken = "token", refreshToken = null, accessTokenExpiresAtMs = Long.MAX_VALUE),
        ),
        deviceInfo = DeviceInfoDto(deviceId = "test-device"),
        http = HttpClient(handler) {
            expectSuccess = false
            install(ContentNegotiation) { json(AbsJson) }
        },
    )

    @Test
    fun `a page is asked for explicitly, and the echoed strings do not break it`() = runTest {
        var asked: String? = null
        val engine = MockEngine { request ->
            asked = request.url.toString()
            respond(
                """
                {"results":[{"id":"se_1","name":"The Breakwater","libraryId":"lib_1",
                  "books":[{"id":"li_1","libraryId":"lib_1","media":{"metadata":
                     {"title":"Lighthouse Wakes","seriesName":"The Breakwater #1"}}},
                   {"id":"li_2","libraryId":"lib_1","media":{"metadata":
                     {"title":"Lighthouse Falls","seriesName":"The Breakwater #2, Riverton #1"}}}]}],
                 "total":1,"limit":"50","page":"0","minified":false,"include":""}
                """.trimIndent(),
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val response = client(engine).librarySeries("lib_1", page = 0, limit = 50)

        assertThat(asked).contains("/api/libraries/lib_1/series")
        assertThat(asked).contains("limit=50")
        assertThat(asked).contains("page=0")
        assertThat(response.total).isEqualTo(1)

        val series = response.results.single()
        assertThat(series.name).isEqualTo("The Breakwater")
        // The ordering the server computed, which is what the array is for.
        assertThat(series.books.map { it.id }).containsExactly("li_1", "li_2").inOrder()
    }

    /**
     * Members arrive minified, so each one's sequence for *this* series has to come back
     * out of the joined string anchored on the name the listing already gave. The second
     * book here is in two series, which is exactly the string the blind parse misreads.
     */
    @Test
    fun `a member's sequence is recovered for the series it was listed under`() = runTest {
        val engine = MockEngine {
            respond(
                """
                {"results":[{"id":"se_1","name":"The Breakwater","libraryId":"lib_1",
                  "books":[{"id":"li_2","libraryId":"lib_1","media":{"metadata":
                     {"title":"Lighthouse Falls","seriesName":"The Breakwater #2, Riverton #1"}}}]}],
                 "total":1}
                """.trimIndent(),
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val series = client(engine).librarySeries("lib_1", page = 0).results.single()
        val ref = series.books.single().seriesRefFor(series.id, series.name)

        assertThat(ref.name).isEqualTo("The Breakwater")
        assertThat(ref.sequence).isEqualTo(2.0)
        assertThat(ref.id).isEqualTo("se_1")
    }

    /** `limit=0` is "every series in one response" on this server, and never sent. */
    @Test
    fun `asking for no limit is refused before it reaches the server`() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }

        val thrown = runCatching { client(engine).librarySeries("lib_1", page = 0, limit = 0) }

        assertThat(thrown.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a library with no series answers with nothing rather than failing`() = runTest {
        val engine = MockEngine { respond("""{"results":[],"total":0}""", HttpStatusCode.OK, jsonHeaders) }

        assertThat(client(engine).librarySeries("lib_1", page = 0).results).isEmpty()
    }
}
