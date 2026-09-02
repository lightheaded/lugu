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
import io.github.lightheaded.lugu.core.db.NOTHING_PUSHED_SEC
import io.github.lightheaded.lugu.core.db.ProgressEntity
import io.github.lightheaded.lugu.core.db.episodeKeyOf
import io.github.lightheaded.lugu.core.db.toEpisodeIdOrNull
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.model.ProgressConflictResolver
import io.github.lightheaded.lugu.core.model.ProgressResolution
import io.github.lightheaded.lugu.core.model.ServerCopySeen
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * A position moved by the app rather than by the listener, offered back for undoing.
 *
 * Originally only ever a newer position adopted from another device, which is why the
 * default notice reads as a bare "jumped from here to there" — with nothing else able to
 * cause one, saying so added nothing.
 *
 * [reason] exists because that is no longer true: a podcast's trim settings also move the
 * position, and "Jumped from 0:00 to 0:15" is a true description of an intro being skipped
 * that explains none of it. A cause the listener cannot see is indistinguishable from the
 * app losing their place, which is the complaint lugu exists to answer — so where the app
 * knows why, it says why. Null keeps the original wording for the case that has no better
 * explanation than the numbers themselves.
 */
data class ProgressJump(
    val libraryItemId: String,
    val episodeId: String?,
    val fromSec: Double,
    val toSec: Double,
    /** A short phrase naming the cause, e.g. "Skipped the intro". Null for a plain jump. */
    val reason: String? = null,
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
            // alone unless the server's copy came from somewhere other than here —
            // decided by the same rule playback uses, on the server's clock alone. The
            // old test compared the server's stamp with this device's and so handed a
            // week of offline listening to whichever machine had the faster clock.
            val server = dto.toDomain()
            if (local != null && !ProgressConflictResolver.serverCopyCameFromElsewhere(server, local.seenCopy())) {
                return@mapNotNull null
            }
            server.toEntity(
                account = account,
                isDirty = false,
                serverLastUpdateMs = server.lastUpdateMs,
                pushedTimeSec = local?.pushedTimeSec ?: NOTHING_PUSHED_SEC,
                pushedFinished = local?.pushedFinished ?: false,
            )
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
        val existing = progressDao.get(account.serverId, account.userId, itemId, episodeKeyOf(episodeId))
        val local = existing?.toDomain()
        val server = runCatching { client.progress(itemId, episodeId)?.toDomain() }.getOrNull()
        val seen = existing.seenCopy()

        return when (val resolution = ProgressConflictResolver.resolve(local, server, seen)) {
            is ProgressResolution.AdoptServer -> {
                // The server's revision goes in the column that holds server revisions,
                // so the *next* conflict is decided against a number from the same clock.
                progressDao.upsert(
                    resolution.server.toEntity(
                        account = account,
                        isDirty = false,
                        serverLastUpdateMs = resolution.server.lastUpdateMs,
                        pushedTimeSec = existing?.pushedTimeSec ?: NOTHING_PUSHED_SEC,
                        pushedFinished = existing?.pushedFinished ?: false,
                    ),
                )
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
                // The resolver has already decided that nothing else wrote the server's
                // copy, which is the same question the push guard asks. Asking it a
                // second time here is what left a position stranded on the phone: the
                // guard compared this device's clock with the server's, so on a device
                // running behind the server it refused every push it had just decided to
                // make. Keeping one rule in one place is the fix.
                enqueue(account, resolution.local)
                null
            }

            ProgressResolution.InSync -> {
                // Worth a write even with nothing to resolve: the stamp just read is what
                // makes the *next* conflict answerable without another round trip.
                if (existing != null && server != null) {
                    progressDao.noteServerStamp(
                        serverId = account.serverId,
                        userId = account.userId,
                        itemId = itemId,
                        episodeKey = episodeKeyOf(episodeId),
                        serverLastUpdateMs = server.lastUpdateMs,
                    )
                }
                null
            }
        }
    }

    /** What this device knows about the server's copy, read off the stored row. */
    private fun ProgressEntity?.seenCopy(): ServerCopySeen {
        if (this == null) return ServerCopySeen()
        return ServerCopySeen(
            lastUpdateMs = serverLastUpdateMs,
            pushedTimeSec = pushedTimeSec.takeIf { it >= 0.0 },
            pushedFinished = pushedFinished,
        )
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
            pushedTimeSec = existing?.pushedTimeSec ?: NOTHING_PUSHED_SEC,
            pushedFinished = existing?.pushedFinished ?: false,
            isDirty = true,
        )
        progressDao.upsert(entity)

        // Enqueued unconditionally, and the removed guard is worth naming. It asked
        // whether the server holds a copy nobody here accounted for — a question that
        // needs the server's current copy, which this path deliberately never fetches.
        // What it actually compared was this device's clock against a column that
        // sometimes held the server's, so its answer was clock skew rather than
        // provenance. [startSession] asks the real question, with the server's copy in
        // hand, before a single second of this item is played.
        enqueue(account, entity.toDomain())
    }

    /**
     * Marks something finished, or not.
     *
     * An explicit act, so it goes out with `force`: [record] treats finished as sticky
     * precisely so that a stray position update near the end cannot un-finish a book, and
     * the only thing that should be able to is somebody saying so.
     *
     * Un-finishing resets the position to the start, which is what the server's own web
     * client does and what the act means — nobody marks a book unfinished in order to stay
     * at the last second of it. Nothing is lost that was not already lost: a finished book
     * is at its end, so there is no place to keep.
     *
     * [fallbackDurationSec] is used only when no progress row exists yet, since marking an
     * untouched book finished is a normal thing to do and there is no stored duration to
     * read in that case.
     */
    suspend fun setFinished(
        account: ActiveAccount,
        itemId: String,
        episodeId: String?,
        isFinished: Boolean,
        fallbackDurationSec: Double = 0.0,
    ) {
        val existing = progressDao.get(account.serverId, account.userId, itemId, episodeKeyOf(episodeId))
        val duration = existing?.durationSec?.takeIf { it > 0 } ?: fallbackDurationSec
        record(
            account = account,
            itemId = itemId,
            episodeId = episodeId,
            positionSec = if (isFinished) duration else 0.0,
            durationSec = duration,
            isFinished = isFinished,
            force = true,
        )
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
            pushedTimeSec = payload.currentTime,
            pushedFinished = payload.isFinished,
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

/**
 * A server copy, written down as a row.
 *
 * [serverLastUpdateMs] is passed in rather than taken from [lastUpdateMs], even though on
 * a server copy the two are the same number. They are the same *value* and they are not
 * the same *fact*: one is when the listening happened and orders the Continue shelf, the
 * other is the server's own revision of this row and settles conflicts. Letting one field
 * fill both columns is how a push's local timestamp came to be read as a server revision.
 */
internal fun MediaProgress.toEntity(
    account: ActiveAccount,
    isDirty: Boolean,
    serverLastUpdateMs: Long,
    pushedTimeSec: Double = NOTHING_PUSHED_SEC,
    pushedFinished: Boolean = false,
) = ProgressEntity(
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
    serverLastUpdateMs = serverLastUpdateMs,
    pushedTimeSec = pushedTimeSec,
    pushedFinished = pushedFinished,
    isDirty = isDirty,
)

