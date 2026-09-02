package io.github.lightheaded.lugu.core.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema v1.
 *
 * Every user-scoped row carries (serverId, userId) even though the UI only shows one
 * account today. Multi-server is then a UI change later instead of a migration of every
 * table — see docs/PLAN.md §4.8.
 */

@Entity(tableName = "server")
data class ServerEntity(
    @PrimaryKey val serverId: String,
    val baseUrl: String,
    val userId: String,
    val username: String,
    val defaultLibraryId: String?,
    val serverVersion: String?,
    val isActive: Boolean,
    /**
     * A second address to prefer when it answers — typically the server's address on the
     * home network, where a reverse proxy is not in the way.
     *
     * An address, not a credential, so it lives here rather than in encrypted storage.
     * Which one is used is decided by trying this one with a short timeout, never by
     * inspecting the network: reading the current Wi-Fi network's name needs the location
     * permission on Android 10 and later, and asking for a listener's location so a book
     * loads faster is not a trade worth offering.
     */
    val lanBaseUrl: String? = null,
)

@Entity(
    tableName = "library",
    primaryKeys = ["serverId", "userId", "id"],
)
data class LibraryEntity(
    val serverId: String,
    val userId: String,
    val id: String,
    val name: String,
    val mediaType: String,
    val displayOrder: Int,
)

/**
 * The library mirror. Columns exist for what the UI sorts, filters and renders;
 * [rawJson] keeps the rest of the server payload so a new UI field does not need a
 * migration and a resync.
 */
@Entity(
    tableName = "library_item",
    primaryKeys = ["serverId", "userId", "id"],
    indices = [
        Index(value = ["serverId", "userId", "libraryId"]),
        Index(value = ["serverId", "userId", "title"]),
        Index(value = ["updatedAtMs"]),
    ],
)
data class LibraryItemEntity(
    val serverId: String,
    val userId: String,
    val id: String,
    val libraryId: String,
    val mediaType: String,
    val title: String,
    val subtitle: String?,
    val authorName: String?,
    val narratorName: String?,
    /** As the server sends it, sequence and all: "The Breakwater #2". */
    val seriesName: String?,
    /**
     * The series name with its number removed ("The Breakwater #2" → "The Breakwater").
     *
     * Two books in one series have *different* `seriesName` values, because the number is
     * part of the string — so grouping a series by `seriesName` groups nothing. This is
     * the column that identifies a series.
     */
    val seriesTitle: String?,
    /**
     * The number parsed out of `seriesName` ("The Breakwater #2" → 2.0).
     *
     * Kept as its own column because the only alternative is ordering a series by its
     * name, and lexicographic ordering puts "#10" before "#2" — which would make
     * "next in series" confidently recommend the wrong book.
     */
    val seriesSequence: Double?,
    val description: String?,
    val durationSec: Double,
    val sizeBytes: Long,
    val numEpisodes: Int,
    val addedAtMs: Long,
    val updatedAtMs: Long,
    val coverPath: String?,
    val rawJson: String?,
    /** Set when a sync pass no longer sees the item, so stale rows can be swept. */
    val syncedAtMs: Long,
)

/**
 * Which series a book is in — one row per membership, so a book can be in several.
 *
 * The columns on [LibraryItemEntity] cannot express that, and it is not a hypothetical:
 * the server keeps series membership in a join table with the sequence on the join row,
 * and renders it for list payloads as a single comma-joined string. A book in two series
 * arrives as "The Breakwater #1, The Tidelands #3", which no single-series column can
 * hold and which the last-resort parse reads as a series called "The Breakwater #1, The
 * Tidelands" at volume three — a series that does not exist, and a number that belongs to
 * a different one.
 *
 * Rows are keyed on the series *name* rather than on [seriesId], because the id is only
 * known when the server sent it and a membership recovered from the joined string has
 * none. The server enforces one spelling of a name per library, and all three sources
 * copy that spelling verbatim, so the name is a sound key within one item's library.
 */
