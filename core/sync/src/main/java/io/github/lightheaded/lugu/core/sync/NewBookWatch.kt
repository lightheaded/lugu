package io.github.lightheaded.lugu.core.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One book that arrived in a library sync and was not on the phone before. */
data class NewBook(
    val libraryItemId: String,
    val libraryId: String,
    val title: String,
    val authorName: String?,
    /** The feed or metadata description, as HTML or plain text. The notifier cuts it. */
    val description: String?,
    val addedAtMs: Long,
)

/**
 * Who is told about new books. Bound in `:core:download`, which owns the notifications;
 * this module cannot see it, so the binding is optional and a build without it is silent.
 */
fun interface NewBookAnnouncer {
    fun announce(account: ActiveAccount, books: List<NewBook>)
}

/** One library of one account, the unit every marker belongs to. */
data class LibraryKey(val serverId: String, val userId: String, val libraryId: String) {
    val account: String get() = accountKeyOf(serverId, userId)

    companion object {
        fun of(account: ActiveAccount, libraryId: String) =
            LibraryKey(account.serverId, account.userId, libraryId)
    }
}

internal fun accountKeyOf(serverId: String, userId: String) = "$serverId|$userId"

/** What [NewBookWatch] keeps between passes. An interface so the rules test without Android. */
interface NewBookMarks {
    /** The newest `addedAt` a complete pass has seen in this library, or null for never. */
    suspend fun seenUpTo(key: LibraryKey): Long?

    suspend fun setSeenUpTo(key: LibraryKey, addedAtMs: Long)

    suspend fun isOpened(key: LibraryKey): Boolean

    suspend fun markOpened(key: LibraryKey)

    /** The account the last pass ran for, or null after a sign-in. */
    suspend fun lastAccount(): String?

    /** Makes [account] the last one and forgets every "seen up to" marker it has. */
    suspend fun startAccount(account: String)

    /** Forgets the last account, so the next pass is the first one of a session. */
    suspend fun forgetLastAccount()
}

/**
 * Finds the books that are new to somebody, during the library sync.
 *
 * ### What "new" means
 *
 * A book is new when a complete sync pass finds it, it was not in Room for this account
 * before the pass, and the server added it after the newest `addedAt` that an earlier
 * pass of the same library saw. The marker is a date, not a count: a count cannot tell
 * one book added and one removed from no change at all.
 *
 * ### When nothing is new, by rule
 *
 * The marker only says something when an earlier pass wrote it. So these passes take
 * stock and tell nobody:
 *
 * - **The first pass of a library.** No marker yet.
 * - **The first pass after a sign-in.** [startSession] forgets the last account.
 * - **The first pass after an account switch.** The account differs from the last one,
 *   and its markers are forgotten. Without that, the first pass after a week on the other
 *   account would announce a week of books at once.
 * - **A library the user has not opened.** It is still mirrored and its marker still
 *   moves, so the first pass after it is opened does not announce its back catalogue.
 * - **A re-download of the whole library.** Room held nothing for the library before
 *   the pass, or every book in the pass is new. Both are a rebuild, not a delivery.
 *
 * Only books count: a podcast library has the new-episode notification.
 *
 * The marker moves whether the setting is on or off. Switched on later, it starts from
 * today, not from the day the setting was last on.
 */
@Singleton
class NewBookWatch internal constructor(
    private val marks: NewBookMarks,
    private val enabled: suspend () -> Boolean,
    private val announcer: NewBookAnnouncer?,
) {
    @Inject
    constructor(
        marks: DataStoreNewBookMarks,
        downloadPrefs: DownloadPrefs,
        announcer: Optional<NewBookAnnouncer>,
    ) : this(marks, { downloadPrefs.current().notifyNewBooks }, announcer.orElse(null))

    /** Two passes over two libraries must not both decide they are the first one. */
    private val sessionLock = Mutex()

    /** The Library tab shows this library, so it is one the user has opened. */
    suspend fun markOpened(account: ActiveAccount, libraryId: String) {
        marks.markOpened(LibraryKey.of(account, libraryId))
    }

    /** A sign-in happened. The next pass only takes stock. */
    suspend fun startSession() {
        sessionLock.withLock { marks.forgetLastAccount() }
    }

    /**
     * Opens the record of one pass. [libraryHadRows] is whether Room held any item of
     * this library before the pass started.
     */
    suspend fun startPass(account: ActiveAccount, libraryId: String, libraryHadRows: Boolean): NewBookPass {
        val key = LibraryKey.of(account, libraryId)
        val switched = sessionLock.withLock {
            val changed = marks.lastAccount() != key.account
            if (changed) marks.startAccount(key.account)
            changed
        }
        val since = marks.seenUpTo(key)
        val armed = !switched && since != null && libraryHadRows && marks.isOpened(key)
        return NewBookPass(key, since, armed)
    }

    /**
     * Closes a pass that walked the whole library: moves the marker, and tells the
     * announcer about what was new when the pass was armed and the setting is on.
     *
     * Not called for a pass that failed half way. Its marker stays where it was, so the
     * next complete pass still finds the books this one did not reach.
     */
    suspend fun finish(account: ActiveAccount, pass: NewBookPass) {
        val newest = maxOf(pass.since ?: 0L, pass.newestSeen)
        if (pass.since == null || newest > pass.since) marks.setSeenUpTo(pass.key, newest)

        val books = pass.books()
        if (books.isEmpty()) return
        if (!enabled()) return
        announcer?.announce(account, books)
    }
}

