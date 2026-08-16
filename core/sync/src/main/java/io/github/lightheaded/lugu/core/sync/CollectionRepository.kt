package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.AbsHttpException
import io.github.lightheaded.lugu.core.api.AuthExpiredException
import io.github.lightheaded.lugu.core.api.CollectionDto
import io.github.lightheaded.lugu.core.api.addToCollection
import io.github.lightheaded.lugu.core.api.collection
import io.github.lightheaded.lugu.core.api.collections
import io.github.lightheaded.lugu.core.api.removeFromCollection
import io.github.lightheaded.lugu.core.db.CollectionDao
import io.github.lightheaded.lugu.core.db.CollectionEntity
import io.github.lightheaded.lugu.core.db.CollectionItemEntity
import io.github.lightheaded.lugu.core.db.CollectionSummary
import io.github.lightheaded.lugu.core.db.LibraryDao
import io.github.lightheaded.lugu.core.model.LibraryItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Collections, mirrored for reading and never for writing.
 *
 * Reads come from Room like everything else, so the list of collections and the contents of
 * one are both there on a cold start with no network. Writes are the opposite: a membership
 * change goes to the server, and what the server says afterwards is what gets stored.
 *
 * There is deliberately **no offline edit queue** here, unlike progress. A collection is
 * shared state — anyone with access to the library can change it, from any client — and it
 * is an ordered list whose order the server owns. Two people who reorder it while offline
 * produce two histories with no merge that is right, and a queued "add" replayed tomorrow
 * would silently undo a removal somebody made today. An edit attempted with no server is
 * therefore refused outright and says why, which is a smaller harm than accepting it now
 * and losing it later.
 */
