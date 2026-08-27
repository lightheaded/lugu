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
import org.junit.Test

/**
 * "Which feed episodes does the library not already have" and the request shapes for
 * asking the Audiobookshelf **server** to fetch episodes it does not hold yet.
 *
 * This is server-side fetch, not the phone-side download `:core:download` owns — see
 * `AbsPodcasts.kt`'s file KDoc.
 */
class AbsPodcastsTest {

    // region episodesMissingFromServerLibrary

    private fun episode(guid: String? = null, enclosureUrl: String? = null, title: String = "Episode") =
        PodcastFeedEpisodeDto(
            title = title,
            guid = guid,
            enclosure = enclosureUrl?.let { PodcastEnclosureDto(url = it) },
        )

    @Test
    fun `an episode whose guid is already known is not missing`() {
        val feed = listOf(episode(guid = "guid-1"), episode(guid = "guid-2"))

        val missing = episodesMissingFromServerLibrary(feed, knownGuidsOrEnclosureUrls = setOf("guid-1"))

        assertThat(missing.map { it.guid }).containsExactly("guid-2")
    }

    @Test
    fun `a feed with no guid falls back to the enclosure url`() {
        val feed = listOf(
            episode(enclosureUrl = "https://feed.example/ep1.mp3"),
            episode(enclosureUrl = "https://feed.example/ep2.mp3"),
        )

        val missing = episodesMissingFromServerLibrary(
            feed,
            knownGuidsOrEnclosureUrls = setOf("https://feed.example/ep1.mp3"),
        )

        assertThat(missing).hasSize(1)
        assertThat(missing.single().enclosure?.url).isEqualTo("https://feed.example/ep2.mp3")
    }

    @Test
    fun `an episode with neither a guid nor an enclosure url is always reported as missing`() {
        // Nothing to match it against, so it can never be "already known" — safer than
        // silently dropping it from what gets offered.
        val feed = listOf(episode(title = "No identity at all"))

        val missing = episodesMissingFromServerLibrary(feed, knownGuidsOrEnclosureUrls = setOf("anything"))

        assertThat(missing).hasSize(1)
    }

    @Test
    fun `an empty known set reports every episode as missing`() {
        val feed = listOf(episode(guid = "guid-1"), episode(guid = "guid-2"))

        assertThat(episodesMissingFromServerLibrary(feed, emptySet())).hasSize(2)
    }

    @Test
    fun `a blank guid does not match a blank known entry`() {
        // A blank string is not an identity; treating "" as a real guid would make every
        // feed with one blank-guid episode swallow every other blank-guid episode too.
        val feed = listOf(episode(guid = ""), episode(guid = "guid-2"))

        val missing = episodesMissingFromServerLibrary(feed, knownGuidsOrEnclosureUrls = setOf(""))

        assertThat(missing).hasSize(2)
    }

    // endregion

    // region JSON decoding — tolerant by design, like every other DTO in this client

    @Test
    fun `checknew's response decodes a real feed episode shape`() {
        val json = """
            {"episodes":[
              {"title":"Episode One","subtitle":"A first look","description":"<p>Notes</p>",
               "pubDate":"Mon, 01 Jun 2026 00:00:00 GMT","episodeType":"full","season":"1",
               "episode":"1","author":"Jefferson Vale","duration":"01:02:03",
               "durationSeconds":3723.0,"explicit":"no","publishedAt":1748736000000,
               "enclosure":{"url":"https://feed.example/ep1.mp3","type":"audio/mpeg","length":"1048576"},
               "guid":"guid-ep-1","chapters":[{"id":1,"title":"Intro","start":0.0,"end":30.0}],
               "someUnknownFutureField":"ignored"}
            ]}
        """.trimIndent()

        val decoded = AbsJson.decodeFromString(CheckNewEpisodesResponse.serializer(), json)

        assertThat(decoded.episodes).hasSize(1)
        val ep = decoded.episodes.single()
        assertThat(ep.title).isEqualTo("Episode One")
        assertThat(ep.guid).isEqualTo("guid-ep-1")
        assertThat(ep.enclosure?.url).isEqualTo("https://feed.example/ep1.mp3")
        assertThat(ep.publishedAt).isEqualTo(1748736000000L)
        assertThat(ep.chapters.single().title).isEqualTo("Intro")
    }

    @Test
    fun `an episode with no chapters, no guid and no enclosure still decodes`() {
        val decoded = AbsJson.decodeFromString(
            PodcastFeedEpisodeDto.serializer(),
            """{"title":"Bare Episode"}""",
        )

        assertThat(decoded.title).isEqualTo("Bare Episode")
        assertThat(decoded.guid).isNull()
        assertThat(decoded.enclosure).isNull()
        assertThat(decoded.chapters).isEmpty()
    }

    // endregion

    // region request shapes, against a mocked server