@Entity(
    tableName = "item_series",
    primaryKeys = ["serverId", "userId", "libraryItemId", "seriesName"],
    indices = [
        Index(value = ["serverId", "userId", "seriesName"]),
        Index(value = ["serverId", "userId", "libraryId"]),
    ],
)
data class ItemSeriesEntity(
    val serverId: String,
    val userId: String,
    val libraryItemId: String,
    /** Denormalised from the item so a sync pass can sweep one library without a join. */
    val libraryId: String,
    val seriesName: String,
    /** The server's own id, where the membership came from a source that carries one. */
    val seriesId: String?,
    /**
     * This book's position in this series, and null when no position is known.
     *
     * Null covers both "the server has no sequence for this book" and "the sequence it
     * has is not a number", because those mean the same thing to anything that orders a
     * series. Ordering by anything else — the title, or the order rows arrived in — is
     * how a shelf recommends the wrong volume, which is a spoiler rather than a glitch.
     */
    val sequence: Double?,
    /**
     * Where this book sits in the order the library-series listing returned, or null.
     *
     * Worth storing and worth being careful with. The server produces that order by
     * natural-sorting the sequence strings, so where sequences exist it agrees with
     * [sequence] and adds nothing — and where none exist its comparator has nothing to
     * compare, leaving the order the scanner inserted the rows in. That is the order the
     * server's own web client displays, which is reason enough to lay a series page out
     * that way instead of alphabetically. It is not reason enough to tell somebody which
     * book to read next, so "next in series" ignores this column and requires a
     * [sequence].
     */
    val serverRank: Int?,
    /** One of [SeriesOrigin]: how much this row can be trusted, and what may overwrite it. */
    val origin: Int,
    val syncedAtMs: Long,
)

/**
 * Where a series membership came from, ordered by how much the server had to do with it.
 *
 * The distinction is what stops a cheap source from undoing an authoritative one. The
 * paged library sync runs on every app open and can only ever parse the joined string; if
 * it overwrote what the series listing established, every upgrade would be followed by a
 * downgrade a few seconds later.
 */
object SeriesOrigin {
    /**
     * Recovered from the joined `seriesName` string, by the parse that predates all of
     * this. At most one membership, and only as good as the string is unambiguous.
     */
    const val PARSED = 0

    /**
     * Stated by the server: the library-series listing, or the structured `metadata.series`
     * array on an expanded item. Both are the join table rather than a rendering of it.
     */
    const val SERVER = 1
}

@Entity(
    tableName = "episode",
    primaryKeys = ["serverId", "userId", "id"],
    indices = [Index(value = ["serverId", "userId", "libraryItemId"])],
)
data class EpisodeEntity(
    val serverId: String,
    val userId: String,
    val id: String,
    val libraryItemId: String,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val episodeNumber: String?,
    val season: String?,
    val publishedAtMs: Long,
    val durationSec: Double,
    val position: Int,
)

/**
 * Chapters, stored already sorted by [startSec]. The write path is the only place that
 * enforces it (see `ChapterDao.replaceForItem`), so every reader downstream can assume it.
 */
@Entity(
    tableName = "chapter",
    primaryKeys = ["serverId", "userId", "libraryItemId", "chapterIndex"],
)
data class ChapterEntity(
    val serverId: String,
    val userId: String,
    val libraryItemId: String,
    val chapterIndex: Int,
    val startSec: Double,
    val endSec: Double,
    val title: String,
)

/** No push from this device has ever been accepted for a row. */
const val NOTHING_PUSHED_SEC: Double = -1.0

