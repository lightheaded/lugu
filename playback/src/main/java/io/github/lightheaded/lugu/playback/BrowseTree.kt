package io.github.lightheaded.lugu.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.lightheaded.lugu.core.db.DownloadDao
import io.github.lightheaded.lugu.core.db.DownloadState
import io.github.lightheaded.lugu.core.db.EpisodeDao
import io.github.lightheaded.lugu.core.db.EpisodeEntity
import io.github.lightheaded.lugu.core.db.LibraryDao
import io.github.lightheaded.lugu.core.db.LibraryItemDao
import io.github.lightheaded.lugu.core.db.LibraryItemEntity
import io.github.lightheaded.lugu.core.db.QueueDao
import io.github.lightheaded.lugu.core.model.ContinueLabel
import io.github.lightheaded.lugu.core.model.FtsQuery
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import io.github.lightheaded.lugu.core.sync.LibrarySettings
import io.github.lightheaded.lugu.core.sync.QueuePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * The browse tree a car sees.
 *
 * Every node is served from Room and nothing here touches the network, which is the
 * whole design: a car connects the moment the phone is plugged in, often before any sync
 * has run and sometimes in a garage with no signal. A tree that needed the server would
 * be empty exactly when it is most needed.
 *
 * The shape is shallow on purpose. Driving attention is measured in glances, so the top
 * level answers "carry on with what I was doing" — Continue, Up next, Downloaded —
 * before it offers anything that has to be browsed.
 *
 * Hidden media types are honoured on every node and in search. Someone who has switched
 * podcasts off has said they do not want them in lugu, not that they do not want them on
 * the phone; a car that still offered them would be the one surface where the setting did
 * not apply, and the car is the surface where an unwanted row costs the most attention.
 */
