package io.github.lightheaded.lugu.core.api

import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/*
 * The library's series, as extension functions on AbsClient for the same reason as
 * collections: everything here goes through the client's public `send`, so it inherits
 * the proactive token refresh and the single 401 retry without restating either.
 *
 * Shapes verified against the 2.36.0 server source — `server/controllers/LibraryController.js`
 * (`getAllSeriesForLibrary`), `server/utils/queries/seriesFilters.js` (`getFilteredSeries`)
 * and `server/models/Series.js`. This endpoint exists because the paged item listing does
 * not carry structured series membership at all: the minified book payload has only the
 * joined `seriesName` string.
 */

/**
 * One series and everything in it, in the server's own order.
 *
 * [books] is the load-bearing field and it is a **rendering of an ordering**, not a list
 * of sequences: the server sorts the join rows by their sequence string with a natural
 * comparator and then emits each member as a complete *minified* library item, which
 * carries no sequence of its own. So position in this list is what the server knows about
 * reading order, and the sequence for a given book has to come from somewhere else.
 *
 * That ordering is trustworthy exactly as far as the sequences behind it go. Where no
 * member of a series has one, the server's comparator has nothing to compare and the
 * order that comes back is the order the scanner happened to insert the rows — see the
 * KDoc on `ItemSeriesEntity.serverRank` for what lugu does and does not do with that.
 */
@Serializable
data class LibrarySeriesDto(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val libraryId: String = "",
    val books: List<LibraryItemDto> = emptyList(),
)

/**
 * The listing envelope, with only the two fields worth reading.
 *
 * `limit` and `page` come back as whatever was sent — the handler echoes the raw query
 * string rather than the numbers it used — so they are deliberately not declared: a
 * server that echoed `"50"` would otherwise fail the whole page on a type mismatch.
 * `minified` is echoed the same way and is never read by the handler, which is the same
 * defect the collections listing has and the reason this call is rate-limited.
 */
@Serializable
data class LibrarySeriesResponse(
    val results: List<LibrarySeriesDto> = emptyList(),
    val total: Int = 0,
)

/**
 * One page of a library's series.
 *
 * Paged explicitly, and unlike the collections listing this paging is real: the handler
 * passes `limit` and `offset` straight into the database query, so asking for fifty
 * series costs fifty series rather than all of them. The default of `limit=0` means "no
 * limit" and is never sent, for the same reason it is never sent to the item listing.
 *
 * A podcast library answers with an empty list. Series are a property of books on this
 * server, so there is nothing there to fail on.
 */
suspend fun AbsClient.librarySeries(
    libraryId: String,
    page: Int,
    limit: Int = DEFAULT_SERIES_PAGE_SIZE,
): LibrarySeriesResponse {
    require(limit > 0) { "limit=0 asks the server for every series in one response" }
    val response = send("/api/libraries/$libraryId/series?limit=$limit&page=$page", HttpMethod.Get)
    if (!response.status.isSuccess()) {
        throw AbsHttpException(response.status.value, response.bodyAsText().take(300))
    }
    return response.body()
}

/**
 * Smaller than the item listing's page size, because a page here is not a page of items.
 *
 * Every member of every series on the page arrives as a complete minified library item,
 * so fifty long series is already several thousand item payloads. Fifty keeps one
 * response comfortably inside a phone's patience while still finishing an ordinary
 * library in a handful of requests.
 */
const val DEFAULT_SERIES_PAGE_SIZE = 50
