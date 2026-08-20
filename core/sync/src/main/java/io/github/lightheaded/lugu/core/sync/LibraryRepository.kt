package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.AbsJson
import io.github.lightheaded.lugu.core.api.LibraryItemDto
import io.github.lightheaded.lugu.core.api.librarySeries
import io.github.lightheaded.lugu.core.api.seriesRefFor
import io.github.lightheaded.lugu.core.api.seriesRefs
import io.github.lightheaded.lugu.core.api.toDomain
import io.github.lightheaded.lugu.core.db.BrowseGroup
import io.github.lightheaded.lugu.core.db.ChapterDao
import io.github.lightheaded.lugu.core.db.ChapterEntity
import io.github.lightheaded.lugu.core.db.EpisodeDao
import io.github.lightheaded.lugu.core.db.EpisodeEntity
import io.github.lightheaded.lugu.core.db.InProgressRow
import io.github.lightheaded.lugu.core.db.ItemSeriesDao
import io.github.lightheaded.lugu.core.db.ItemSeriesEntity
import io.github.lightheaded.lugu.core.db.LibraryDao
import io.github.lightheaded.lugu.core.db.LibraryEntity
import io.github.lightheaded.lugu.core.db.LibraryItemDao
import io.github.lightheaded.lugu.core.db.LibraryItemEntity
import io.github.lightheaded.lugu.core.db.LibraryItemFtsDao
import io.github.lightheaded.lugu.core.db.LibraryItemFtsEntity
import io.github.lightheaded.lugu.core.db.SeriesOrigin
import io.github.lightheaded.lugu.core.model.Chapters
import io.github.lightheaded.lugu.core.model.FtsQuery
import io.github.lightheaded.lugu.core.model.Library
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.model.PodcastEpisode
import io.github.lightheaded.lugu.core.model.SeriesRef
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A computed row on the library screen. */
enum class ShelfKind(val label: String) {
    CONTINUE("Continue listening"),
    NEXT_IN_SERIES("Next in series"),
    ALMOST_FINISHED("Almost finished"),
    DOWNLOADED("Downloaded"),
    PICK_IT_BACK_UP("Pick it back up"),
    SHORT_LISTENS("Short listens"),
}

/**
 * One thing on a shelf.
 *
 * Not simply a [LibraryItem], because "Continue listening" is about what is being listened
 * to rather than about which items are in progress, and for a podcast those are different
 * questions — somebody three episodes into a show has three answers, not one. Every other
 * shelf leaves [episodeId] null, which is also what makes the key below unique on all of
 * them.
 */
data class ShelfEntry(
    val item: LibraryItem,
    val episodeId: String? = null,
    val episodeTitle: String? = null,
    /** The duration of what is being played: the episode for a podcast, the book otherwise. */
    val playedDurationSec: Double = 0.0,
) {
    /**
     * Stable across recomposition and unique within a shelf. The item id alone stopped
     * being unique when the continue shelf started listing episodes, and Compose throws on
     * a duplicate key rather than merely looking odd.
     */
    val key: String get() = "${item.id}#${episodeId.orEmpty()}"
}

data class Shelf(val kind: ShelfKind, val entries: List<ShelfEntry>)

/**
 * A way of grouping the library other than by title.
 *
 * These are the three links the app already renders on an item page — author, series and
 * narrator — and until now every one of them pointed nowhere. Linking to a dead end is
 * worse than not linking, which is why the pages come before the links.
 */
enum class BrowseKind(val id: String, val label: String, val singular: String) {
    AUTHORS("authors", "Authors", "Author"),
    SERIES("series", "Series", "Series"),
    NARRATORS("narrators", "Narrators", "Narrator"),
    ;

