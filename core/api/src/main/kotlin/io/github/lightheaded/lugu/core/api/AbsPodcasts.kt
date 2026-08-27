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
 * Server-side episode fetch, as extension functions on AbsClient.
 *
 * This is the OTHER "download": Audiobookshelf reading a podcast's RSS feed and pulling
 * episodes into the SERVER's own library folder. Nothing here ever puts a file on the
 * phone — that is `:core:download`, a different module owned by a different piece of work.
 * Names in this file spell out "server" or "queue" rather than a bare "download" for
 * exactly that reason.
 *
 * Shapes verified against the 2.36.0-era server source on `master` — no live server was
 * reachable while this was written:
 *   - `server/routers/ApiRouter.js` for the routes and their HTTP methods.
 *   - `server/controllers/PodcastController.js` for `checkNewEpisodes`, `downloadEpisodes`,
 *     `getEpisodeDownloads`, `clearEpisodeDownloadQueue`, `getPodcastFeed`.
 *   - `server/managers/PodcastManager.js` for what `checkAndDownloadNewEpisodes` and
 *     `downloadPodcastEpisodes` actually do.
 *   - `server/utils/podcastUtils.js` for the `RssPodcastEpisode` shape a feed episode has.
 *   - `server/objects/PodcastEpisodeDownload.js` for `toJSONForClient()`, the shape of one
 *     row in the server-side download queue.
 *
 * The route doc-comment in `PodcastController.js` says `GET /api/podcasts/:id/checknew`,
 * and `ApiRouter.js` registers it with `router.get(...)`. A brief given for this task
 * described it as a POST; the server source is the source of truth and it is a GET.
 *
 * The sharp edge: [checkNewEpisodesOnServer] does not just report what is new. It calls
 * `checkAndDownloadNewEpisodes`, which finds episodes published after the podcast's last
 * check and immediately queues every one it finds (up to `limit`, default 3) for the
 * server to fetch — there is no separate confirmation step and no library setting that
 * turns this off. So one call both asks "what's new" and starts the fetch. There is no
 * per-request opt-out; a caller that only wants to *look* would have to use
 * [podcastFeedOnServer] and diff it against what the library already holds instead.
 *
 * [download-episodes] exists for the opposite case: an explicit list of feed episodes
 * (e.g. from [podcastFeedOnServer] or a search) that did not come from `checknew` and were
 * not already queued by it. It takes the request body as a bare JSON array — not wrapped
 * in an object — and every element must be shaped exactly like the feed gave it: the
 * server hands it straight to `PodcastEpisodeDownload.setData()`, which reads
 * `enclosure.url` off it directly. A library `EpisodeDto` (an episode already on the
 * server) is the wrong shape for this call; only [PodcastFeedEpisodeDto], as returned by
 * `checknew` or the feed endpoint, is accepted.
 */

/**
 * One episode as the RSS feed describes it — not yet on the server, so it has no
 * `EpisodeDto.id` and no `audioTrack`. This is the exact shape `download-episodes` expects
 * back, because the server does not resolve it against anything before queuing a fetch.
 *
 * [guid] and [enclosure] are what the server itself uses to tell "already have this one"
 * from "new" (`Podcast.checkHasEpisodeByFeedEpisode`): it matches on `guid`, and failing
 * that on `enclosure.url`. Both are read here for the same comparison — see
 * [episodesMissingFromServerLibrary].
 */