@Singleton
class CollectionRepository @Inject constructor(
    private val client: AbsClient,
    private val collectionDao: CollectionDao,
    private val libraryDao: LibraryDao,
    private val clock: Clock,
) {
    /**
     * When the last full pass finished, so the next one can decline to run.
     *
     * In memory rather than on disk, and on purpose: forgetting it on a cold start is the
     * right behaviour, because that is exactly when the mirror is most likely to be stale.
     */
    private var lastSyncAtMs: Long = 0

    /** The collections of one library, or of every library when [libraryId] is null. */
    fun observeCollections(
        account: ActiveAccount,
        libraryId: String? = null,
    ): Flow<List<CollectionSummary>> =
        collectionDao.observeAll(account.serverId, account.userId, libraryId)

    /**
     * One collection's items, in the collection's own order.
     *
     * Rows are joined against the library mirror, so an item the mirror has not seen is
     * absent rather than half-drawn — which also contains an upstream leak: the library
     * listing hands back every collection's contents without applying the reader's own tag
     * and explicit-content restrictions, and the join drops anything they were not allowed
     * to see in the first place.
     */
    fun observeItems(account: ActiveAccount, collectionId: String): Flow<List<LibraryItem>> =
        collectionDao.observeItems(account.serverId, account.userId, collectionId).map { rows ->
            rows.map { it.toDomain() }
        }

    /** Which collections hold this item — the question the item page asks. */
    fun observeMembership(account: ActiveAccount, itemId: String): Flow<Set<String>> =
        collectionDao.observeMembership(account.serverId, account.userId, itemId).map { it.toSet() }

    /**
     * Re-mirrors every collection on the server, and sweeps the ones it no longer has.
     *
     * Every library is pulled in one pass rather than one at a time, because the sweep at
     * the end is account-wide: a pass that refreshed a single library and then swept would
     * delete the collections of all the others.
     *
     * Rate-limited unless [force] is set, which is what a pull-to-refresh passes. The
     * listing has no lighter form — the server sends every member as a complete expanded
     * item whatever is asked of it, so the response runs to several megabytes on a library
     * of ordinary size — and running that on every screen that wants a collection name
     * would spend most of a phone's data budget restating what Room already knows.
     */
    suspend fun sync(account: ActiveAccount, force: Boolean = false): Result<Int> = runCatching {
        val startedAt = clock.nowMs()
        if (!force && lastSyncAtMs > 0 && startedAt - lastSyncAtMs < SYNC_INTERVAL_MS) return@runCatching 0

        val known = collectionDao.observeAll(account.serverId, account.userId)
            .first()
            .map { it.id }
            .toSet()
        val seen = mutableSetOf<String>()

        libraryDao.all(account.serverId, account.userId).forEach { library ->
            val remote = fetch(library.id) ?: return@forEach
            remote.forEach { dto ->
                store(account, dto, library.id, startedAt)
                seen += dto.id
            }
        }

        // Membership rows would otherwise outlive the collection they belong to: the sweep
        // below drops the collection row, and nothing in the schema would ever revisit its
        // items again.
        (known - seen).forEach { collectionDao.clearItems(account.serverId, account.userId, it) }
        collectionDao.deleteStale(account.serverId, account.userId, startedAt)
        lastSyncAtMs = startedAt
        seen.size
    }

    /**
     * Re-mirrors one collection.
     *
     * What a collection's own page asks for. It is both far cheaper than the full pass and
     * the better-behaved of the two endpoints: this one applies the reader's restrictions
     * to the books it returns, where the library listing does not.
     */
    suspend fun refresh(account: ActiveAccount, collectionId: String): Result<Unit> = runCatching {
        val dto = client.collection(collectionId)
        store(account, dto, dto.libraryId, clock.nowMs())
    }

    /** Adds the item, and stores the collection the server hands back. */
    suspend fun add(account: ActiveAccount, collectionId: String, itemId: String): Result<Unit> =
        edit(account) { client.addToCollection(collectionId, itemId) }

    suspend fun remove(account: ActiveAccount, collectionId: String, itemId: String): Result<Unit> =
        edit(account) { client.removeFromCollection(collectionId, itemId) }

    /**
     * One library's collections, or null when the server would not say.
     *
     * A 4xx is treated as "this library has none to give" and the pass carries on. Anything
     * else — a network fault, a 5xx — is rethrown so the whole pass aborts: the sweep at the
     * end deletes whatever the pass did not see, so a failure quietly read as "nothing here"
     * would empty the mirror the moment the phone lost signal.
     */
    private suspend fun fetch(libraryId: String): List<CollectionDto>? = try {
        client.collections(libraryId)
    } catch (e: AbsHttpException) {
        if (e.status in 400..499) null else throw e
    }

    /**
     * Sends one change and stores the answer.
     *
     * Both edit endpoints reply with the whole collection as it now stands, so there is
     * nothing to fetch afterwards and nothing to guess: the order in that reply is the
     * order, including whatever else has changed in a list other people also edit.
     */
    private suspend fun edit(
        account: ActiveAccount,
        change: suspend () -> CollectionDto,
    ): Result<Unit> = runCatching {
        val dto = change()
        store(account, dto, dto.libraryId, clock.nowMs())
    }.fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { Result.failure(editFailure(it)) },
    )

    private suspend fun store(
        account: ActiveAccount,
        dto: CollectionDto,
        libraryIdFallback: String,
        syncedAtMs: Long,
    ) {
        collectionDao.upsertAll(
            listOf(
                CollectionEntity(
                    serverId = account.serverId,
                    userId = account.userId,
                    id = dto.id,
                    libraryId = dto.libraryId.ifBlank { libraryIdFallback },
                    name = dto.name.ifBlank { "Untitled collection" },
                    description = dto.description,
                    updatedAtMs = dto.lastUpdate,
                    syncedAtMs = syncedAtMs,
                ),
            ),
        )
        // Wholesale replacement rather than a diff, for the reason set out in the KDoc of
        // CollectionDao.replaceItems: the position is the payload, so a diff would have to
        // reproduce the whole ordering anyway.
        collectionDao.replaceItems(
            account.serverId,
            account.userId,
            dto.id,
            dto.books.mapIndexed { index, book ->
                CollectionItemEntity(
                    serverId = account.serverId,
                    userId = account.userId,
                    collectionId = dto.id,
                    libraryItemId = book.id,
                    position = index,
                )
            },
        )
    }

    private companion object {
        /** Long enough that moving between the screens costs nothing, short enough to feel live. */
        const val SYNC_INTERVAL_MS = 5L * 60 * 1000
    }
}

/**
 * Why an edit failed, in words worth putting in front of somebody.
 *
 * The offline case gets a sentence of its own because it is the one that looks like a bug:
 * every other list in this app can be changed with no signal, and a collection cannot. It is
 * shared, ordered, server-owned state, so the refusal is the design rather than a gap in it
 * — see the class KDoc.
 */
private fun editFailure(cause: Throwable): Throwable = when {
    cause is AuthExpiredException -> cause
    cause is AbsHttpException && cause.status == 403 ->
        IllegalStateException("Your account is not allowed to change collections.", cause)
    cause is AbsHttpException && cause.status == 404 ->
        IllegalStateException("That collection is no longer on the server.", cause)
    cause is AbsHttpException ->
        IllegalStateException("The server refused the change (HTTP ${cause.status}).", cause)
    else -> IllegalStateException(
        "A collection can only be changed while the server is reachable.",
        cause,
    )
}
