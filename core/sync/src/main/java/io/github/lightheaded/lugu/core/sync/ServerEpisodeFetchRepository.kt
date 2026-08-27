package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.AbsHttpException
import io.github.lightheaded.lugu.core.api.AuthExpiredException
import io.github.lightheaded.lugu.core.api.PodcastFeedDto
import io.github.lightheaded.lugu.core.api.PodcastFeedEpisodeDto
import io.github.lightheaded.lugu.core.api.ServerEpisodeFetchDto
import io.github.lightheaded.lugu.core.api.checkNewEpisodesOnServer
import io.github.lightheaded.lugu.core.api.clearServerEpisodeFetchQueue
import io.github.lightheaded.lugu.core.api.podcastFeedOnServer
import io.github.lightheaded.lugu.core.api.queueEpisodesOnServer
import io.github.lightheaded.lugu.core.api.serverEpisodeFetchQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the Audiobookshelf **server** to fetch podcast episodes it does not hold yet.
 *
 * This is the other "download" — see `AbsPodcasts.kt`'s file KDoc. The file lands on the
 * server's disk, never on the phone, so this repository has nothing to do with
 * `:core:download` and nothing it stores here ever becomes a local, playable episode by
 * itself. If a fetch succeeds, the episode shows up in Room only once the ordinary library
 * sync next mirrors it — a socket `item_updated`/`episode_added` event or the next poll.
 *
 * **No offline queue, unlike progress.** Every method here is a direct request answered
 * with [Result], the same shape [CollectionRepository] uses for a collection edit and for
 * the same reason: retrying this later without being asked risks a real duplicate.
 * `checknew` itself is safe to repeat — the server compares against the podcast's own last
 * check — but [queueOnServer] takes an explicit list of feed episodes, and the server's own
 * queue only guards against a duplicate URL while something is *already downloading*; two
 * calls made while its queue is empty can each start a fetch. A request that fails while
 * offline is therefore reported and dropped, not stored to retry blindly once a connection
 * returns — the same trade [CollectionRepository] makes for a shared, server-owned action.
 *
 * Nothing here blocks a screen: the local Room mirror is what every screen renders from,
 * and this is only ever a fallible side request layered on top of it.
 */
@Singleton
class ServerEpisodeFetchRepository @Inject constructor(
    private val client: AbsClient,
) {
    /**
     * Asks the server to check this podcast's feed and fetch what is new — the single
     * action behind "get new episodes from the feed".
     *
     * One call both finds and fetches: the server queues every episode it reports here for
     * its own download, up to [limit]. See `AbsPodcasts.kt`'s KDoc on
     * `checkNewEpisodesOnServer` — there is no separate "just look" step through this
     * endpoint.
     */
    suspend fun checkAndFetchNewEpisodes(
        itemId: String,
        limit: Int = DEFAULT_LIMIT,
    ): Result<List<PodcastFeedEpisodeDto>> = request { client.checkNewEpisodesOnServer(itemId, limit) }

    /**
     * Queues an explicit list of feed episodes for the server to fetch — for a "browse the
     * whole feed" screen where someone picks specific episodes rather than accepting
     * whatever `checknew` would find.
     *
     * [episodes] must be [PodcastFeedEpisodeDto] values taken from the feed, typically via
     * [feedFromServer] filtered through
     * `io.github.lightheaded.lugu.core.api.episodesMissingFromServerLibrary`.
     */
    suspend fun queueOnServer(itemId: String, episodes: List<PodcastFeedEpisodeDto>): Result<Unit> =
        request { client.queueEpisodesOnServer(itemId, episodes) }

    /** The server's own fetch queue for this podcast: what it is fetching or waiting to fetch. */
    suspend fun fetchQueue(itemId: String): Result<List<ServerEpisodeFetchDto>> =
        request { client.serverEpisodeFetchQueue(itemId) }

    /** Drops whatever this podcast has waiting or in progress in the server's own queue. */
    suspend fun clearFetchQueue(itemId: String): Result<Unit> =
        request { client.clearServerEpisodeFetchQueue(itemId) }

    /** The podcast's whole feed, unfiltered, as the server parses it. */
    suspend fun feedFromServer(rssFeedUrl: String): Result<PodcastFeedDto> =
        request { client.podcastFeedOnServer(rssFeedUrl) }

    private suspend fun <T> request(call: suspend () -> T): Result<T> =
        runCatching { call() }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(fetchFailure(it)) },
        )

    private companion object {
        /**
         * Higher than the server's own bare default of 3 — see `checkNewEpisodesOnServer`'s
         * KDoc — because "fetch what I am missing" reads as "all of it", not "the newest
         * three". Still a cap, not an unbounded ask: a feed can carry decades of episodes.
         */
        const val DEFAULT_LIMIT = 25
    }
}

/**
 * Why asking the server to fetch episodes failed, in words worth putting in front of
 * somebody. Mirrors [CollectionRepository]'s `editFailure` — same shared, server-owned
 * action, same reasons it can be refused.
 */
private fun fetchFailure(cause: Throwable): Throwable = when {
    cause is AuthExpiredException -> cause
    // Not a network problem — episodes must be non-empty before anything is sent, and the
    // exception already says so; wrapping it as an unreachable-server message would hide
    // a caller bug behind an unrelated excuse.
    cause is IllegalArgumentException -> cause
    cause is AbsHttpException && cause.status == 403 ->
        IllegalStateException("Your account is not allowed to ask the server to fetch episodes.", cause)
    cause is AbsHttpException && cause.status == 400 ->
        IllegalStateException("The server could not read this podcast's feed.", cause)
    cause is AbsHttpException -> IllegalStateException(
        "The server refused the request (HTTP ${cause.status}).",
        cause,
    )
    else -> IllegalStateException(
        "The server can only fetch episodes while it is reachable.",
        cause,
    )
}
