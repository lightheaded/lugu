package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.AbsJson
import io.github.lightheaded.lugu.core.api.MediaProgressDto
import io.github.lightheaded.lugu.core.api.ProgressUpdateRequest
import io.github.lightheaded.lugu.core.api.toDomain
import io.github.lightheaded.lugu.core.db.OutboxDao
import io.github.lightheaded.lugu.core.db.OutboxEntity
import io.github.lightheaded.lugu.core.db.OutboxKind
import io.github.lightheaded.lugu.core.db.PositionHistoryDao
import io.github.lightheaded.lugu.core.db.PositionHistoryEntity
import io.github.lightheaded.lugu.core.db.ProgressDao
import io.github.lightheaded.lugu.core.db.ProgressEntity
import io.github.lightheaded.lugu.core.db.episodeKeyOf
import io.github.lightheaded.lugu.core.db.toEpisodeIdOrNull
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.model.ProgressConflictResolver
import io.github.lightheaded.lugu.core.model.ProgressResolution
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** Surfaced to the UI when a session start adopted a newer position from another device. */
data class ProgressJump(
    val libraryItemId: String,
    val episodeId: String?,
    val fromSec: Double,
    val toSec: Double,
)

@Serializable
internal data class ProgressOutboxPayload(
    val libraryItemId: String,
    val episodeId: String?,
    val currentTime: Double,
    val duration: Double,
    val progress: Double,
    val isFinished: Boolean,
    val lastUpdateMs: Long,
)

/**
 * Local-first progress with the conflict rules from docs/PLAN.md §4.2.
 *
 * Writes land in Room immediately and in the outbox second; nothing waits on the
 * network. Reads come from Room. The one network-first moment is [startSession],
 * which pulls before it pushes — the rule that stops lugu doing what the official app
 * does and overwriting a newer position from another device with a stale local one.
 */
