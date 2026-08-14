package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.AbsJson
import io.github.lightheaded.lugu.core.api.LibraryItemDto
import io.github.lightheaded.lugu.core.api.toDomain
import io.github.lightheaded.lugu.core.db.ChapterDao
import io.github.lightheaded.lugu.core.db.ChapterEntity
import io.github.lightheaded.lugu.core.db.EpisodeDao
import io.github.lightheaded.lugu.core.db.EpisodeEntity
import io.github.lightheaded.lugu.core.db.LibraryDao
import io.github.lightheaded.lugu.core.db.LibraryEntity
import io.github.lightheaded.lugu.core.db.LibraryItemDao
import io.github.lightheaded.lugu.core.db.LibraryItemEntity
import io.github.lightheaded.lugu.core.model.Chapters
import io.github.lightheaded.lugu.core.model.Library
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.model.PodcastEpisode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The library mirror.
 *
 * Reads always come from Room, never from the network — that is the whole point of
 * docs/PLAN.md §4.1. A cold start with no connectivity still shows the full library.
 * The network only ever *writes* into Room.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val client: AbsClient,
    private val libraryDao: LibraryDao,
    private val itemDao: LibraryItemDao,
    private val episodeDao: EpisodeDao,
    private val chapterDao: ChapterDao,
    private val clock: Clock,
) {
    fun observeLibraries(account: ActiveAccount): Flow<List<Library>> =
        libraryDao.observeAll(account.serverId, account.userId).map { rows ->
            rows.map { Library(it.id, it.name, MediaType.fromWire(it.mediaType), it.displayOrder) }
        }

    fun observeItems(account: ActiveAccount, libraryId: String): Flow<List<LibraryItem>> =
        itemDao.observeByLibrary(account.serverId, account.userId, libraryId).map { rows ->
            rows.map { it.toDomain() }
        }

    fun search(account: ActiveAccount, libraryId: String, query: String): Flow<List<LibraryItem>> =
        itemDao.search(account.serverId, account.userId, libraryId, query).map { rows ->
            rows.map { it.toDomain() }
        }

    fun observeItem(account: ActiveAccount, itemId: String): Flow<LibraryItem?> =
        itemDao.observeById(account.serverId, account.userId, itemId).map { it?.toDomain() }

    fun observeEpisodes(account: ActiveAccount, itemId: String): Flow<List<PodcastEpisode>> =
        episodeDao.observeForItem(account.serverId, account.userId, itemId).map { rows ->
            rows.map {
                PodcastEpisode(
                    id = it.id,
                    libraryItemId = it.libraryItemId,
                    title = it.title,
                    subtitle = it.subtitle,
                    description = it.description,
                    episodeNumber = it.episodeNumber,
                    season = it.season,
                    publishedAtMs = it.publishedAtMs,
                    durationSec = it.durationSec,
                    index = it.position,
                )
            }
        }

    fun observeContinueListening(account: ActiveAccount): Flow<List<LibraryItem>> =
        itemDao.observeContinueListening(account.serverId, account.userId).map { rows ->
            rows.map { it.toDomain() }
        }

    suspend fun itemCount(account: ActiveAccount): Int = itemDao.count(account.serverId, account.userId)

    /** Refreshes the list of libraries, dropping ones the server no longer has. */
    suspend fun syncLibraries(account: ActiveAccount): Result<List<Library>> = runCatching {
        val remote = client.libraries()
        val entities = remote.map {
            LibraryEntity(
                serverId = account.serverId,
                userId = account.userId,
                id = it.id,
                name = it.name,
                mediaType = it.mediaType ?: "book",
                displayOrder = it.displayOrder,
            )
        }
        libraryDao.upsertAll(entities)
        libraryDao.deleteMissing(account.serverId, account.userId, remote.map { it.id })
        remote.map { it.toDomain() }
    }

    /**
     * Full paged mirror of one library.
     *
     * Pages are always explicitly sized: `limit=0` makes this server return every row
     * in one response, which is a memory cliff on a large library. Rows untouched by
     * the pass are swept afterwards, which is how deletions propagate without needing
     * a socket event.
     */
    suspend fun syncLibraryItems(
        account: ActiveAccount,
        libraryId: String,
        onProgress: (synced: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<Int> = runCatching {
        val startedAt = clock.nowMs()
        var page = 0
        var synced = 0
        var total = Int.MAX_VALUE

        while (synced < total) {
            val response = client.libraryItems(libraryId, page = page, limit = AbsClient.DEFAULT_PAGE_SIZE)
            if (response.results.isEmpty()) break
            total = response.total.takeIf { it > 0 } ?: response.results.size

            itemDao.upsertAll(response.results.map { it.toEntity(account, libraryId, startedAt) })

            synced += response.results.size
            page += 1
            onProgress(synced, total)
        }

        itemDao.deleteStale(account.serverId, account.userId, libraryId, startedAt)
        synced
    }

    /**
     * Pulls one item in full: chapters, episodes and the long-tail metadata that the
     * minified list payload leaves out.
     */
    suspend fun syncItemDetail(account: ActiveAccount, itemId: String): Result<Unit> = runCatching {
        val dto = client.item(itemId, expanded = true)
        itemDao.upsertAll(listOf(dto.toEntity(account, dto.libraryId, clock.nowMs())))

        val duration = dto.media?.duration ?: 0.0
        val chapters = Chapters.normalise(dto.media?.chapters.orEmpty().map { it.toDomain() }, duration)
        chapterDao.replaceForItem(
            account.serverId,
            account.userId,
            itemId,
            chapters.map {
                ChapterEntity(
                    serverId = account.serverId,
                    userId = account.userId,
                    libraryItemId = itemId,
                    chapterIndex = it.id,
                    startSec = it.startSec,
                    endSec = it.endSec,
                    title = it.title,
                )
            },
        )

        val episodes = dto.media?.episodes.orEmpty()
        if (episodes.isNotEmpty()) {
            episodeDao.upsertAll(
                episodes.map { episode ->
                    val domain = episode.toDomain(itemId)
                    EpisodeEntity(
                        serverId = account.serverId,
                        userId = account.userId,
                        id = domain.id,
                        libraryItemId = itemId,
                        title = domain.title,
                        subtitle = domain.subtitle,
                        description = domain.description,
                        episodeNumber = domain.episodeNumber,
                        season = domain.season,
                        publishedAtMs = domain.publishedAtMs,
                        durationSec = domain.durationSec,
                        position = domain.index,
                    )
                },
            )
        }
    }

    suspend fun chapters(account: ActiveAccount, itemId: String) =
        chapterDao.forItem(account.serverId, account.userId, itemId)

    suspend fun coverUrl(itemId: String, width: Int = 400): String = client.coverUrl(itemId, width)
}

private fun LibraryItemDto.toEntity(
    account: ActiveAccount,
    libraryIdFallback: String,
    syncedAtMs: Long,
): LibraryItemEntity {
    val domain = toDomain()
    return LibraryItemEntity(
        serverId = account.serverId,
        userId = account.userId,
        id = id,
        libraryId = libraryId.ifBlank { libraryIdFallback },
        mediaType = domain.mediaType.name,
        title = domain.title,
        subtitle = domain.subtitle,
        authorName = domain.authorName,
        narratorName = domain.narratorName,
        seriesName = domain.seriesName,
        description = domain.description,
        durationSec = domain.durationSec,
        sizeBytes = domain.sizeBytes,
        numEpisodes = domain.numEpisodes,
        addedAtMs = domain.addedAtMs,
        updatedAtMs = domain.updatedAtMs,
        coverPath = domain.coverPath,
        // Keeping the payload means a new UI field is a code change, not a resync.
        rawJson = runCatching { AbsJson.encodeToString(LibraryItemDto.serializer(), this) }.getOrNull(),
        syncedAtMs = syncedAtMs,
    )
}

internal fun LibraryItemEntity.toDomain(): LibraryItem = LibraryItem(
    id = id,
    libraryId = libraryId,
    mediaType = runCatching { MediaType.valueOf(mediaType) }.getOrDefault(MediaType.BOOK),
    title = title,
    subtitle = subtitle,
    authorName = authorName,
    narratorName = narratorName,
    seriesName = seriesName,
    description = description,
    durationSec = durationSec,
    sizeBytes = sizeBytes,
    numEpisodes = numEpisodes,
    addedAtMs = addedAtMs,
    updatedAtMs = updatedAtMs,
    coverPath = coverPath,
)
