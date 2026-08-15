package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.db.EpisodeDao
import io.github.lightheaded.lugu.core.db.LibraryItemDao
import javax.inject.Inject
import javax.inject.Singleton

/** An episode that was not there the last time lugu looked. */
data class NewEpisode(
    val libraryItemId: String,
    val episodeId: String,
    val podcastTitle: String,
    val episodeTitle: String,
    val publishedAtMs: Long,
)

/**
 * Finds out what podcasts have published since the last look.
 *
 * The library sync mirrors items but not their episodes — those only arrive with the
 * expanded fetch, which until now happened when someone opened a podcast. That is fine
 * for browsing and useless for "tell me when there is a new episode", which has to
 * happen while nobody is looking.
 *
 * Only podcasts already being listened to are refreshed. Audiobookshelf has no
 * subscription concept for a client to read, and pulling every episode of every podcast
 * on the server on a timer would be a lot of requests to answer a question nobody asked.
 */
@Singleton
class PodcastRefresher @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val itemDao: LibraryItemDao,
    private val episodeDao: EpisodeDao,
) {
    /**
     * Re-fetches followed podcasts and returns what is new.
     *
     * New is decided by comparing episode ids before and after, rather than by publish
     * date: a back-catalogue episode added to the feed late is still new to this phone,
     * and a clock that disagrees with the server cannot make it wrong.
     */
    suspend fun refreshFollowed(account: ActiveAccount): List<NewEpisode> {
        val podcasts = itemDao.followedPodcasts(account.serverId, account.userId)
        val found = mutableListOf<NewEpisode>()

        for (podcast in podcasts) {
            val before = episodeDao.forItem(account.serverId, account.userId, podcast.id)
                .map { it.id }
                .toSet()

            // A podcast that fails to refresh is skipped, not fatal: one unreachable
            // feed must not stop the others from being checked.
            libraryRepository.syncItemDetail(account, podcast.id).getOrElse { continue }

            episodeDao.forItem(account.serverId, account.userId, podcast.id)
                .filterNot { it.id in before }
                // First run on a podcast has no "before" to compare against, so
                // everything would look new. Silence is the right answer there.
                .takeIf { before.isNotEmpty() }
                ?.forEach {
                    found += NewEpisode(
                        libraryItemId = podcast.id,
                        episodeId = it.id,
                        podcastTitle = podcast.title,
                        episodeTitle = it.title,
                        publishedAtMs = it.publishedAtMs,
                    )
                }
        }

        return found.sortedByDescending { it.publishedAtMs }
    }
}