/**
 * What one pass has seen so far. The repository feeds it page by page, through [page].
 */
class NewBookPass internal constructor(
    internal val key: LibraryKey,
    internal val since: Long?,
    /** False for every pass that must only take stock. See [NewBookWatch]. */
    val armed: Boolean,
) {
    internal var newestSeen = 0L
        private set
    private var seenCount = 0
    private val arrived = LinkedHashMap<String, NewBook>()

    /** One item of a page. [isBook] is false for a podcast, which never counts. */
    data class Item(val book: NewBook, val isBook: Boolean)

    /**
     * Takes one page, before the page is written to Room.
     *
     * [knownIds] answers which of the given ids Room already holds for the account. It is
     * asked only about the items newer than the marker, which on an ordinary pass is none
     * or a few, so a pass over a large library costs no extra query.
     */
    suspend fun page(items: List<Item>, knownIds: suspend (List<String>) -> Collection<String>) {
        val candidates = items.filter { mayBeNew(it) }.map { it.book }
        if (candidates.isNotEmpty()) {
            val known = knownIds(candidates.map { it.libraryItemId }).toSet()
            candidates.filter { it.libraryItemId !in known }.forEach { arrived[it.libraryItemId] = it }
        }
        for (item in items) {
            seenCount += 1
            if (item.book.addedAtMs > newestSeen) newestSeen = item.book.addedAtMs
        }
    }

    private fun mayBeNew(item: Item): Boolean =
        armed && item.isBook && since != null && item.book.addedAtMs > since

    /**
     * The new books, newest first. Empty when every item of the pass was new, because
     * that is a library downloaded again, not a library that grew.
     */
    fun books(): List<NewBook> {
        if (!armed || arrived.isEmpty()) return emptyList()
        if (arrived.size >= seenCount) return emptyList()
        return arrived.values.sortedByDescending { it.addedAtMs }
    }
}

private val Context.newBookStore: DataStore<Preferences> by preferencesDataStore(name = "lugu_new_books")

/** [NewBookMarks] in its own DataStore file. Nothing here is a setting, so not in a prefs file. */
@Singleton
class DataStoreNewBookMarks @Inject constructor(
    @ApplicationContext context: Context,
) : NewBookMarks {
    private val store = context.newBookStore

    override suspend fun seenUpTo(key: LibraryKey): Long? = store.data.first()[seenKey(key)]

    override suspend fun setSeenUpTo(key: LibraryKey, addedAtMs: Long) {
        store.edit { it[seenKey(key)] = addedAtMs }
    }

    override suspend fun isOpened(key: LibraryKey): Boolean = store.data.first()[openedKey(key)] ?: false

    override suspend fun markOpened(key: LibraryKey) {
        if (isOpened(key)) return
        store.edit { it[openedKey(key)] = true }
    }

    override suspend fun lastAccount(): String? = store.data.first()[LAST_ACCOUNT]

    override suspend fun startAccount(account: String) {
        store.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith("$SEEN_PREFIX$account|") }
                .forEach { prefs.remove(it) }
            prefs[LAST_ACCOUNT] = account
        }
    }

    override suspend fun forgetLastAccount() {
        store.edit { it.remove(LAST_ACCOUNT) }
    }

    private fun seenKey(key: LibraryKey) = longPreferencesKey("$SEEN_PREFIX${key.account}|${key.libraryId}")

    private fun openedKey(key: LibraryKey) = booleanPreferencesKey("opened|${key.account}|${key.libraryId}")

    private companion object {
        const val SEEN_PREFIX = "seen|"
        val LAST_ACCOUNT = stringPreferencesKey("last_account")
    }
}