@Entity(
    tableName = "progress",
    primaryKeys = ["serverId", "userId", "libraryItemId", "episodeKey"],
    indices = [Index(value = ["serverId", "userId", "lastUpdateMs"])],
)
data class ProgressEntity(
    val serverId: String,
    val userId: String,
    val libraryItemId: String,
    /** Empty string for books — SQLite primary keys cannot contain nulls. */
    val episodeKey: String,
    val currentTimeSec: Double,
    val durationSec: Double,
    val progress: Double,
    val isFinished: Boolean,
    /**
     * **When the listening happened**, on the clock of whichever device did it: this one
     * for local listening, the server's `lastUpdate` for a copy adopted from elsewhere.
     *
     * An ordering key and nothing else — the Continue shelf, "most recently played", the
     * stale-listen sweep, the finished-download cutoff. Two clocks meeting here is
     * tolerable because ordering by roughly-wall-clock values is roughly right, and
     * because the alternative is worse: stamping an adopted row with this device's clock
     * would flatten the whole shelf every time a login sweep re-read the server.
     *
     * **Never use it to decide a conflict.** That is what [serverLastUpdateMs] is for, and
     * doing it here is what put a resumed book thirty seconds behind where it was left.
     */
    val lastUpdateMs: Long,
    val startedAtMs: Long,
    /**
     * **The server's own revision** of this row: its `lastUpdate` for the copy this
     * device last read, or 0 when it has never read one.
     *
     * Only ever compared with another value from the server, which is the whole point of
     * it being separate. It used to be written from whichever side last touched the row —
     * the server's clock after an adoption, `System.currentTimeMillis()` after a push —
     * and conflict resolution then compared it with the server's stamp. On a device whose
     * clock ran behind the server's, a stale server position therefore won every conflict.
     */
    val serverLastUpdateMs: Long,
    /**
     * The position the server last accepted from this device, or -1 when it has accepted
     * none.
     *
     * `PATCH /api/me/progress/:id` answers with an empty body, so a push tells us the
     * position the server now holds but never the stamp it gave it. This is what lets
     * lugu recognise its own copy coming back and keep on listening past it.
     */
    val pushedTimeSec: Double = NOTHING_PUSHED_SEC,
    /** The finished flag that went out with [pushedTimeSec]. */
    val pushedFinished: Boolean = false,
    /** True while a local change has not been confirmed by the server. */
    val isDirty: Boolean,
)

/**
 * Every listening session, kept whether or not the server has seen it. Doubles as the
 * user-visible listening history and as the recovery tool for accidental seeks.
 */
@Entity(
    tableName = "session_ledger",
    primaryKeys = ["id"],
    indices = [Index(value = ["serverId", "userId", "startedAtMs"])],
)
data class SessionLedgerEntity(
    /** Client-generated UUID, also used as the server-side id for offline uploads. */
    val id: String,
    val serverId: String,
    val userId: String,
    val libraryItemId: String,
    val episodeId: String?,
    val displayTitle: String,
    val displayAuthor: String,
    val mediaType: String,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val startTimeSec: Double,
    val currentTimeSec: Double,
    val timeListeningSec: Double,
    val durationSec: Double,
    /** True when the session was opened without a server session (offline playback). */
    val isLocal: Boolean,
    val isUploaded: Boolean,
    /** Server session id when one was opened; null for offline sessions. */
    val serverSessionId: String?,
)

/**
 * Durable queue of mutations owed to the server. Everything the user changes lands
 * locally first and here second; WorkManager drains it with backoff. A week in
 * airplane mode should lose nothing.
 */
@Entity(
    tableName = "outbox",
    indices = [Index(value = ["serverId", "userId", "createdAtMs"])],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val serverId: String,
    val userId: String,
    val kind: String,
    /** Dedupe key: a newer entry with the same key supersedes an older pending one. */
    val dedupeKey: String,
    val payloadJson: String,
    val createdAtMs: Long,
    val attempts: Int,
    val lastAttemptAtMs: Long,
    val lastError: String?,
)

object OutboxKind {
    const val PROGRESS_UPDATE = "progress_update"
    const val LOCAL_SESSION = "local_session"
}

/**
 * The play queue: what happens after the thing playing now.
 *
 * Identity only — no title, no duration — so a rename on the server does not leave a
 * stale copy here, and a new column on the UI does not need a migration. [position] is
 * dense from zero (see `QueueDao`), so the row a listener drags is the row that moves.
 *
 * These rows landed in schema v1 ahead of use, which is why M3 needs no migration.
 */
