package io.github.lightheaded.lugu.core.db

import androidx.room.Entity
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
    val seriesName: String?,
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
    /** Server `lastUpdate`, the ordering key for conflict resolution. */
    val lastUpdateMs: Long,
    val startedAtMs: Long,
    /** What the server had when we last heard from it; used to detect regressions. */
    val serverLastUpdateMs: Long,
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

/** Queue rows land in schema v1 so M3 does not need a migration; unused until then. */
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