    companion object {
        fun fromId(id: String?): BrowseKind = entries.firstOrNull { it.id == id } ?: AUTHORS
    }
}

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
    private val ftsDao: LibraryItemFtsDao,
    private val seriesDao: ItemSeriesDao,
    private val libraryPrefs: LibraryPrefs,
    private val clock: Clock,
) {
    /**
     * When the series listing for each library last finished, so the next pass can decline.
     *
     * In memory rather than on disk, for the same reason as in `CollectionRepository`:
     * forgetting it on a cold start is right, because that is when the mirror is most
     * likely to be stale.
     */
    private val lastSeriesSyncAtMs = mutableMapOf<String, Long>()

    /**
     * One lock per (account, library), so two mirror passes over the same library cannot
     * run at once. See [syncLibraryItems] for what happens when they do.
     */
    private val passLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * The libraries this listener wants to see.
     *
     * A hidden media type is filtered out here rather than at each screen, so switching
     * podcasts off reaches the tabs, the shelves, search and the car's browse tree by
     * construction instead of by remembering. Every library on the server is still
     * mirrored and still syncs — hiding is a matter of what is shown, never of what is
     * kept, so switching it back on is instant rather than a resync.
     */
    fun observeLibraries(account: ActiveAccount): Flow<List<Library>> =
        combine(
            libraryDao.observeAll(account.serverId, account.userId),
            libraryPrefs.settings,
        ) { rows, settings ->
            rows.map { Library(it.id, it.name, MediaType.fromWire(it.mediaType), it.displayOrder) }
                .filter { settings.isVisible(it.mediaType) }
        }

    /** Every library, hidden types included — for settings, and for the sync sweep. */
    fun observeAllLibraries(account: ActiveAccount): Flow<List<Library>> =
        libraryDao.observeAll(account.serverId, account.userId).map { rows ->
            rows.map { Library(it.id, it.name, MediaType.fromWire(it.mediaType), it.displayOrder) }
        }

    /**
     * Whether this account has anything mirrored at all.
     *
     * The difference between "you have nothing part-heard" and "your library has not
     * arrived yet" is the whole of what an empty Home can usefully say, and for the first
     * minute of a new account the second is the true one. Sending somebody to the Library
     * tab in that minute sends them to another empty screen.
     */
    fun observeAnythingMirrored(account: ActiveAccount): Flow<Boolean> =
        itemDao.observeCount(account.serverId, account.userId).map { it > 0 }

    fun observeItems(account: ActiveAccount, libraryId: String): Flow<List<LibraryItem>> =
        itemDao.observeByLibrary(account.serverId, account.userId, libraryId).map { rows ->
            rows.map { it.toDomain() }
        }

    /**
     * Instant search over the mirror.
     *
     * Full-text first, because it matches whole words anywhere in the metadata and stays
     * fast on a large library. It falls back to a substring scan for queries FTS cannot
     * express — a two-letter prefix, or a query whose only term is punctuation — so
     * typing never produces an empty screen that a slower search would have filled.
     */
    fun search(account: ActiveAccount, libraryId: String, query: String): Flow<List<LibraryItem>> {
        val match = FtsQuery.toMatchExpression(query)
        val flow = if (match != null) {
            itemDao.searchFts(account.serverId, account.userId, libraryId, match)
        } else {
            itemDao.search(account.serverId, account.userId, libraryId, query)
        }
        return flow.map { rows -> rows.map { it.toDomain() } }
    }

    fun observeItem(account: ActiveAccount, itemId: String): Flow<LibraryItem?> =
        itemDao.observeById(account.serverId, account.userId, itemId).map { it?.toDomain() }

    /**
     * Every series this book belongs to, in the order a page should list them.
     *
     * The screens had to read `LibraryItem.seriesName` before this existed, and that field
     * is a *rendering* of this list rather than a substitute for it — the server joins all
     * of a book's series into one string, so a book in two of them rendered as a single
     * phantom series named after both with the second one's number attached. What is
     * returned here is the membership itself, one entry per series, each with its own
     * number or none.
     */
    fun observeSeriesFor(account: ActiveAccount, itemId: String): Flow<List<SeriesRef>> =
        seriesDao.observeForItem(account.serverId, account.userId, itemId).map { rows ->
            rows.map { SeriesRef(id = it.seriesId, name = it.seriesName, sequence = it.sequence) }
        }

    fun observeEpisodes(account: ActiveAccount, itemId: String): Flow<List<PodcastEpisode>> =
        episodeDao.observeForItem(account.serverId, account.userId, itemId).map { rows ->
            rows.map { it.toDomain() }
        }

    /**
     * One episode, by its own id.
     *
     * Read once rather than observed, because the episode page reads a description and a
     * description does not change under anyone. Taking it from the whole feed instead would
     * mean holding a thousand rows in memory to draw one of them.
     */
    suspend fun episode(account: ActiveAccount, episodeId: String): PodcastEpisode? =
        episodeDao.byId(account.serverId, account.userId, episodeId)?.toDomain()

    /**
     * The three ways a library is browsed other than by title.
     *
     * All computed from the mirror, which is why they work offline and why they exist at
     * all: the server has an author page and a series page in its web client, and no API
     * that hands either to a client. Grouping locally is both the only option and the
     * faster one.
     */
    fun observeGroups(
        account: ActiveAccount,
        kind: BrowseKind,
        libraryId: String? = null,
    ): Flow<List<BrowseGroup>> = when (kind) {
        BrowseKind.AUTHORS -> itemDao.observeAuthors(account.serverId, account.userId, libraryId)
        BrowseKind.NARRATORS -> itemDao.observeNarrators(account.serverId, account.userId, libraryId)
        BrowseKind.SERIES -> itemDao.observeSeries(account.serverId, account.userId, libraryId)
    }

    fun observeGroupItems(
        account: ActiveAccount,
        kind: BrowseKind,
        name: String,
    ): Flow<List<LibraryItem>> {
        val rows = when (kind) {
            BrowseKind.AUTHORS -> itemDao.observeByAuthor(account.serverId, account.userId, name)
            BrowseKind.NARRATORS -> itemDao.observeByNarrator(account.serverId, account.userId, name)
            BrowseKind.SERIES -> itemDao.observeBySeries(account.serverId, account.userId, name)
        }
        return rows.map { list -> list.distinctBy { it.id }.map { it.toDomain() } }
    }

    fun observeContinueListening(account: ActiveAccount): Flow<List<LibraryItem>> =
        itemDao.observeContinueListening(account.serverId, account.userId).map { rows ->
            // The query already groups by item, but the UI keys this list by id and
            // Compose throws on a duplicate key — a crash, not a glitch. Cheap insurance
            // against any future join that reintroduces fan-out.
            rows.distinctBy { it.id }.map { it.toDomain() }
        }

    /**
     * The computed shelves.
     *
     * Every one of these is a query against the local mirror rather than a call to the
     * server's `/personalized` endpoint. That is the whole point: they render on a cold
     * start in airplane mode, and they can answer questions the server does not, like
     * "what have I downloaded".
     *
     * [libraryId] is what the shelves are scoped to; null spans every library. Passing it
     * explicitly is deliberate. These shelves used to be account-wide while the grid
     * beneath them was scoped to the selected library, so choosing the audiobook library
     * filtered the grid and left podcasts on the shelves directly above it — which reads,
     * correctly, as the filter being broken. The caller now has to say which it means.
     */
    fun observeShelves(
        account: ActiveAccount,
        libraryId: String? = null,
        hidden: Set<ShelfKind> = emptySet(),
    ): Flow<List<Shelf>> {
        // Declared in the order they are shown. Listing them as pairs rather than as
        // combine() arguments keeps the display order and the query in one place, and
        // sidesteps combine()'s five-flow typed limit.
        val sources: List<Pair<ShelfKind, Flow<List<ShelfEntry>>>> = listOf(
            // The one shelf that is not a list of items. A podcast with three part-heard
            // episodes belongs on it three times, because each is a separate thing somebody
            // is part-way through and picking the other two should not mean navigating to
            // the show and finding them.
            ShelfKind.CONTINUE to itemDao.observeInProgress(account.serverId, account.userId, libraryId)
                .map { rows -> rows.map { it.toEntry() } },
            ShelfKind.NEXT_IN_SERIES to
                itemDao.observeNextInSeries(account.serverId, account.userId, libraryId).map { it.toEntries() },
            ShelfKind.ALMOST_FINISHED to
                itemDao.observeAlmostFinished(account.serverId, account.userId, libraryId).map { it.toEntries() },
            ShelfKind.DOWNLOADED to itemDao.observeDownloaded(account.serverId, account.userId, libraryId)
                .map { it.toEntries() },
            ShelfKind.PICK_IT_BACK_UP to itemDao.observeStale(
                account.serverId,
                account.userId,
                clock.nowMs() - STALE_AFTER_MS,
                libraryId,
            ).map { it.toEntries() },
            ShelfKind.SHORT_LISTENS to
                itemDao.observeShortListens(account.serverId, account.userId, libraryId).map { it.toEntries() },
        )

        // A shelf switched off is not computed at all. Some of these are the heaviest
        // queries in the app — "next in series" is three correlated subqueries over the
        // whole mirror — and running one to throw the answer away is a cost paid on every
        // change to the library by someone who said they did not want it.
        val wanted = sources.filterNot { (kind, _) -> kind in hidden }
        if (wanted.isEmpty()) return flowOf(emptyList())

        return combine(wanted.map { it.second }) { results ->
            // Every one of these lists is keyed by id in Compose, where a duplicate key
            // is a crash rather than a cosmetic glitch — the same fan-out that once
            // crashed the continue-listening row (see ContinueListeningTest).
            wanted.mapIndexed { index, (kind, _) -> Shelf(kind, results[index]) }
                .filter { it.entries.isNotEmpty() }
        }
    }

    private fun List<LibraryItemEntity>.toEntries(): List<ShelfEntry> =
        distinctBy { it.id }.map { ShelfEntry(it.toDomain(), playedDurationSec = it.durationSec) }

    /**
     * The played duration comes from the progress row, not the item: for an episode those
     * differ by three orders of magnitude, and reading the wrong one reports three minutes
     * into a three-hundred-hour feed.
     */
    private fun InProgressRow.toEntry(): ShelfEntry = ShelfEntry(
        item = item.toDomain(),
        episodeId = episodeId,
        episodeTitle = episodeTitle,
        playedDurationSec = playedDurationSec.takeIf { it > 0.0 } ?: item.durationSec,
    )

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
     *
     * ## One pass per library at a time, and why that is not optional
     *
     * The sweep deletes every row of this library stamped before *this pass* started, so
     * two passes running at once can delete each other's work. The order that does it:
     * pass B stamps a row at its own start time, pass A — which started earlier and is
     * still walking — restamps that row with its *older* time, and B's sweep then reads
     * the row as stale and removes it. A has already gone past, so nothing puts it back,
     * and the item is simply missing from the mirror until the next full pass.
     *
     * It was always possible — the six-hourly reconcile could overlap a pull-to-refresh —
     * and it became likely on 17 August, when signing in started a sync of its own while
     * the Library tab was starting one. It showed up as an instrumented test on the slower
     * emulator reporting that a title never reached Room, which is exactly what it looks
     * like from outside: not a failure, an absence.
     *
     * A second caller therefore waits for the pass in flight and takes its word rather
     * than repeating it. Waiting rather than skipping matters because the caller asked for
     * a mirror and has to be able to read one when this returns; not repeating it matters
     * because a full pass over a large library is minutes of somebody's data.
     */
    suspend fun syncLibraryItems(
        account: ActiveAccount,
        libraryId: String,
        onProgress: (synced: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<Int> = runCatching {
        val inFlight = passLock(account, libraryId)
        if (!inFlight.tryLock()) {
            inFlight.withLock { }
            return@runCatching itemDao.countInLibrary(account.serverId, account.userId, libraryId)
        }
        try {
            mirrorLibraryItems(account, libraryId, onProgress)
        } finally {
            inFlight.unlock()
        }
    }

    private suspend fun mirrorLibraryItems(
        account: ActiveAccount,
        libraryId: String,
        onProgress: (synced: Int, total: Int) -> Unit,
    ): Int {
        val startedAt = clock.nowMs()
        var page = 0
        var synced = 0
        var total = Int.MAX_VALUE

        while (synced < total) {
            val response = client.libraryItems(libraryId, page = page, limit = AbsClient.DEFAULT_PAGE_SIZE)
            if (response.results.isEmpty()) break
            total = response.total.takeIf { it > 0 } ?: response.results.size

            val entities = response.results.map { it.toEntity(account, libraryId, startedAt) }
            itemDao.upsertAll(entities)
            ftsDao.replaceAll(account.serverId, account.userId, entities.map { it.toFtsRow() })
            writeParsedSeries(account, libraryId, response.results, startedAt)

            synced += response.results.size
            page += 1
            onProgress(synced, total)
        }

        itemDao.deleteStale(account.serverId, account.userId, libraryId, startedAt)
        // The sweep above is the only place items disappear, so it is also the only
        // place the index can be left pointing at rows that no longer exist.
        ftsDao.deleteOrphans(account.serverId, account.userId, libraryId)
        seriesDao.deleteOrphans(account.serverId, account.userId, libraryId)

        // The series listing rides along with the item sync rather than being scheduled
        // separately, so nothing outside this class has to know it exists — and it is not
        // tied to opening an item, which is the one cadence it must never have. Its own
        // failure is not this one's: the mirror is already written, and the memberships
        // fall back to what the paged pass parsed. Hence the Result being dropped.
        syncSeries(account, libraryId)
        return synced
    }

    /**
     * One lock per (account, library), created once and kept.
     *
     * Keyed by account as well as library because a library id is only unique within a
     * server, and two accounts syncing at once is an ordinary thing on a shared phone.
     * The map only grows by the number of libraries somebody has, so nothing evicts it.
     */
    private fun passLock(account: ActiveAccount, libraryId: String): Mutex =
        passLocks.getOrPut("${account.serverId}#$libraryId") { Mutex() }

    /**
     * Mirrors one library's series from the server's own listing.
     *
     * This is the only source that states series membership for a whole library at once.
     * The paged item listing cannot: its minified payloads carry the joined `seriesName`
     * string and nothing else, so a book in two series is indistinguishable from a book
     * in one series with an odd name.
     *
     * Rate-limited to [SERIES_SYNC_INTERVAL_MS] unless [force] is set, which is what a
     * pull-to-refresh passes. The listing is expensive in the way the collections listing
     * is expensive — its documented `minified` parameter is echoed back and never read, so
     * every member of every series arrives as a complete item payload — and the reason it
     * is not simply refused is that, unlike collections, its paging is real: `limit` and
     * `offset` go straight into the database query, so this walks the library in bounded
     * pages rather than pulling it in one response.
     *
     * The sweep at the end runs only when every page came back. A pass that failed halfway
     * and then swept would read a dropped connection as "this library has no series any
     * more" and empty every series page on the phone.
     */
    suspend fun syncSeries(
        account: ActiveAccount,
        libraryId: String,
        force: Boolean = false,
    ): Result<Int> = runCatching {
        val startedAt = clock.nowMs()
        val last = lastSeriesSyncAtMs[libraryId] ?: 0
        if (!force && last > 0 && startedAt - last < SERIES_SYNC_INTERVAL_MS) return@runCatching 0

        var page = 0
        var seen = 0
        var total = Int.MAX_VALUE

        while (seen < total) {
            val response = client.librarySeries(libraryId, page = page)
            if (response.results.isEmpty()) break
            total = response.total.takeIf { it > 0 } ?: response.results.size

            response.results.forEach { series ->
                val name = series.name.trim()
                if (name.isEmpty()) return@forEach
                val members = series.books.filter { it.id.isNotBlank() }
                seriesDao.replaceForItems(
                    account.serverId,
                    account.userId,
                    members.map { it.id },
                    members.mapIndexed { rank, book ->
                        val ref = book.seriesRefFor(series.id.takeIf { it.isNotBlank() }, name)
                        ItemSeriesEntity(
                            serverId = account.serverId,
                            userId = account.userId,
                            libraryItemId = book.id,
                            libraryId = book.libraryId.ifBlank { libraryId },
                            seriesName = ref.name,
                            seriesId = ref.id,
                            sequence = ref.sequence,
                            serverRank = rank,
                            origin = SeriesOrigin.SERVER,
                            syncedAtMs = startedAt,
                        )
                    },
                )
            }

            seen += response.results.size
            page += 1
        }

        // Only reached when the walk completed, which is what makes the sweep safe.
        seriesDao.deleteStale(account.serverId, account.userId, libraryId, startedAt)
        // And now the two columns on the item can be brought into line with what the
        // listing said, rather than staying whatever the paged pass parsed out of a string
        // that, for a book in two series, names neither of them.
        itemDao.refreshPrimarySeries(account.serverId, account.userId, libraryId)
        lastSeriesSyncAtMs[libraryId] = startedAt
        seen
    }

    /**
     * One item's memberships, from the structured array only an expanded fetch carries.
     *
     * The most exact source there is — the server's own join rows, with their own ids and
     * sequences — so it always writes, where the parsed floor defers to whatever came
     * before it. Absence of the array is not absence of series: every minified payload
     * lacks it, so a payload with none is left entirely alone rather than read as "this
     * book is in nothing", which would empty the item's series between one sync and the
     * next.
     *
     * The rank is carried across from the rows being replaced. It belongs to the library
     * listing and this fetch knows nothing about it; writing null would quietly cost a
     * series page its ordering the first time somebody opened one of its books.
     */
    private suspend fun writeStructuredSeries(
        account: ActiveAccount,
        libraryId: String,
        dto: LibraryItemDto,
        syncedAtMs: Long,
    ) {
        val structured = dto.media?.metadata?.series.orEmpty()
        if (structured.isEmpty() || dto.id.isBlank()) return

        val ranks = seriesDao.forItem(account.serverId, account.userId, dto.id)
            .associate { it.seriesName to it.serverRank }
        seriesDao.replaceForItems(
            account.serverId,
            account.userId,
            listOf(dto.id),
            dto.seriesRefs().map { ref ->
                ItemSeriesEntity(
                    serverId = account.serverId,
                    userId = account.userId,
                    libraryItemId = dto.id,
                    libraryId = libraryId,
                    seriesName = ref.name,
                    seriesId = ref.id,
                    sequence = ref.sequence,
                    serverRank = ranks[ref.name],
                    origin = SeriesOrigin.SERVER,
                    syncedAtMs = syncedAtMs,
                )
            },
        )
    }

    /**
     * The floor under everything: one membership per item, parsed from the joined string.
     *
     * Written only for items the server has not already spoken for, so the pass that runs
     * on every app open cannot undo what the series listing established. Without that
     * guard a book in two series would be correct for a few seconds after each sync and
     * wrong for the rest of the time.
     *
     * A membership that survives here is one neither server source covered — the listing
     * has not run yet, or could not be reached — and it is exactly what the app did before
     * any of this, no better and no worse.
     */
    private suspend fun writeParsedSeries(
        account: ActiveAccount,
        libraryId: String,
        items: List<LibraryItemDto>,
        syncedAtMs: Long,
    ) {
        val ids = items.map { it.id }.filter { it.isNotBlank() }
        if (ids.isEmpty()) return
        val spokenFor = seriesDao
            .itemsAtOrAbove(account.serverId, account.userId, ids, SeriesOrigin.SERVER)
            .toSet()
        val writable = items.filter { it.id.isNotBlank() && it.id !in spokenFor }
        if (writable.isEmpty()) return

        seriesDao.replaceForItems(
            account.serverId,
            account.userId,
            writable.map { it.id },
            writable.flatMap { dto ->
                dto.seriesRefs().map { ref ->
                    ItemSeriesEntity(
                        serverId = account.serverId,
                        userId = account.userId,
                        libraryItemId = dto.id,
                        libraryId = dto.libraryId.ifBlank { libraryId },
                        seriesName = ref.name,
                        seriesId = ref.id,
                        sequence = ref.sequence,
                        serverRank = null,
                        origin = if (ref.id != null) SeriesOrigin.SERVER else SeriesOrigin.PARSED,
                        syncedAtMs = syncedAtMs,
                    )
                }
            },
        )
    }

    /**
     * Pulls one item in full: chapters, episodes and the long-tail metadata that the
     * minified list payload leaves out.
     */
    suspend fun syncItemDetail(account: ActiveAccount, itemId: String): Result<Unit> = runCatching {
        val dto = client.item(itemId, expanded = true)
        val entity = dto.toEntity(account, dto.libraryId, clock.nowMs())
        itemDao.upsertAll(listOf(entity))
        ftsDao.replaceAll(account.serverId, account.userId, listOf(entity.toFtsRow()))
        writeStructuredSeries(account, entity.libraryId, dto, clock.nowMs())

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

    /**
     * Forgets one item, index and all.
     *
     * The sweep after a full sync deletes by timestamp across a whole library, which is the
     * wrong shape for a single deletion arriving over the socket — and doing it by hand at
     * the call site means the full-text index gets forgotten exactly once, by whoever
     * forgets. The index is the part that fails quietly: a search result pointing at a row
     * that no longer exists opens a blank page.
     */
    suspend fun remove(account: ActiveAccount, itemId: String) {
        itemDao.delete(account.serverId, account.userId, itemId)
        ftsDao.deleteByItemIds(account.serverId, account.userId, listOf(itemId))
        seriesDao.deleteForItem(account.serverId, account.userId, itemId)
        episodeDao.deleteForItem(account.serverId, account.userId, itemId)
        chapterDao.deleteForItem(account.serverId, account.userId, itemId)
    }

    suspend fun chapters(account: ActiveAccount, itemId: String) =
        chapterDao.forItem(account.serverId, account.userId, itemId)

    suspend fun coverUrl(itemId: String, width: Int = 400): String = client.coverUrl(itemId, width)

    private companion object {
        /** Two weeks without a listen is when a book stops being "in progress" in someone's head. */
        const val STALE_AFTER_MS = 14L * 24 * 60 * 60 * 1000

        /**
         * The same floor the collections listing gets, for the same reason: an endpoint
         * that sends a complete item payload per member is not one to call on every screen
         * that wants a series name. Long enough that moving between screens costs nothing,
         * short enough that a book added on the desktop turns up while somebody is
         * still looking for it.
         */
        const val SERIES_SYNC_INTERVAL_MS = 5L * 60 * 1000
    }
}

private fun LibraryItemDto.toEntity(
    account: ActiveAccount,
    libraryIdFallback: String,
    syncedAtMs: Long,
): LibraryItemEntity {
    val domain = toDomain()
    val primarySeries = seriesRefs().firstOrNull()
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
        // The item's *primary* series, kept for the queries that legitimately want one
        // answer — an author page groups a writer's books by series, and the item screen
        // has room for one line. Every question about membership proper is answered by
        // `item_series`, which is where a book in two series is two rows rather than one
        // mangled string. Taken from the structured array when the payload has one, so an
        // expanded fetch fixes this column too and not only the table.
        seriesTitle = primarySeries?.name,
        seriesSequence = primarySeries?.sequence,
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

/**
 * What the search index holds for one item.
 *
 * Everything a listener might half-remember goes in one column: they search for "the one
 * narrated by Mays" without knowing or caring which field that lives in. Field-scoped
 * search would be a worse answer to the same question.
 */
private fun LibraryItemEntity.toFtsRow(): LibraryItemFtsEntity = LibraryItemFtsEntity(
    serverId = serverId,
    userId = userId,
    itemId = id,
    libraryId = libraryId,
    text = listOfNotNull(title, subtitle, authorName, narratorName, seriesName, description)
        .filter { it.isNotBlank() }
        .joinToString(" "),
)

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

internal fun EpisodeEntity.toDomain(): PodcastEpisode = PodcastEpisode(
    id = id,
    libraryItemId = libraryItemId,
    title = title,
    subtitle = subtitle,
    description = description,
    episodeNumber = episodeNumber,
    season = season,
    publishedAtMs = publishedAtMs,
    durationSec = durationSec,
    index = position,
)