@Entity(
    tableName = "queue",
    primaryKeys = ["serverId", "userId", "libraryItemId", "episodeKey"],
)
data class QueueEntity(
    val serverId: String,
    val userId: String,
    val libraryItemId: String,
    val episodeKey: String,
    val position: Int,
    val addedAtMs: Long,
    /** Distinguishes a user-added entry from one an auto-continuation rule appended. */
    val source: String,
)

object QueueSource {
    /** Put there on purpose. Plays without asking, because it was already asked for. */
    const val USER = "user"

    /** Suggested by a continuation rule — series, or the next podcast episode. */
    const val AUTO = "auto"
}

/**
 * One downloaded item (or podcast episode), and everything needed to play it with the
 * network switched off.
 *
 * [tracksJson] is the load-bearing field: it is the manifest of audio files, their
 * offsets into the book and their cache keys. Without it a downloaded book is
 * unplayable offline, because the URLs and track offsets normally arrive from
 * `POST /api/items/:id/play` — a call that needs the server. Storing the manifest at
 * download time is what turns "the bytes are on the phone" into "the book plays".
 */
@Entity(
    tableName = "download",
    primaryKeys = ["serverId", "userId", "libraryItemId", "episodeKey"],
    indices = [Index(value = ["serverId", "userId", "state"])],
)
data class DownloadEntity(
    val serverId: String,
    val userId: String,
    val libraryItemId: String,
    /** Empty string for books, mirroring the progress table. */
    val episodeKey: String,
    val title: String,
    val author: String?,
    val mediaType: String,
    /** One of [DownloadState]. */
    val state: String,
    val tracksJson: String,
    val durationSec: Double,
    val bytesTotal: Long,
    val bytesDownloaded: Long,
    val percent: Float,
    val requestedAtMs: Long,
    val completedAtMs: Long,
    val error: String?,
)

object DownloadState {
    const val QUEUED = "queued"
    const val DOWNLOADING = "downloading"
    const val COMPLETED = "completed"
    const val FAILED = "failed"

    /**
     * Marked for deletion, but the bytes are still on disk.
     *
     * A tap on a completed download's delete control lands here first rather than
     * removing anything: it lets the delete undo exactly, because nothing was touched.
     * `DownloadDao.unfinished` excludes this state on purpose -- the engine's own
     * reconciler folds Media3's per-file state back onto a row, and every file behind a
     * pending-delete row is still complete, so letting the reconciler see the row would
     * fold it straight back to [COMPLETED] and silently undo the delete.
     */
    const val PENDING_DELETE = "pending_delete"

    /** Only a completed download can be played with no network. */
    fun isPlayableOffline(state: String) = state == COMPLETED
}

/**
 * Full-text index over the library mirror.
 *
 * Not an external-content table: Room does not generate the triggers that would keep
 * one in sync, so a stale index would silently return yesterday's library. This one is
 * written explicitly wherever items are written, which is a single place.
 *
 * The id columns are `notIndexed` so they scope a query without polluting the terms —
 * searching for a library id is not a thing anyone wants to do.
 */
@Fts4(notIndexed = ["serverId", "userId", "itemId", "libraryId"])
@Entity(tableName = "library_item_fts")
data class LibraryItemFtsEntity(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val libraryId: String,
    /** Title, subtitle, author, narrator, series, description, genres and tags, joined. */
    val text: String,
)

/**
 * Every large position change, so no jump is ever unrecoverable.
 *
 * Added after a real incident: the notification's previous button seeked a book to
 * zero, that position was persisted and synced, and there was no way back — the local
 * database only ever holds *current* progress. A forty-hour book lost to one
 * mistaken tap is the exact failure lugu exists to prevent, so every jump big enough
 * to be unintended is now recorded and can be undone.
 */
@Entity(
    tableName = "position_history",
    indices = [Index(value = ["serverId", "userId", "libraryItemId", "atMs"])],
)
data class PositionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val serverId: String,
    val userId: String,
    val libraryItemId: String,
    val episodeKey: String,
    val fromSec: Double,
    val toSec: Double,
    val atMs: Long,
    /** Why the position moved: "seek", "chapter", "notification", "adopted-remote". */
    val reason: String,
)