@Singleton
class BrowseTree @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val itemDao: LibraryItemDao,
    private val episodeDao: EpisodeDao,
    private val libraryDao: LibraryDao,
    private val queueDao: QueueDao,
    private val downloadDao: DownloadDao,
    private val libraryPrefs: LibraryPrefs,
    private val queuePrefs: QueuePrefs,
) {
    /**
     * The root, which must exist even when signed out.
     *
     * Returning an error here is what sends Android Auto into a bind-retry loop
     * (androidx/media#3158): it re-binds the service, gets the same error, and repeats
     * until something gives up. A valid root holding one row that explains the problem
     * is both honest and stable.
     */
    fun root(): MediaItem = browsable(BrowseNode.Root, "lugu")

    suspend fun children(parentId: String): List<MediaItem> {
        val account = authRepository.account() ?: return listOf(
            disabled("Sign in on your phone to use lugu here"),
        )
        val server = account.serverId
        val user = account.userId
        val library = libraryPrefs.settings.first()

        return when (val node = BrowseNode.parse(parentId)) {
            BrowseNode.Root -> rootChildren(account, library)

            // One row per thing being listened to rather than per item: a podcast with three
            // part-heard episodes offers all three, each addressing its own episode, so the
            // car can start any of them without going through the show's page first. This
            // node used to group by item, and picking either of the other two episodes then
            // meant opening the podcast and finding it — several glances at a screen while
            // driving, which is the cost a car node exists to avoid.
            //
            // A row per episode is only useful if it says which episode it is, and that rule
            // is [ContinueLabel]'s: the same one the phone's own Continue shelf draws, held
            // in one place since the two had a copy each and were free to drift apart.
            BrowseNode.Continue -> itemDao.observeInProgress(server, user).first()
                .filter { library.isVisible(mediaTypeOf(it.item.mediaType)) }
                .map { row ->
                    playable(
                        node = BrowseNode.Playable(row.item.id, row.episodeId),
                        title = ContinueLabel.title(row.item.title, row.episodeTitle),
                        subtitle = ContinueLabel.subtitle(
                            itemTitle = row.item.title,
                            author = row.item.authorName,
                            episodeTitle = row.episodeTitle,
                        ),
                        coverUri = coverUri(row.item.id),
                    )
                }

            BrowseNode.UpNext -> queueDao.observeRows(server, user).first()
                .filter { library.isVisible(mediaTypeOf(it.mediaType)) }
                .map { row ->
                    playable(
                        node = BrowseNode.Playable(row.libraryItemId, row.episodeKey.ifEmpty { null }),
                        title = row.title.ifBlank { "Not in this library any more" },
                        subtitle = row.author,
                        coverUri = coverUri(row.libraryItemId),
                    )
                }

            BrowseNode.Downloaded -> downloadDao.observeAll(server, user).first()
                .filter { it.state == DownloadState.COMPLETED }
                .filter { library.isVisible(mediaTypeOf(it.mediaType)) }
                .map {
                    playable(
                        node = BrowseNode.Playable(it.libraryItemId, it.episodeKey.ifEmpty { null }),
                        title = it.title,
                        subtitle = it.author,
                        coverUri = coverUri(it.libraryItemId),
                    )
                }

            // A series is a book idea, so the whole node goes with books.
            BrowseNode.AllSeries -> if (!library.isVisible(MediaType.BOOK)) {
                emptyList()
            } else {
                itemDao.seriesTitles(server, user).map { browsable(BrowseNode.Series(it), it) }
            }

            // A car keeps ids across sessions, so a node hidden since it was last browsed
            // can still be asked for by id. Answering with nothing is the honest reply.
            BrowseNode.AllPodcasts -> if (!library.isVisible(MediaType.PODCAST)) {
                emptyList()
            } else {
                itemDao.byMediaType(server, user, PODCAST_MEDIA_TYPE)
                    .map { browsable(BrowseNode.Podcast(it.id), it.title, coverUri(it.id)) }
            }

            BrowseNode.LatestEpisodes -> latestEpisodes(account, library)

            BrowseNode.Libraries -> libraryDao.observeAll(server, user).first()
                .filter { library.isVisible(mediaTypeOf(it.mediaType)) }
                .map { browsable(BrowseNode.Library(it.id), it.name) }

            is BrowseNode.Series -> itemDao.bySeries(server, user, node.title)
                .filter { library.isVisible(mediaTypeOf(it.mediaType)) }
                .map { it.toMediaItem(account) }

            is BrowseNode.Podcast -> if (!library.isVisible(MediaType.PODCAST)) {
                emptyList()
            } else {
                // The stored order is newest first. A serial is listened to the other way
                // round, and in a car the first row is the one that gets pressed, so the
                // podcast's own answer decides which end of the archive that row is at.
                val episodes = episodeDao.observeForItem(server, user, node.itemId).first()
                val ordered = if (queuePrefs.podcastOldestFirst(node.itemId)) episodes.reversed() else episodes
                ordered.map { it.toMediaItem(node.itemId, account) }
            }

            is BrowseNode.Library -> itemDao.byLibrary(server, user, node.libraryId)
                .filter { library.isVisible(mediaTypeOf(it.mediaType)) }
                .map { it.toMediaItem(account) }

            // A leaf has no children, and an id we do not recognise gets an empty list
            // rather than an error — see [root] on why errors are the dangerous answer.
            is BrowseNode.Playable, BrowseNode.Unknown -> emptyList()
        }
    }

    /**
     * Voice search, and the search box in the car's own UI.
     *
     * The same FTS index the phone's search uses, so "play the dark forest in lugu"
     * finds what typing it would — and finds it with no connection.
     */
    suspend fun search(query: String): List<MediaItem> {
        val account = authRepository.account() ?: return emptyList()
        val library = libraryPrefs.settings.first()
        val match = FtsQuery.toMatchExpression(query)
        val rows = if (match != null) {
            itemDao.searchEverywhere(account.serverId, account.userId, match, SEARCH_LIMIT)
        } else {
            itemDao.searchEverywhereLike(account.serverId, account.userId, query, SEARCH_LIMIT)
        }
        // Spoken search is the one path that can reach an item without passing a node, so
        // filtering here is what stops "play me a podcast" working after podcasts were
        // switched off.
        return rows
            .filter { library.isVisible(mediaTypeOf(it.mediaType)) }
            .map { it.toMediaItem(account) }
    }

    /** One node by id, for a controller that asks about a node rather than browsing to it. */
    suspend fun item(mediaId: String): MediaItem? {
        val account = authRepository.account() ?: return null
        val library = libraryPrefs.settings.first()
        return when (val node = BrowseNode.parse(mediaId)) {
            is BrowseNode.Playable -> if (node.episodeId != null) {
                if (!library.isVisible(MediaType.PODCAST)) {
                    null
                } else {
                    episodeDao.byId(account.serverId, account.userId, node.episodeId)
                        ?.toMediaItem(node.itemId, account)
                }
            } else {
                itemDao.byId(account.serverId, account.userId, node.itemId)
                    ?.takeIf { library.isVisible(mediaTypeOf(it.mediaType)) }
                    ?.toMediaItem(account)
            }

            BrowseNode.Unknown -> null
            else -> browsable(node, node.id.substringAfterLast('/'))
        }
    }

    /**
     * The newest unplayed episodes across every podcast being followed, newest first.
     *
     * The one node that crosses podcasts, and the reason it is worth having: a podcast
     * listener with a dozen subscriptions otherwise has to open each one in turn to find
     * what has arrived, which is a dozen glances at a screen while driving. Publication
     * order rather than podcast order, because "what is new" is a question about time
     * (upstream app#679 and server#1516).
     *
     * Always newest first, whatever a podcast's own reading order says: this node is
     * the answer to what has just come out, and a per-podcast reading order is a statement
     * about how one show is worked through, not about what is new across all of them.
     */
    private suspend fun latestEpisodes(account: ActiveAccount, library: LibrarySettings): List<MediaItem> {
        if (!library.isVisible(MediaType.PODCAST)) return emptyList()
        val server = account.serverId
        val user = account.userId
        return itemDao.followedPodcasts(server, user)
            .flatMap { episodeDao.latestUnfinished(server, user, it.id, PER_PODCAST_LIMIT) }
            .sortedByDescending { it.publishedAtMs }
            .take(LATEST_EPISODES_LIMIT)
            .map { it.toMediaItem(it.libraryItemId, account) }
    }

    private suspend fun rootChildren(account: ActiveAccount, library: LibrarySettings): List<MediaItem> {
        val server = account.serverId
        val user = account.userId

        // A category that opens onto nothing is worse in a car than one that is not
        // there, so each is offered only if it has something in it. Continue and
        // Libraries always appear: between them they are the way back to everything.
        val hasQueue = queueDao.observeRows(server, user).first()
            .any { library.isVisible(mediaTypeOf(it.mediaType)) }
        val hasDownloads = downloadDao.observeAll(server, user).first()
            .any { it.state == DownloadState.COMPLETED && library.isVisible(mediaTypeOf(it.mediaType)) }
        val hasSeries = library.isVisible(MediaType.BOOK) && itemDao.seriesTitles(server, user).isNotEmpty()
        val hasPodcasts = library.isVisible(MediaType.PODCAST) &&
            itemDao.byMediaType(server, user, PODCAST_MEDIA_TYPE, limit = 1).isNotEmpty()
        // Asked one podcast at a time and stopping at the first hit, rather than by
        // building the whole list to count it: at the root the only question is whether
        // the row is worth showing, and the answer is usually the first podcast asked.
        val hasLatestEpisodes = library.isVisible(MediaType.PODCAST) &&
            itemDao.followedPodcasts(server, user).any {
                episodeDao.latestUnfinished(server, user, it.id, limit = 1).isNotEmpty()
            }

        return buildList {
            add(browsable(BrowseNode.Continue, "Continue"))
            if (hasQueue) add(browsable(BrowseNode.UpNext, "Up next"))
            // Above Downloaded because for a podcast listener this is the row that gets
            // pressed, and a car's first screen is only a few rows tall.
            if (hasLatestEpisodes) add(browsable(BrowseNode.LatestEpisodes, "Latest episodes"))
            if (hasDownloads) add(browsable(BrowseNode.Downloaded, "Downloaded"))
            if (hasSeries) add(browsable(BrowseNode.AllSeries, "Series"))
            if (hasPodcasts) add(browsable(BrowseNode.AllPodcasts, "Podcasts"))
            add(browsable(BrowseNode.Libraries, "Libraries"))
        }
    }

    /**
     * The stored media type as the settings understand it.
     *
     * Anything unrecognised reads as a book, which is what `MediaType.fromWire` does
     * everywhere else — a row with an odd type is still a row worth showing.
     */
    private fun mediaTypeOf(stored: String): MediaType = MediaType.fromWire(stored)

    private fun LibraryItemEntity.toMediaItem(account: ActiveAccount): MediaItem = playable(
        node = BrowseNode.Playable(id, null),
        title = title,
        subtitle = authorName,
        coverUri = coverUri(id),
    )

    private fun EpisodeEntity.toMediaItem(itemId: String, account: ActiveAccount): MediaItem = playable(
        node = BrowseNode.Playable(itemId, id),
        title = title,
        subtitle = subtitle,
        coverUri = coverUri(itemId),
    )

    private fun playable(
        node: BrowseNode.Playable,
        title: String,
        subtitle: String?,
        coverUri: Uri?,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(node.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(coverUri)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .build(),
        )
        .build()

    private fun browsable(node: BrowseNode, title: String, coverUri: Uri? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(node.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtworkUri(coverUri)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

    /** A row that explains itself and cannot be pressed. */
    private fun disabled(text: String): MediaItem = MediaItem.Builder()
        .setMediaId(BrowseNode.Unknown.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(text)
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    /**
     * Covers go out as `content://` rather than as server URLs.
     *
     * A car fetches artwork in its own process, which has none of lugu's authentication, so
     * every one of these used to come back 401 and every tile in the car was blank. Reading
     * this comes back into lugu instead — see [CoverProvider].
     *
     * A cover that still cannot be loaded, because there is no signal and none was cached, is
     * a blank tile and never a missing row: the title carries the meaning.
     */
    private fun coverUri(itemId: String): Uri = CoverProvider.uri(context, itemId)

    private companion object {
        /** Stored uppercase, from `MediaType.name` — see `LibraryRepository`. */
        const val PODCAST_MEDIA_TYPE = "PODCAST"

        const val SEARCH_LIMIT = 50

        /**
         * How far back into one podcast the latest-episodes node reaches.
         *
         * Small on purpose. Somebody with a hundred unplayed episodes of one show wants
         * that show's own node, not a list where it drowns out everything else.
         */
        const val PER_PODCAST_LIMIT = 3

        /** A car list nobody will scroll to the end of; enough to cover a week of feeds. */
        const val LATEST_EPISODES_LIMIT = 50
    }
}