    @Test
    fun `checkNewEpisodesOnServer is a GET with the limit as a query parameter`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                """{"episodes":[]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val absClient = AbsClient(
            serverUrlProvider = StaticServerUrlProvider("https://books.example"),
            tokenStore = InMemoryTokenStore(
                AuthTokens(accessToken = "token", refreshToken = null, accessTokenExpiresAtMs = Long.MAX_VALUE / 2),
            ),
            deviceInfo = DeviceInfoDto(deviceId = "test-device"),
            nowMs = { 0L },
            http = HttpClient(engine) {
                expectSuccess = false
                install(ContentNegotiation) { json(AbsJson) }
            },
        )

        absClient.checkNewEpisodesOnServer("li_podcast_1", limit = 25)

        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Get)
        assertThat(request.url.encodedPath).isEqualTo("/api/podcasts/li_podcast_1/checknew")
        assertThat(request.url.parameters["limit"]).isEqualTo("25")
    }

    @Test
    fun `queueEpisodesOnServer posts a bare JSON array, not an object`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond("", HttpStatusCode.OK)
        }
        val absClient = AbsClient(
            serverUrlProvider = StaticServerUrlProvider("https://books.example"),
            tokenStore = InMemoryTokenStore(
                AuthTokens(accessToken = "token", refreshToken = null, accessTokenExpiresAtMs = Long.MAX_VALUE / 2),
            ),
            deviceInfo = DeviceInfoDto(deviceId = "test-device"),
            nowMs = { 0L },
            http = HttpClient(engine) {
                expectSuccess = false
                install(ContentNegotiation) { json(AbsJson) }
            },
        )

        absClient.queueEpisodesOnServer(
            "li_podcast_1",
            listOf(PodcastFeedEpisodeDto(title = "New Episode", guid = "guid-9")),
        )

        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.url.encodedPath).isEqualTo("/api/podcasts/li_podcast_1/download-episodes")
        val bodyText = request.body.toByteArray().decodeToString()
        assertThat(bodyText.trim()).startsWith("[")
        assertThat(bodyText).contains("\"guid\":\"guid-9\"")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `queueEpisodesOnServer refuses an empty list before any request is sent`() = runTest {
        var requestSent = false
        val absClient = AbsClient(
            serverUrlProvider = StaticServerUrlProvider("https://books.example"),
            tokenStore = InMemoryTokenStore(
                AuthTokens(accessToken = "token", refreshToken = null, accessTokenExpiresAtMs = Long.MAX_VALUE / 2),
            ),
            deviceInfo = DeviceInfoDto(deviceId = "test-device"),
            nowMs = { 0L },
            http = HttpClient(
                MockEngine { request ->
                    requestSent = true
                    respond("", HttpStatusCode.OK)
                },
            ) {
                expectSuccess = false
                install(ContentNegotiation) { json(AbsJson) }
            },
        )

        try {
            absClient.queueEpisodesOnServer("li_podcast_1", emptyList())
        } finally {
            assertThat(requestSent).isFalse()
        }
    }

    @Test
    fun `clearServerEpisodeFetchQueue is a GET, matching the server's own choice of verb`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond("", HttpStatusCode.OK)
        }
        val absClient = AbsClient(
            serverUrlProvider = StaticServerUrlProvider("https://books.example"),
            tokenStore = InMemoryTokenStore(
                AuthTokens(accessToken = "token", refreshToken = null, accessTokenExpiresAtMs = Long.MAX_VALUE / 2),
            ),
            deviceInfo = DeviceInfoDto(deviceId = "test-device"),
            nowMs = { 0L },
            http = HttpClient(engine) {
                expectSuccess = false
                install(ContentNegotiation) { json(AbsJson) }
            },
        )

        absClient.clearServerEpisodeFetchQueue("li_podcast_1")

        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Get)
        assertThat(request.url.encodedPath).isEqualTo("/api/podcasts/li_podcast_1/clear-queue")
    }

    @Test
    fun `podcastFeedOnServer posts the rss url and unwraps the podcast envelope`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                """{"podcast":{"metadata":{"title":"An Invented Podcast"},"episodes":[],"numEpisodes":0}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val absClient = AbsClient(
            serverUrlProvider = StaticServerUrlProvider("https://books.example"),
            tokenStore = InMemoryTokenStore(
                AuthTokens(accessToken = "token", refreshToken = null, accessTokenExpiresAtMs = Long.MAX_VALUE / 2),
            ),
            deviceInfo = DeviceInfoDto(deviceId = "test-device"),
            nowMs = { 0L },
            http = HttpClient(engine) {
                expectSuccess = false
                install(ContentNegotiation) { json(AbsJson) }
            },
        )

        val feed = absClient.podcastFeedOnServer("https://feed.example/rss.xml")

        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.url.encodedPath).isEqualTo("/api/podcasts/feed")
        assertThat(feed.metadata?.title).isEqualTo("An Invented Podcast")
    }

    // endregion
}