@Singleton
class ProgressRepository @Inject constructor(
    private val client: AbsClient,
    private val progressDao: ProgressDao,
    private val outboxDao: OutboxDao,
    private val positionHistoryDao: PositionHistoryDao,
    private val clock: Clock,
) {
    /**
     * Records a position change big enough that it might not have been intended, so it
     * can always be undone. Normal listening drifts forward a few seconds at a time and
     * is not worth recording; a jump is.
     */
    suspend fun recordJump(
        account: ActiveAccount,
        itemId: String,
        episodeId: String?,
        fromSec: Double,
        toSec: Double,
        reason: String,
    ) {
        if (kotlin.math.abs(toSec - fromSec) < MIN_NOTABLE_JUMP_SEC) return
        positionHistoryDao.insert(
            PositionHistoryEntity(
                serverId = account.serverId,
                userId = account.userId,
                libraryItemId = itemId,
                episodeKey = episodeKeyOf(episodeId),
                fromSec = fromSec,
                toSec = toSec,
                atMs = clock.nowMs(),
                reason = reason,
            ),
        )
        positionHistoryDao.trimOlderThan(
            account.serverId,
            account.userId,
            clock.nowMs() - HISTORY_RETENTION_MS,
        )
    }

    fun observeHistory(account: ActiveAccount, itemId: String) =
        positionHistoryDao.observeForItem(account.serverId, account.userId, itemId)

    suspend fun lastJump(account: ActiveAccount, itemId: String) =
        positionHistoryDao.mostRecentForItem(account.serverId, account.userId, itemId)

    fun observe(account: ActiveAccount, itemId: String, episodeId: String?): Flow<MediaProgress?> =
        progressDao.observeOne(account.serverId, account.userId, itemId, episodeKeyOf(episodeId))
            .map { it?.toDomain() }

    fun observeAll(account: ActiveAccount): Flow<List<MediaProgress>> =
        progressDao.observeAll(account.serverId, account.userId).map { rows -> rows.map { it.toDomain() } }

    suspend fun get(account: ActiveAccount, itemId: String, episodeId: String?): MediaProgress? =
        progressDao.get(account.serverId, account.userId, itemId, episodeKeyOf(episodeId))?.toDomain()

    /** Bulk seed from a login or a full reconciliation sweep. Never clobbers dirty local rows. */
    suspend fun seedFromServer(account: ActiveAccount, remote: List<MediaProgressDto>) {
        if (remote.isEmpty()) return
        val existing = progressDao.dirty(account.serverId, account.userId)
            .associateBy { it.libraryItemId to it.episodeKey }

        val rows = remote.mapNotNull { dto ->
            val key = dto.libraryItemId to episodeKeyOf(dto.episodeId)
            val local = existing[key]
            // A dirty local row is a change the server has not accepted yet. Leave it
            // alone unless the server's copy is genuinely newer.
            if (local != null && local.lastUpdateMs >= dto.lastUpdate) return@mapNotNull null
            dto.toEntity(account, isDirty = false)
        }
        progressDao.upsertAll(rows)
    }

    /**
     * Pull-before-push. Called when playback of an item is about to start.
     *
     * Returns a [ProgressJump] when the server's position won and moved us materially,
     * so the UI can say so and offer an undo — an automatic correction the user cannot
     * see is indistinguishable from a bug.
     */
    suspend fun startSession(account: ActiveAccount, itemId: String, episodeId: String?): ProgressJump? {
        val local = get(account, itemId, episodeId)
        val server = runCatching { client.progress(itemId, episodeId)?.toDomain() }.getOrNull()

        return when (val resolution = ProgressConflictResolver.resolve(local, server)) {
            is ProgressResolution.AdoptServer -> {
                progressDao.upsert(resolution.server.toEntity(account, isDirty = false))
                resolution.replacedLocal?.let { replaced ->
                    ProgressJump(
                        libraryItemId = itemId,
                        episodeId = episodeId,
                        fromSec = replaced.currentTimeSec,
                        toSec = resolution.server.currentTimeSec,
                    )
                }
            }

            is ProgressResolution.KeepLocal -> {
                // Local is ahead: push it, but only through the guarded path.
                enqueuePush(account, resolution.local, server)
                null
            }

            ProgressResolution.InSync -> null
        }
    }

    /** Undo for an adopted jump. An explicit user action, so it bypasses the forward-only guard. */
    suspend fun revertJump(account: ActiveAccount, jump: ProgressJump, durationSec: Double) {
        record(
            account = account,
            itemId = jump.libraryItemId,
            episodeId = jump.episodeId,
            positionSec = jump.fromSec,
            durationSec = durationSec,
            isFinished = false,
            force = true,
        )
    }

    /**
     * The hot path: called on every pause, seek, chapter change, 5-second tick and
     * service teardown. Room write plus a superseding outbox entry, no network.
     */
    suspend fun record(
        account: ActiveAccount,
        itemId: String,
        episodeId: String?,
        positionSec: Double,
        durationSec: Double,
        isFinished: Boolean = false,
        force: Boolean = false,
    ) {
        val now = clock.nowMs()
        val existing = progressDao.get(account.serverId, account.userId, itemId, episodeKeyOf(episodeId))
        val fraction = if (durationSec > 0) (positionSec / durationSec).coerceIn(0.0, 1.0) else 0.0

        val entity = ProgressEntity(
            serverId = account.serverId,
            userId = account.userId,
            libraryItemId = itemId,
            episodeKey = episodeKeyOf(episodeId),
            currentTimeSec = positionSec,
            durationSec = durationSec,
            progress = fraction,
            // "Finished" is sticky: only an explicit user action un-sets it.
            isFinished = isFinished || (existing?.isFinished == true && !force),
            lastUpdateMs = now,
            startedAtMs = existing?.startedAtMs?.takeIf { it > 0 } ?: now,
            serverLastUpdateMs = existing?.serverLastUpdateMs ?: 0L,
            isDirty = true,
        )
        progressDao.upsert(entity)

        val knownServer = existing?.let {
            MediaProgress(
                libraryItemId = itemId,
                episodeId = episodeId,
                currentTimeSec = it.currentTimeSec,
                durationSec = it.durationSec,
                lastUpdateMs = it.serverLastUpdateMs,
            )
        }
        if (force || ProgressConflictResolver.mayPushAutomatically(entity.toDomain(), knownServer)) {
            enqueue(account, entity.toDomain())
        }
    }

    private suspend fun enqueuePush(account: ActiveAccount, local: MediaProgress, knownServer: MediaProgress?) {
        if (ProgressConflictResolver.mayPushAutomatically(local, knownServer)) enqueue(account, local)
    }

    private suspend fun enqueue(account: ActiveAccount, progress: MediaProgress) {
        val payload = ProgressOutboxPayload(
            libraryItemId = progress.libraryItemId,
            episodeId = progress.episodeId,
            currentTime = progress.currentTimeSec,
            duration = progress.durationSec,
            progress = progress.progress,
            isFinished = progress.isFinished,
            lastUpdateMs = progress.lastUpdateMs,
        )
        outboxDao.enqueueSuperseding(
            OutboxEntity(
                serverId = account.serverId,
                userId = account.userId,
                kind = OutboxKind.PROGRESS_UPDATE,
                // One pending entry per item: after a week offline we owe the server
                // the final position, not every tick along the way.
                dedupeKey = "${progress.libraryItemId}#${episodeKeyOf(progress.episodeId)}",
                payloadJson = AbsJson.encodeToString(ProgressOutboxPayload.serializer(), payload),
                createdAtMs = clock.nowMs(),
                attempts = 0,
                lastAttemptAtMs = 0,
                lastError = null,
            ),
        )
    }

    /** Drains one outbox entry. Returns true when the server accepted it. */
    internal suspend fun flushEntry(entry: OutboxEntity): Boolean {
        if (entry.kind != OutboxKind.PROGRESS_UPDATE) return false
        val payload = runCatching {
            AbsJson.decodeFromString(ProgressOutboxPayload.serializer(), entry.payloadJson)
        }.getOrNull() ?: return true // Undecodable payload: drop it rather than retry forever.

        client.updateProgress(
            itemId = payload.libraryItemId,
            episodeId = payload.episodeId,
            body = ProgressUpdateRequest(
                currentTime = payload.currentTime,
                duration = payload.duration,
                progress = payload.progress,
                isFinished = payload.isFinished,
            ),
        )
        progressDao.markClean(
            serverId = entry.serverId,
            userId = entry.userId,
            itemId = payload.libraryItemId,
            episodeKey = episodeKeyOf(payload.episodeId),
            serverLastUpdateMs = payload.lastUpdateMs,
        )
        return true
    }

    private companion object {
        /** Below this, a position change is ordinary listening rather than a jump. */
        const val MIN_NOTABLE_JUMP_SEC = 45.0
        const val HISTORY_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }

    /** Full reconciliation sweep: the server's whole progress table, merged in. */
    suspend fun reconcile(account: ActiveAccount): Result<Unit> = runCatching {
        seedFromServer(account, client.allProgress())
    }
}

internal fun ProgressEntity.toDomain(): MediaProgress = MediaProgress(
    libraryItemId = libraryItemId,
    episodeId = episodeKey.toEpisodeIdOrNull(),
    currentTimeSec = currentTimeSec,
    durationSec = durationSec,
    progress = progress,
    isFinished = isFinished,
    lastUpdateMs = lastUpdateMs,
    startedAtMs = startedAtMs,
)

internal fun MediaProgress.toEntity(account: ActiveAccount, isDirty: Boolean) = ProgressEntity(
    serverId = account.serverId,
    userId = account.userId,
    libraryItemId = libraryItemId,
    episodeKey = episodeKeyOf(episodeId),
    currentTimeSec = currentTimeSec,
    durationSec = durationSec,
    progress = progress,
    isFinished = isFinished,
    lastUpdateMs = lastUpdateMs,
    startedAtMs = startedAtMs,
    serverLastUpdateMs = lastUpdateMs,
    isDirty = isDirty,
)

internal fun MediaProgressDto.toEntity(account: ActiveAccount, isDirty: Boolean) =
    toDomain().toEntity(account, isDirty)
