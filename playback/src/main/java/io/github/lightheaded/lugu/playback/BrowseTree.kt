package io.github.lightheaded.lugu.playback

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
import io.github.lightheaded.lugu.core.model.FtsQuery
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
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
 */
@Singleton
class BrowseTree @Inject constructor(
    private val authRepository: AuthRepository,
    private val itemDao: LibraryItemDao,
    private val episodeDao: EpisodeDao,
    private val libraryDao: LibraryDao,
    private val queueDao: QueueDao,
    private val downloadDao: DownloadDao,
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

        return when (val node = BrowseNode.parse(parentId)) {
            BrowseNode.Root -> rootChildren(account)

            BrowseNode.Continue -> itemDao.observeContinueListening(server, user).first()
                .map { it.toMediaItem(account) }

            BrowseNode.UpNext -> queueDao.observeRows(server, user).first().map { row ->
                playable(
                    node = BrowseNode.Playable(row.libraryItemId, row.episodeKey.ifEmpty { null }),
                    title = row.title.ifBlank { "Not in this library any more" },
                    subtitle = row.author,
                    coverUrl = coverUrl(account, row.libraryItemId),
                )
            }

            BrowseNode.Downloaded -> downloadDao.observeAll(server, user).first()
                .filter { it.state == DownloadState.COMPLETED }
                .map {
                    playable(
                        node = BrowseNode.Playable(it.libraryItemId, it.episodeKey.ifEmpty { null }),
                        title = it.title,
                        subtitle = it.author,
                        coverUrl = coverUrl(account, it.libraryItemId),
                    )
                }

            BrowseNode.AllSeries -> itemDao.seriesTitles(server, user)
                .map { browsable(BrowseNode.Series(it), it) }

            BrowseNode.AllPodcasts -> itemDao.byMediaType(server, user, PODCAST_MEDIA_TYPE)
                .map { browsable(BrowseNode.Podcast(it.id), it.title, coverUrl(account, it.id)) }

            BrowseNode.Libraries -> libraryDao.observeAll(server, user).first()
                .map { browsable(BrowseNode.Library(it.id), it.name) }

            is BrowseNode.Series -> itemDao.bySeries(server, user, node.title).map { it.toMediaItem(account) }

            is BrowseNode.Podcast -> episodeDao.observeForItem(server, user, node.itemId).first()
                .map { it.toMediaItem(node.itemId, account) }

            is BrowseNode.Library -> itemDao.byLibrary(server, user, node.libraryId)
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
        val match = FtsQuery.toMatchExpression(query)
        val rows = if (match != null) {
            itemDao.searchEverywhere(account.serverId, account.userId, match, SEARCH_LIMIT)
        } else {
            itemDao.searchEverywhereLike(account.serverId, account.userId, query, SEARCH_LIMIT)
        }
        return rows.map { it.toMediaItem(account) }
    }

    /** One node by id, for a controller that asks about a node rather than browsing to it. */
    suspend fun item(mediaId: String): MediaItem? {
        val account = authRepository.account() ?: return null
        return when (val node = BrowseNode.parse(mediaId)) {
            is BrowseNode.Playable -> if (node.episodeId != null) {
                episodeDao.byId(account.serverId, account.userId, node.episodeId)
                    ?.toMediaItem(node.itemId, account)
            } else {
                itemDao.byId(account.serverId, account.userId, node.itemId)?.toMediaItem(account)
            }

            BrowseNode.Unknown -> null
            else -> browsable(node, node.id.substringAfterLast('/'))
        }
    }

    private suspend fun rootChildren(account: ActiveAccount): List<MediaItem> {
        val server = account.serverId
        val user = account.userId

        // A category that opens onto nothing is worse in a car than one that is not
        // there, so each is offered only if it has something in it. Continue and
        // Libraries always appear: between them they are the way back to everything.
        val hasQueue = queueDao.all(server, user).isNotEmpty()
        val hasDownloads = downloadDao.observeAll(server, user).first()
            .any { it.state == DownloadState.COMPLETED }
        val hasSeries = itemDao.seriesTitles(server, user).isNotEmpty()
        val hasPodcasts = itemDao.byMediaType(server, user, PODCAST_MEDIA_TYPE, limit = 1).isNotEmpty()

        return buildList {
            add(browsable(BrowseNode.Continue, "Continue"))
            if (hasQueue) add(browsable(BrowseNode.UpNext, "Up next"))
            if (hasDownloads) add(browsable(BrowseNode.Downloaded, "Downloaded"))
            if (hasSeries) add(browsable(BrowseNode.AllSeries, "Series"))
            if (hasPodcasts) add(browsable(BrowseNode.AllPodcasts, "Podcasts"))
            add(browsable(BrowseNode.Libraries, "Libraries"))
        }
    }

    private fun LibraryItemEntity.toMediaItem(account: ActiveAccount): MediaItem = playable(
        node = BrowseNode.Playable(id, null),
        title = title,
        subtitle = authorName,
        coverUrl = coverUrl(account, id),
    )

    private fun EpisodeEntity.toMediaItem(itemId: String, account: ActiveAccount): MediaItem = playable(
        node = BrowseNode.Playable(itemId, id),
        title = title,
        subtitle = subtitle,
        coverUrl = coverUrl(account, itemId),
    )

    private fun playable(
        node: BrowseNode.Playable,
        title: String,
        subtitle: String?,
        coverUrl: String?,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(node.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(coverUrl?.let(Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .build(),
        )
        .build()

    private fun browsable(node: BrowseNode, title: String, coverUrl: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(node.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtworkUri(coverUrl?.let(Uri::parse))
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
     * Covers are server URLs, which a car may fail to load with no connection. That is a
     * blank tile, never a missing row — the title carries the meaning.
     */
    private fun coverUrl(account: ActiveAccount, itemId: String): String =
        "${account.baseUrl}/api/items/$itemId/cover?width=400"

    private companion object {
        /** Stored uppercase, from `MediaType.name` — see `LibraryRepository`. */
        const val PODCAST_MEDIA_TYPE = "PODCAST"

        const val SEARCH_LIMIT = 50
    }
}
