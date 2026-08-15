package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.AbsHttpException
import io.github.lightheaded.lugu.core.db.BookmarkDao
import io.github.lightheaded.lugu.core.db.BookmarkEntity
import io.github.lightheaded.lugu.core.model.Bookmark
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToLong

/**
 * Bookmarks, local first.
 *
 * Same shape as progress: the write lands in Room and the screen updates from Room, then
 * the server is told. Someone marking a place in a tunnel gets a bookmark; the alternative
 * — a button that only works with signal — is the behaviour that makes people stop
 * trusting the feature and start remembering timestamps in their head.
 *
 * The server addresses a bookmark by `(item, time)` with no id, so time is rounded to
 * whole seconds on the way in. Rounding at the boundary rather than at display time is
 * what guarantees that the row created here and the row the server made can be deleted by
 * the same key later.
 */
@Singleton
class BookmarkRepository @Inject constructor(
    private val client: AbsClient,
    private val dao: BookmarkDao,
    private val clock: Clock,
) {
    fun observe(account: ActiveAccount, itemId: String): Flow<List<Bookmark>> =
        dao.observeForItem(account.serverId, account.userId, itemId).map { rows ->
            rows.map { it.toDomain() }
        }

    /**
     * Adds a bookmark and pushes it. Returns the local row either way — a failed push is
     * a row still marked dirty, not a lost bookmark.
     */
    suspend fun add(
        account: ActiveAccount,
        itemId: String,
        positionSec: Double,
        title: String,
    ): Bookmark {
        val timeSec = positionSec.coerceAtLeast(0.0).roundToLong()
        val entity = BookmarkEntity(
            serverId = account.serverId,
            userId = account.userId,
            libraryItemId = itemId,
            timeSec = timeSec,
            title = title.ifBlank { defaultTitle(timeSec) },
            createdAtMs = clock.nowMs(),
            isDirty = true,
        )
        dao.upsert(entity)
        push(account, entity)
        return entity.toDomain()
    }

    suspend fun rename(account: ActiveAccount, itemId: String, timeSec: Long, title: String) {
        val existing = dao.forItem(account.serverId, account.userId, itemId)
            .firstOrNull { it.timeSec == timeSec } ?: return
        val updated = existing.copy(title = title.ifBlank { defaultTitle(timeSec) }, isDirty = true)
        dao.upsert(updated)
        push(account, updated)
    }

    /**
     * Deletes locally at once and tells the server after.
     *
     * A failed delete leaves a tombstone rather than removing the row outright: a row that
     * simply vanished here would be handed straight back by the next pull, and a bookmark
     * that comes back from the dead is worse than one that takes a while to go.
     */
    suspend fun remove(account: ActiveAccount, itemId: String, timeSec: Long) {
        val existing = dao.forItem(account.serverId, account.userId, itemId)
            .firstOrNull { it.timeSec == timeSec } ?: return
        dao.upsert(existing.copy(isDirty = true, isDeleted = true))
        val sent = runCatching { client.deleteBookmark(itemId, timeSec) }.isSuccess
        if (sent) dao.deleteRow(account.serverId, account.userId, itemId, timeSec)
    }

    /**
     * Reconciles with the server: push what is owed, then adopt what it has.
     *
     * Push first. Pulling first would overwrite a local bookmark with a server that has
     * never heard of it, which is the one ordering that can lose someone's work.
     */
    suspend fun sync(account: ActiveAccount): Result<Int> = runCatching {
        dao.dirty(account.serverId, account.userId).forEach { push(account, it) }

        val remote = client.allBookmarks()
        dao.deleteSettled(account.serverId, account.userId)
        dao.upsertAll(
            remote.map {
                BookmarkEntity(
                    serverId = account.serverId,
                    userId = account.userId,
                    libraryItemId = it.libraryItemId,
                    timeSec = it.time,
                    title = it.title.ifBlank { defaultTitle(it.time) },
                    // The server sends seconds; a bookmark created in 1970 is a bookmark
                    // whose date is simply unknown, not one worth showing as such.
                    createdAtMs = if (it.createdAt > 0) it.createdAt else clock.nowMs(),
                )
            },
        )
        remote.size
    }

    /** One push. Clears the dirty flag only when the server actually took it. */
    private suspend fun push(account: ActiveAccount, entity: BookmarkEntity) {
        val sent = runCatching {
            if (entity.isDeleted) {
                client.deleteBookmark(entity.libraryItemId, entity.timeSec)
            } else {
                // Create, and fall back to a rename only when the server actually answered:
                // it rejects a second bookmark at the same time, which is exactly what a
                // retry of a create that already landed looks like. A failure with no
                // answer at all means there is no network, and trying again immediately
                // would only spend a second timeout to learn the same thing.
                runCatching { client.createBookmark(entity.libraryItemId, entity.timeSec, entity.title) }
                    .getOrElse { failure ->
                        if (failure !is AbsHttpException) throw failure
                        client.updateBookmark(entity.libraryItemId, entity.timeSec, entity.title)
                    }
            }
        }.isSuccess
        if (!sent) return

        if (entity.isDeleted) {
            dao.deleteRow(account.serverId, account.userId, entity.libraryItemId, entity.timeSec)
        } else {
            dao.upsert(entity.copy(isDirty = false))
        }
    }

    /** "At 1:23:45" — a position is a better name than "Bookmark 3". */
    private fun defaultTitle(timeSec: Long): String {
        val hours = timeSec / 3600
        val minutes = (timeSec % 3600) / 60
        val seconds = timeSec % 60
        return if (hours > 0) {
            "At %d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "At %d:%02d".format(minutes, seconds)
        }
    }
}

private fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    libraryItemId = libraryItemId,
    timeSec = timeSec,
    title = title,
    createdAtMs = createdAtMs,
    isPending = isDirty,
)
