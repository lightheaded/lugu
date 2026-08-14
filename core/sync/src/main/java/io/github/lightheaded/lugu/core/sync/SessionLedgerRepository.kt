package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.DeviceInfoDto
import io.github.lightheaded.lugu.core.api.LocalSessionDto
import io.github.lightheaded.lugu.core.db.SessionLedgerDao
import io.github.lightheaded.lugu.core.db.SessionLedgerEntity
import io.github.lightheaded.lugu.core.model.ListeningSession
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Every listening session, recorded locally whether or not the server ever hears
 * about it (docs/PLAN.md §4.2). It is both the offline-playback record that gets
 * replayed on reconnect and, later, the user-visible history that makes "put me back
 * where I was yesterday evening" possible.
 */
@Singleton
class SessionLedgerRepository @Inject constructor(
    private val client: AbsClient,
    private val dao: SessionLedgerDao,
    private val deviceInfo: DeviceInfoDto,
    private val clock: Clock,
) {
    fun observeRecent(account: ActiveAccount): Flow<List<ListeningSession>> =
        dao.observeRecent(account.serverId, account.userId).map { rows ->
            rows.map {
                ListeningSession(
                    id = it.id,
                    libraryItemId = it.libraryItemId,
                    episodeId = it.episodeId,
                    startedAtMs = it.startedAtMs,
                    updatedAtMs = it.updatedAtMs,
                    startTimeSec = it.startTimeSec,
                    currentTimeSec = it.currentTimeSec,
                    timeListeningSec = it.timeListeningSec,
                    isLocal = it.isLocal,
                    uploaded = it.isUploaded,
                )
            }
        }

    /**
     * Opens a ledger row. [serverSessionId] is null when the server could not be
     * reached, in which case the row is replayed later through `/api/session/local-all`.
     */
    suspend fun open(
        account: ActiveAccount,
        libraryItemId: String,
        episodeId: String?,
        title: String,
        author: String,
        mediaType: String,
        startTimeSec: Double,
        durationSec: Double,
        serverSessionId: String?,
    ): String {
        val now = clock.nowMs()
        val id = serverSessionId ?: UUID.randomUUID().toString()
        dao.upsert(
            SessionLedgerEntity(
                id = id,
                serverId = account.serverId,
                userId = account.userId,
                libraryItemId = libraryItemId,
                episodeId = episodeId,
                displayTitle = title,
                displayAuthor = author,
                mediaType = mediaType,
                startedAtMs = now,
                updatedAtMs = now,
                startTimeSec = startTimeSec,
                currentTimeSec = startTimeSec,
                timeListeningSec = 0.0,
                durationSec = durationSec,
                isLocal = serverSessionId == null,
                isUploaded = false,
                serverSessionId = serverSessionId,
            ),
        )
        return id
    }

    suspend fun update(id: String, currentTimeSec: Double, listenedDeltaSec: Double) {
        val existing = dao.byId(id) ?: return
        dao.upsert(
            existing.copy(
                currentTimeSec = currentTimeSec,
                timeListeningSec = existing.timeListeningSec + listenedDeltaSec.coerceAtLeast(0.0),
                updatedAtMs = clock.nowMs(),
            ),
        )
    }

    /**
     * Replays offline sessions. The client-generated UUID is the server's id too, so a
     * retry after a half-failed upload cannot double-count listening time.
     */
    suspend fun uploadPending(account: ActiveAccount) {
        val pending = dao.pendingUploads(account.serverId, account.userId)
        if (pending.isEmpty()) return

        val payload = pending.map {
            LocalSessionDto(
                id = it.id,
                libraryItemId = it.libraryItemId,
                episodeId = it.episodeId,
                deviceInfo = deviceInfo,
                startedAt = it.startedAtMs,
                updatedAt = it.updatedAtMs,
                startTime = it.startTimeSec,
                currentTime = it.currentTimeSec,
                timeListening = it.timeListeningSec,
                duration = it.durationSec,
                mediaType = it.mediaType.lowercase(),
                displayTitle = it.displayTitle,
                displayAuthor = it.displayAuthor,
            )
        }
        runCatching { client.uploadLocalSessions(payload) }
            .onSuccess { dao.markUploaded(pending.map { it.id }) }
    }
}
