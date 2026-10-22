package io.github.lightheaded.lugu.core.api

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.AuthTokens
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The batch add request, in the shape the 2.36.0 server reads.
 *
 * `CollectionController.addBatch` reads `req.body.books` as a list of library item id
 * strings. Anything else in the body is ignored, and a body with no usable id is a 400, so
 * a wrong key name would fail every time rather than now and then.
 */
class AbsCollectionsTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String) = AbsClient(
        serverUrlProvider = StaticServerUrlProvider("https://books.example"),
        tokenStore = InMemoryTokenStore(
            AuthTokens(accessToken = "token", refreshToken = null, accessTokenExpiresAtMs = Long.MAX_VALUE / 2),
        ),
        deviceInfo = DeviceInfoDto(deviceId = "test-device"),
        nowMs = { 0L },
        http = HttpClient(
            MockEngine { request ->
                requests += request
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            expectSuccess = false
            install(ContentNegotiation) { json(AbsJson) }
        },
    )

    private val collectionJson = """
        {"id":"col_1","libraryId":"lib_1","name":"An Invented Shelf",
         "books":[{"id":"li_1","libraryId":"lib_1"},{"id":"li_2","libraryId":"lib_1"}],
         "lastUpdate":5,"createdAt":1}
    """.trimIndent()

    @Test
    fun `a batch add posts the ids under books to the batch route`() = runTest {
        client(body = collectionJson).addAllToCollection("col_1", listOf("li_1", "li_2"))

        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.url.encodedPath).isEqualTo("/api/collections/col_1/batch/add")
        assertThat(request.body.contentType?.toString()).startsWith("application/json")
        val sent = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
        assertThat(sent.keys).containsExactly("books")
        assertThat(sent.getValue("books").jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("li_1", "li_2")
            .inOrder()
    }

    @Test
    fun `the reply is the collection as it now stands`() = runTest {
        val dto = client(body = collectionJson).addAllToCollection("col_1", listOf("li_2"))

        assertThat(dto.id).isEqualTo("col_1")
        assertThat(dto.books.map { it.id }).containsExactly("li_1", "li_2").inOrder()
    }

    @Test
    fun `a refusal surfaces as an http exception with the status`() = runTest {
        val failing = client(HttpStatusCode.BadRequest, body = "Invalid request body. No valid books")

        val thrown = runCatching { failing.addAllToCollection("col_1", listOf("li_9")) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(AbsHttpException::class.java)
        assertThat((thrown as AbsHttpException).status).isEqualTo(400)
    }
}
