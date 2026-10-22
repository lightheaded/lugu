package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.AbsJson
import io.github.lightheaded.lugu.core.api.DeviceInfoDto
import io.github.lightheaded.lugu.core.api.InMemoryTokenStore
import io.github.lightheaded.lugu.core.api.StaticServerUrlProvider
import io.github.lightheaded.lugu.core.db.CollectionDao
import io.github.lightheaded.lugu.core.db.CollectionEntity
import io.github.lightheaded.lugu.core.db.CollectionItemEntity
import io.github.lightheaded.lugu.core.db.CollectionSummary
import io.github.lightheaded.lugu.core.db.LibraryDao
import io.github.lightheaded.lugu.core.db.LibraryItemEntity
import io.github.lightheaded.lugu.core.model.AuthTokens
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [CollectionRepository.addAll]: what its Result says, and what it stores.
 *
 * The count is the point. The server's batch route answers with a success even when it
 * dropped some of the ids, so the repository has to read the count from the collection in
 * the reply, and the screen's line depends on that number being true.
 */
class CollectionBatchAddTest {

    private val account = ActiveAccount(
        serverId = "srv_1",
        baseUrl = "https://books.example",
        userId = "usr_1",
        username = "reader",
        defaultLibraryId = null,
    )

    private val dao = RecordingCollectionDao()
    private val requests = mutableListOf<HttpRequestData>()

    private fun repository(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = CollectionRepository(
        client = AbsClient(
            serverUrlProvider = StaticServerUrlProvider("https://books.example"),
            tokenStore = InMemoryTokenStore(
                AuthTokens(accessToken = "token", refreshToken = null, accessTokenExpiresAtMs = Long.MAX_VALUE / 2),
            ),
            deviceInfo = DeviceInfoDto(deviceId = "test-device"),
            nowMs = { 0L },
            http = HttpClient(
                MockEngine { request ->
                    requests += request
                    handler(request)
                },
            ) {
                expectSuccess = false
                install(ContentNegotiation) { json(AbsJson) }
            },
        ),
        collectionDao = dao,
        libraryDao = unusedLibraryDao(),
        clock = object : Clock {
            override fun nowMs(): Long = 42L
        },
    )

    private fun MockRequestHandleScope.collection(vararg ids: String): HttpResponseData = respond(
        """{"id":"col_1","libraryId":"lib_1","name":"An Invented Shelf",""" +
            """"books":[${ids.joinToString(",") { """{"id":"$it"}""" }}]}""",
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
    )

    @Test
    fun `every id in the reply counts, and the reply is stored in its order`() = runTest {
        val repository = repository { collection("li_0", "li_1", "li_2") }

        val result = repository.addAll(account, "col_1", listOf("li_1", "li_2"))

        assertThat(result.getOrThrow()).isEqualTo(2)
        assertThat(dao.items.map { it.libraryItemId }).containsExactly("li_0", "li_1", "li_2").inOrder()
        assertThat(dao.items.map { it.position }).containsExactly(0, 1, 2).inOrder()
        assertThat(dao.collections.single().name).isEqualTo("An Invented Shelf")
    }

    @Test
    fun `an id the server dropped is not counted`() = runTest {
        val repository = repository { collection("li_1") }

        val result = repository.addAll(account, "col_1", listOf("li_1", "li_2"))

        assertThat(result.getOrThrow()).isEqualTo(1)
    }

    @Test
    fun `a repeated id is sent and counted once`() = runTest {
        val repository = repository { collection("li_1") }

        val result = repository.addAll(account, "col_1", listOf("li_1", "li_1"))

        assertThat(result.getOrThrow()).isEqualTo(1)
    }

    @Test
    fun `an empty list sends nothing`() = runTest {
        val repository = repository { collection() }

        val result = repository.addAll(account, "col_1", emptyList())

        assertThat(result.getOrThrow()).isEqualTo(0)
        assertThat(requests).isEmpty()
    }

    @Test
    fun `no server fails at once with the offline reason, and stores nothing`() = runTest {
        val repository = repository { throw IOException("unreachable") }

        val result = repository.addAll(account, "col_1", listOf("li_1"))

        assertThat(result.exceptionOrNull()?.message)
            .isEqualTo("A collection can only be changed while the server is reachable.")
        assertThat(dao.collections).isEmpty()
        assertThat(dao.items).isEmpty()
    }

    @Test
    fun `a 403 says the account may not change collections`() = runTest {
        val repository = repository { respond("", HttpStatusCode.Forbidden) }

        val result = repository.addAll(account, "col_1", listOf("li_1"))

        assertThat(result.exceptionOrNull()?.message)
            .isEqualTo("Your account is not allowed to change collections.")
    }

    @Test
    fun `a 404 says the collection is gone`() = runTest {
        val repository = repository { respond("Collection not found", HttpStatusCode.NotFound) }

        val result = repository.addAll(account, "col_1", listOf("li_1"))

        assertThat(result.exceptionOrNull()?.message).isEqualTo("That collection is no longer on the server.")
    }
}

/** Keeps the last write of each kind, which is all the batch path makes. */
private class RecordingCollectionDao : CollectionDao {
    var collections: List<CollectionEntity> = emptyList()
    var items: List<CollectionItemEntity> = emptyList()

    override fun observeAll(serverId: String, userId: String, libraryId: String?): Flow<List<CollectionSummary>> =
        flowOf(emptyList())

    override fun observeItems(serverId: String, userId: String, collectionId: String): Flow<List<LibraryItemEntity>> =
        flowOf(emptyList())

    override fun observeMembership(serverId: String, userId: String, itemId: String): Flow<List<String>> =
        flowOf(emptyList())

    override suspend fun byId(serverId: String, userId: String, id: String): CollectionEntity? = null

    override suspend fun upsertAll(collections: List<CollectionEntity>) {
        this.collections = collections
    }

    override suspend fun upsertItems(items: List<CollectionItemEntity>) {
        this.items = items
    }

    override suspend fun clearItems(serverId: String, userId: String, collectionId: String) {
        items = emptyList()
    }

    override suspend fun deleteStale(serverId: String, userId: String, before: Long) = Unit

    override suspend fun clearAllItems(serverId: String, userId: String) {
        items = emptyList()
    }
}

/** The batch path never reads the library mirror. A call here is a test failure. */
private fun unusedLibraryDao(): LibraryDao = Proxy.newProxyInstance(
    LibraryDao::class.java.classLoader,
    arrayOf(LibraryDao::class.java),
) { _, method, _ -> error("LibraryDao.${method.name} was not expected") } as LibraryDao
