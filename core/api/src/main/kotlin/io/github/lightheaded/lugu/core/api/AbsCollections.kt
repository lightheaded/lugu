package io.github.lightheaded.lugu.core.api

import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/*
 * Collections, as extension functions on AbsClient rather than members of it.
 *
 * Everything here goes through the client's public `send`, so it inherits the proactive
 * token refresh and the single 401 retry without restating either.
 *
 * Shapes verified against the 2.36.0 server source — `server/routers/ApiRouter.js`,
 * `server/controllers/CollectionController.js` and `server/models/Collection.js` — and
 * against a live server of that version. The published API documentation disagrees with
 * the server on three points that matter here and the server was believed each time: it
 * documents a `userId` field that no longer exists, a `minified` parameter the collections
 * handler never reads, and 500s where the server now returns 400.
 */

/**
 * One collection as the server sends it.
 *
 * [books] is always present and always holds *expanded* library items — there is no
 * minified form of this response, whatever the documentation says — so a collection of
 * fifty books arrives with fifty complete item payloads, chapters and audio files
 * included. Only the ids are kept: everything else about an item is already mirrored, and
 * a second copy of it here would be a second thing to keep in step.
 *
 * [lastUpdate] is epoch milliseconds and is deliberately *not* used as a change token:
 * adding or removing a book writes only the join row, leaving this timestamp untouched.
 * Anything that trusted it to mean "membership changed" would miss every membership change.
 */
@Serializable
data class CollectionDto(
    val id: String = "",
    val libraryId: String = "",
    val name: String = "",
    val description: String? = null,
    val books: List<LibraryItemDto> = emptyList(),
    val lastUpdate: Long = 0,
    val createdAt: Long = 0,
)

/**
 * The library listing's envelope.
 *
 * Paged in shape only. The server builds every collection in full before slicing, so asking
 * for a page costs what asking for all of them costs; `sort`, `desc` and `filter` are
 * echoed back and then ignored. There is therefore nothing to gain from paging this, and
 * the default `limit=0` — meaning no limit — is what gets sent.
 */
@Serializable
data class CollectionsResponse(
    val results: List<CollectionDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val page: Int = 0,
)

/** The server names the member "book" even where it means a library item. */
@Serializable
data class CollectionBookRequest(val id: String)

/**
 * Every collection in one library.
 *
 * A podcast library answers with an empty list rather than an error: the endpoint exists
 * for every library, and the server simply refuses to put anything but a book in a
 * collection, so one can never be created there.
 */
suspend fun AbsClient.collections(libraryId: String): List<CollectionDto> =
    read<CollectionsResponse>("/api/libraries/$libraryId/collections").results

/**
 * One collection.
 *
 * Preferred over picking the same collection out of the library listing wherever both would
 * do, and not only because it is smaller: this is the one of the two that applies the
 * user's own tag and explicit-content restrictions to the books it returns.
 */
suspend fun AbsClient.collection(collectionId: String): CollectionDto =
    read("/api/collections/$collectionId")

/**
 * Adds one item, and answers with the collection as it now stands.
 *
 * The updated collection comes back in the response, so nothing needs to fetch it again to
 * find out where the server put it.
 */
suspend fun AbsClient.addToCollection(collectionId: String, libraryItemId: String): CollectionDto =
    read("/api/collections/$collectionId/book", HttpMethod.Post) {
        contentType(ContentType.Application.Json)
        setBody(CollectionBookRequest(libraryItemId))
    }

/**
 * Removes one item, and answers with the collection as it now stands.
 *
 * The last path segment is a *library item* id despite the server calling it `bookId`;
 * the server's own comment on the route says as much. Removing something that was not in
 * the collection succeeds and changes nothing, which is the state the caller wanted anyway.
 */
suspend fun AbsClient.removeFromCollection(
    collectionId: String,
    libraryItemId: String,
): CollectionDto = read("/api/collections/$collectionId/book/$libraryItemId", HttpMethod.Delete)

/**
 * Sends the request and insists on a success.
 *
 * The client has this already, as a private member; repeating it here is the price of
 * building on the public surface rather than reaching into the class.
 */
private suspend inline fun <reified T> AbsClient.read(
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
