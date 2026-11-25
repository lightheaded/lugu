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
import io.github.lightheaded.lugu.core.db.ServerCopyWrite
import io.github.lightheaded.lugu.core.db.episodeKeyOf
import io.github.lightheaded.lugu.core.db.toEpisodeIdOrNull
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.model.ProgressConflictResolver
import io.github.lightheaded.lugu.core.model.ProgressKey
import io.github.lightheaded.lugu.core.model.ProgressResolution
import io.github.lightheaded.lugu.core.model.ServerCopySeen
import io.github.lightheaded.lugu.core.model.SweepVerdict
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
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

/** A hold on one item's pushes, from [ProgressRepository.holdPushes]. */
class PushHold internal constructor(internal val key: String) {
    internal val done = CompletableDeferred<Unit>()
}

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
 * which pulls before it pushes (a downloaded item pulls just after it starts, with its
 * pushes held — see [holdPushes]) — the rule that stops lugu doing what the official app
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

    /**
     * Records a play or a pause at [atSec], so the history shows when listening started
     * and stopped, and not only where the position jumped. Every one is kept: a pause is
     * the position somebody wants back after they fell asleep.
     */
    suspend fun recordPlayback(
        account: ActiveAccount,
        itemId: String,
        episodeId: String?,
        atSec: Double,
        reason: String,
    ) {
        positionHistoryDao.insert(
            PositionHistoryEntity(
                serverId = account.serverId,
                userId = account.userId,
                libraryItemId = itemId,
                episodeKey = episodeKeyOf(episodeId),
                fromSec = atSec,
                toSec = atSec,
                atMs = clock.nowMs(),
                reason = reason,
            ),
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

    /**
     * Bulk seed from a login or a full reconciliation sweep.
     *
     * A clean or absent row takes the server's copy, unless a push for it waits in the
     * outbox: that push is newer than anything the sweep read. For a dirty row, newer
     * listening wins: [ProgressConflictResolver.sweepVerdict] is the rule, and the one place
     * to change which side wins. A copy this device accounted for, or one that this device
     * listened clearly later than, leaves the row and its push alone. The push then goes
     * out at the next flush, and a finished mark made offline goes with it.
     *
     * A row kept over another device's copy records that copy's stamp, so that the check
     * at a session start reads it as accounted for and does not adopt the older position
     * before the push went out. No history entry is written for it: nothing on the phone
     * moved.
     *
     * [except] is the item in the player. Its position moves only through a session start,
     * which can seek the player and show the jump. A row written under a playing item is
     * overwritten by the next tick, which then pushes the old position over the new one.
     *
     * A dirty row that loses to the server's copy is handled as [settle] handles it: its
     * queued push goes in the same write, and the position it held goes into the history
     * as a jump from another device. The usual case is a position listened to while the
     * sign-in had lapsed, met at the next sign-in by a position that another device wrote
     * later. Nobody is in the player to be offered an Undo, so the history is where the
     * listener finds the old position again.
     */
    suspend fun seedFromServer(
        account: ActiveAccount,
        remote: List<MediaProgressDto>,
        except: ProgressKey? = null,
    ) {
        if (remote.isEmpty()) return
        val existing = progressDao.dirty(account.serverId, account.userId)
            .associateBy { it.libraryItemId to it.episodeKey }

        // Dirty rows kept over another device's copy, with the stamp of that copy.
        val outlistened = mutableListOf<Pair<ProgressEntity, Long>>()

        val writes = remote.mapNotNull { dto ->
            if (except != null && except.libraryItemId == dto.libraryItemId && except.episodeId == dto.episodeId) {
                return@mapNotNull null
            }
            val key = dto.libraryItemId to episodeKeyOf(dto.episodeId)
            val local = existing[key]
            // A dirty local row is a change the server has not accepted yet. Which side
            // wins is the resolver's to say: newer listening, judged across a margin wide
            // enough that a modest clock error cannot pick the older side.
            val server = dto.toDomain()
            if (local != null) {
                when (ProgressConflictResolver.sweepVerdict(local.toDomain(), server, local.seenCopy())) {
                    SweepVerdict.KEEP_LOCAL -> return@mapNotNull null
                    SweepVerdict.KEEP_LOCAL_NEWER -> {
                        outlistened += local to server.lastUpdateMs
                        return@mapNotNull null
                    }
                    SweepVerdict.TAKE_SERVER -> Unit
                }
            }
            ServerCopyWrite(
                row = server.toEntity(
                    account = account,
                    isDirty = false,
                    serverLastUpdateMs = server.lastUpdateMs,
                    pushedTimeSec = local?.pushedTimeSec ?: NOTHING_PUSHED_SEC,
                    pushedFinished = local?.pushedFinished ?: false,
                ),
                replacedLastUpdateMs = local?.lastUpdateMs,
                queuedPushKey = dedupeKeyOf(dto.libraryItemId, dto.episodeId),
            )
        }
        val written = progressDao.writeServerCopies(writes)

        outlistened.forEach { (row, serverStamp) ->
            progressDao.noteOutlistenedServerStamp(
                serverId = row.serverId,
                userId = row.userId,
                itemId = row.libraryItemId,
                episodeKey = row.episodeKey,
                readServerLastUpdateMs = row.serverLastUpdateMs,
                serverLastUpdateMs = serverStamp,
            )
        }

        written.forEach { write ->
            if (write.replacedLastUpdateMs == null) return@forEach
            val replaced = existing[write.row.libraryItemId to write.row.episodeKey] ?: return@forEach
            recordJump(
                account = account,
                itemId = write.row.libraryItemId,
                episodeId = write.row.episodeKey.toEpisodeIdOrNull(),
                fromSec = replaced.currentTimeSec,
                toSec = write.row.currentTimeSec,
                reason = OTHER_DEVICE_REASON,
            )
        }
    }

    /**
     * Pull-before-push. Called when playback of an item is about to start.
     *
     * Returns a [ProgressJump] when the server's position won and moved us materially,
     * so the UI can say so and offer an undo — an automatic correction the user cannot
     * see is indistinguishable from a bug.
     *
     * A downloaded item does not call this before it plays: it starts at once and asks
     * afterwards, through [fetchServerCopy], [resolveAgainst] and [settle], with its
     * pushes held until then — see [holdPushes].
     */
    suspend fun startSession(account: ActiveAccount, itemId: String, episodeId: String?): ProgressJump? {
        val server = fetchServerCopy(itemId, episodeId)
        return settle(account, itemId, episodeId, server, resolveAgainst(account, itemId, episodeId, server))
    }

    /** The server's copy of one item's progress. Null when it has none or cannot be reached. */
    suspend fun fetchServerCopy(itemId: String, episodeId: String?): MediaProgress? =
        runCatching { client.progress(itemId, episodeId)?.toDomain() }.getOrNull()

    /** What the conflict rules say about [server] against the row in Room now. Writes nothing. */
    suspend fun resolveAgainst(
        account: ActiveAccount,
        itemId: String,
        episodeId: String?,
        server: MediaProgress?,
    ): ProgressResolution {
        val existing = progressDao.get(account.serverId, account.userId, itemId, episodeKeyOf(episodeId))
        return ProgressConflictResolver.resolve(existing?.toDomain(), server, existing.seenCopy())
    }

    /**
     * Writes what [resolveAgainst] decided.
     *
     * An adopted server copy also drops the push queued for the item. That push holds the
     * position the server's copy has just replaced, and sending it would undo the adoption
     * on the server — the other device's listening, lost after all.
     */
    suspend fun settle(
        account: ActiveAccount,
        itemId: String,
        episodeId: String?,
        server: MediaProgress?,
        resolution: ProgressResolution,
    ): ProgressJump? {
        val existing = progressDao.get(account.serverId, account.userId, itemId, episodeKeyOf(episodeId))
        return when (resolution) {
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
                outboxDao.deleteByDedupeKey(
                    serverId = account.serverId,
                    userId = account.userId,
                    kind = OutboxKind.PROGRESS_UPDATE,
                    dedupeKey = dedupeKeyOf(itemId, episodeId),
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

    /**
     * Stops this device pushing a position for one item until [releasePushes].
     *
     * A downloaded item plays before the server is asked where the listener got to. Until
     * that answer is in, every position this device holds for the item can be older than
     * one from another device, and a push of it — a pause in the first second is enough —
     * would overwrite that. Positions still go into Room and the outbox; only the send
     * waits. A hold that nobody releases ends by itself after [PUSH_HOLD_MAX_MS], so a
     * start that was dropped halfway cannot stop sync for good.
     */
    fun holdPushes(account: ActiveAccount, itemId: String, episodeId: String?): PushHold {
        val hold = PushHold(holdKeyOf(account.serverId, account.userId, dedupeKeyOf(itemId, episodeId)))
        // A newer start of the same item replaces the older hold. The older one is
        // released, because a flush waiting on it re-reads the map and waits for this one.
        pushHolds.put(hold.key, hold.done)?.complete(Unit)
        return hold
    }

    fun releasePushes(hold: PushHold) {
        hold.done.complete(Unit)
        pushHolds.remove(hold.key, hold.done)
    }

    private suspend fun awaitPushHold(serverId: String, userId: String, dedupeKey: String) {
        val key = holdKeyOf(serverId, userId, dedupeKey)
        withTimeoutOrNull(PUSH_HOLD_MAX_MS) {
            while (true) {
                val held = pushHolds[key] ?: break
                held.await()
            }
        }
    }

    private val pushHolds = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

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

    /** The progress as it stands now, kept by a caller that may need to undo a change. */
    suspend fun snapshot(account: ActiveAccount, itemId: String, episodeId: String?): MediaProgress? =
        get(account, itemId, episodeId)

    /**
     * Undo for [setFinished] and other explicit changes: puts back the position and the
     * finished flag from [snapshot]. A null snapshot means there was no progress, so the
     * item goes back to the start, not finished.
     *
     * Goes out with `force` for the same reason as [setFinished]: finished is sticky in
     * [record], and an undo of "mark finished" must be able to clear it.
     *
     * [ifUnchangedSince] is the row's `lastUpdateMs` right after the change being undone.
     * When the row has been written since (playback, a sync), the undo would put an old
     * position and flag over newer listening, so nothing is written and the result is false.
     */
    suspend fun restore(
        account: ActiveAccount,
        itemId: String,
        episodeId: String?,
        snapshot: MediaProgress?,
        ifUnchangedSince: Long? = null,
    ): Boolean {
        val existing = progressDao.get(account.serverId, account.userId, itemId, episodeKeyOf(episodeId))
        if (ifUnchangedSince != null && existing?.lastUpdateMs != ifUnchangedSince) return false
        val duration = snapshot?.durationSec?.takeIf { it > 0 }
            ?: existing?.durationSec
            ?: 0.0
        record(
            account = account,
            itemId = itemId,
            episodeId = episodeId,
            positionSec = snapshot?.currentTimeSec ?: 0.0,
            durationSec = duration,
            isFinished = snapshot?.isFinished ?: false,
            force = true,
        )
        return true
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
                dedupeKey = dedupeKeyOf(progress.libraryItemId, progress.episodeId),
                payloadJson = AbsJson.encodeToString(ProgressOutboxPayload.serializer(), payload),
                createdAtMs = clock.nowMs(),
                attempts = 0,
                lastAttemptAtMs = 0,
                lastError = null,
            ),
        )
    }

    /**
     * Drains one outbox entry. Returns true when the entry is done with: the server
     * accepted it, or it no longer needs sending. [OutboxFlusher] is the caller. Public so
     * that the push hold can be tested over real Room, which lives in another module.
     */
    suspend fun flushEntry(entry: OutboxEntity): Boolean {
        if (entry.kind != OutboxKind.PROGRESS_UPDATE) return false
        val payload = runCatching {
            AbsJson.decodeFromString(ProgressOutboxPayload.serializer(), entry.payloadJson)
        }.getOrNull() ?: return true // Undecodable payload: drop it rather than retry forever.

        awaitPushHold(entry.serverId, entry.userId, entry.dedupeKey)
        // The check that held the push can have adopted another device's position and
        // dropped this entry. Sending it anyway would undo that on the server.
        if (!outboxDao.exists(entry.rowId)) return true

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
            // A row written while the PATCH was in flight has a newer push waiting, and
            // stays dirty until that one goes. See ProgressDao.markClean.
            pushedLastUpdateMs = payload.lastUpdateMs,
        )
        return true
    }

    private companion object {
        /**
         * The longest a push waits for a held item's check. Long enough for one slow
         * request on a bad network, short enough that a lost release costs one late push.
         */
        const val PUSH_HOLD_MAX_MS = 30_000L

        fun dedupeKeyOf(itemId: String, episodeId: String?): String = "$itemId#${episodeKeyOf(episodeId)}"

        fun holdKeyOf(serverId: String, userId: String, dedupeKey: String): String = "$serverId|$userId|$dedupeKey"

        /**
         * The history reason for a position replaced by another device's. The same stored
         * string as `SeekReasons.OTHER_DEVICE` in playback, which the history screen reads
         * as "From another device".
         */
        const val OTHER_DEVICE_REASON = "other-device"

        /** Below this, a position change is ordinary listening rather than a jump. */
        const val MIN_NOTABLE_JUMP_SEC = 45.0
        const val HISTORY_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }

    /** Full reconciliation sweep: the server's whole progress table, merged in. */
    suspend fun reconcile(account: ActiveAccount, except: ProgressKey? = null): Result<Unit> = runCatching {
        seedFromServer(account, client.allProgress(), except)
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