@Serializable
data class PodcastFeedEpisodeDto(
    val title: String = "",
    val subtitle: String? = null,
    val description: String? = null,
    val pubDate: String? = null,
    val episodeType: String? = null,
    val season: String? = null,
    val episode: String? = null,
    val author: String? = null,
    val duration: String? = null,
    val durationSeconds: Double? = null,
    val explicit: String? = null,
    /** Unix milliseconds; null when the feed's `pubDate` did not parse as a date. */
    val publishedAt: Long? = null,
    val enclosure: PodcastEnclosureDto? = null,
    val guid: String? = null,
    val chaptersUrl: String? = null,
    val chaptersType: String? = null,
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
data class PodcastEnclosureDto(
    val url: String = "",
    val type: String? = null,
    /** Bytes, but sent by the feed as a string — see [PodcastFeedEpisodeDto]'s KDoc. */
    val length: String? = null,
)

@Serializable
data class PodcastFeedMetadataDto(
    val title: String? = null,
    val author: String? = null,
    val description: String? = null,
    val image: String? = null,
    val feedUrl: String? = null,
    val explicit: String? = null,
)

@Serializable
data class PodcastFeedDto(
    val metadata: PodcastFeedMetadataDto? = null,
    val episodes: List<PodcastFeedEpisodeDto> = emptyList(),
    val numEpisodes: Int? = null,
)

@Serializable
internal data class PodcastFeedResponse(val podcast: PodcastFeedDto? = null)

@Serializable
internal data class PodcastFeedRequest(val rssFeed: String)

@Serializable
internal data class CheckNewEpisodesResponse(val episodes: List<PodcastFeedEpisodeDto> = emptyList())

/**
 * One row of the server's own episode-fetch queue — `PodcastEpisodeDownload.toJSONForClient()`.
 * [id] identifies the queue entry, not a library episode; a fetch still in progress or still
 * waiting its turn has no [libraryItemId] episode to point at yet.
 */
@Serializable
data class ServerEpisodeFetchDto(
    val id: String? = null,
    val episodeDisplayTitle: String? = null,
    val url: String? = null,
    val libraryItemId: String? = null,
    val libraryId: String? = null,
    val isFinished: Boolean = false,
    val failed: Boolean = false,
    val podcastTitle: String? = null,
    val season: String? = null,
    val episode: String? = null,
    val publishedAt: Long? = null,
    val guid: String? = null,
    val startedAt: Long? = null,
    val createdAt: Long? = null,
    val finishedAt: Long? = null,
)

@Serializable
internal data class ServerEpisodeFetchQueueResponse(val downloads: List<ServerEpisodeFetchDto> = emptyList())

/**
 * Asks the server to re-read this podcast's RSS feed and fetch what it does not have yet.
 *
 * One call both checks and fetches — see this file's KDoc. [limit] caps how many episodes
 * the server will queue from this one call; the server itself defaults to 3 when no
 * `limit` query parameter is sent, so it is passed explicitly here rather than relying on
 * a default the app does not control.
 *
 * Requires an admin-or-up account on the server (`req.user.isAdminOrUp`); a lesser account
 * gets a 403, surfaced to the caller as [AbsHttpException].
 */
suspend fun AbsClient.checkNewEpisodesOnServer(
    itemId: String,
    limit: Int = DEFAULT_NEW_EPISODE_LIMIT,
): List<PodcastFeedEpisodeDto> =
    podcastRead<CheckNewEpisodesResponse>("/api/podcasts/$itemId/checknew?limit=$limit").episodes

/**
 * Queues an explicit list of feed episodes for the server to fetch, bypassing `checknew`'s
 * own comparison against the podcast's last check.
 *
 * [episodes] must be [PodcastFeedEpisodeDto] values taken from the feed — from
 * [checkNewEpisodesOnServer] or [podcastFeedOnServer] — and not library episodes; see this
 * file's KDoc for why. An empty list is refused by the server with a 400, so it is refused
 * here first with a clearer message.
 */
suspend fun AbsClient.queueEpisodesOnServer(itemId: String, episodes: List<PodcastFeedEpisodeDto>) {
    require(episodes.isNotEmpty()) { "queueEpisodesOnServer called with no episodes to queue" }
    val response = send("/api/podcasts/$itemId/download-episodes", HttpMethod.Post) {
        contentType(ContentType.Application.Json)
        setBody(episodes)
    }
    if (!response.status.isSuccess()) {
        throw AbsHttpException(response.status.value, response.bodyAsText().take(300))
    }
}

/** The server's own fetch queue for this podcast — what it has queued, is fetching, or has finished. */
suspend fun AbsClient.serverEpisodeFetchQueue(itemId: String): List<ServerEpisodeFetchDto> =
    podcastRead<ServerEpisodeFetchQueueResponse>("/api/podcasts/$itemId/downloads").downloads

/**
 * Empties the server's own fetch queue for this podcast. A queue entry already finished
 * fetching is unaffected — this only drops what is waiting or in progress.
 *
 * The server exposes this as `GET`, not `DELETE` (verified in `ApiRouter.js`); that is the
 * server's choice of verb, not a mistake carried over here.
 */
suspend fun AbsClient.clearServerEpisodeFetchQueue(itemId: String) {
    val response = send("/api/podcasts/$itemId/clear-queue", HttpMethod.Get)
    if (!response.status.isSuccess()) {
        throw AbsHttpException(response.status.value, response.bodyAsText().take(300))
    }
}

/**
 * Parses a podcast RSS feed on the server and returns it, unfiltered — every episode the
 * feed carries, not only the ones the library is missing. Pair with
 * [episodesMissingFromServerLibrary] to find which of them are worth queuing.
 */
suspend fun AbsClient.podcastFeedOnServer(rssFeedUrl: String): PodcastFeedDto {
    val response = send("/api/podcasts/feed", HttpMethod.Post) {
        contentType(ContentType.Application.Json)
        setBody(PodcastFeedRequest(rssFeedUrl))
    }
    if (!response.status.isSuccess()) {
        throw AbsHttpException(response.status.value, response.bodyAsText().take(300))
    }
    return response.body<PodcastFeedResponse>().podcast ?: PodcastFeedDto()
}

/**
 * Which of [feedEpisodes] the library does not already hold, so a "browse the whole feed"
 * screen does not offer to queue an episode a second time.
 *
 * Mirrors the server's own `Podcast.checkHasEpisodeByFeedEpisode`: an episode already held
 * is identified by [PodcastFeedEpisodeDto.guid] first, and by its enclosure URL when a feed
 * carries no guid. [knownGuidsOrEnclosureUrls] is therefore built the same way — a set of
 * every held episode's guid (falling back to its enclosure URL) — by whichever caller has
 * that information; it is not read out of Room here; Room stores no guid or enclosure URL
 * today, see this feature's report for what a schema change to add one would cost.
 */
fun episodesMissingFromServerLibrary(
    feedEpisodes: List<PodcastFeedEpisodeDto>,
    knownGuidsOrEnclosureUrls: Set<String>,
): List<PodcastFeedEpisodeDto> = feedEpisodes.filterNot { episode ->
    val identity = episode.guid?.takeIf { it.isNotBlank() } ?: episode.enclosure?.url?.takeIf { it.isNotBlank() }
    identity != null && identity in knownGuidsOrEnclosureUrls
}

private const val DEFAULT_NEW_EPISODE_LIMIT = 25

/**
 * Sends the request and insists on a success. The client has this already as a private
 * member; repeating it here is the price of building on the public surface rather than
 * reaching into the class — the same trade [AbsCollections.kt] makes.
 */
private suspend inline fun <reified T> AbsClient.podcastRead(
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
