package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Which books a library sync calls new, and which passes only take stock.
 *
 * Each pass here walks the same steps the repository's mirror pass walks: count what Room
 * held, start the pass, hand it each page before the page is written, and finish it. Room
 * is a set of ids per account.
 */
class NewBookWatchTest {

    private val marks = InMemoryMarks()
    private val announced = mutableListOf<Pair<ActiveAccount, List<NewBook>>>()
    private var enabled = true
    private val watch = NewBookWatch(
        marks = marks,
        enabled = { enabled },
        announcer = NewBookAnnouncer { account, books -> announced += account to books },
    )

    /** What Room holds, per account key, per library. */
    private val room = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()

    @Test
    fun `the first sync of a library tells nobody`() = runTest {
        watch.markOpened(alice, LIB)
        sync(alice, LIB, listOf(book("b1", 100), book("b2", 200)))

        assertThat(announced).isEmpty()
        assertThat(marks.seenUpTo(LibraryKey.of(alice, LIB))).isEqualTo(200)
    }

    @Test
    fun `a book added after the last sync is announced, with its details`() = runTest {
        settled(alice)

        sync(alice, LIB, shelf + book("b3", 300, title = "The Lantern Ferry", author = "Ilse Varn"))

        assertThat(announced).hasSize(1)
        val (account, books) = announced.single()
        assertThat(account).isEqualTo(alice)
        assertThat(books.map { it.libraryItemId }).containsExactly("b3")
        assertThat(books.single().title).isEqualTo("The Lantern Ferry")
        assertThat(books.single().authorName).isEqualTo("Ilse Varn")
    }

    @Test
    fun `a sync with no new items tells nobody`() = runTest {
        settled(alice)

        sync(alice, LIB, shelf)
        sync(alice, LIB, shelf)

        assertThat(announced).isEmpty()
    }

    @Test
    fun `several new books arrive newest first`() = runTest {
        settled(alice)

        sync(alice, LIB, shelf + book("b3", 300) + book("b5", 500) + book("b4", 400))

        assertThat(announced.single().second.map { it.libraryItemId }).containsExactly("b5", "b4", "b3").inOrder()
    }

    @Test
    fun `a book announced once is not announced again`() = runTest {
        settled(alice)
        sync(alice, LIB, shelf + book("b3", 300))

        sync(alice, LIB, shelf + book("b3", 300))

        assertThat(announced).hasSize(1)
    }

    /**
     * The other account's library moved on while this one was away. The first pass after
     * coming back is a switch, and a week of books in one batch is not news.
     */
    @Test
    fun `an account switch tells nobody, and the pass after it does`() = runTest {
        settled(alice)
        settled(bob)

        sync(alice, LIB, shelf + book("b3", 300))
        assertThat(announced).isEmpty()

        sync(alice, LIB, shelf + book("b3", 300) + book("b4", 400))
        assertThat(announced.single().second.map { it.libraryItemId }).containsExactly("b4")
    }

    @Test
    fun `the first sync after a sign-in tells nobody`() = runTest {
        settled(alice)

        watch.startSession()
        sync(alice, LIB, shelf + book("b3", 300))

        assertThat(announced).isEmpty()
    }

    @Test
    fun `a library the user has not opened tells nobody, but its marker moves`() = runTest {
        sync(alice, OTHER_LIB, shelf)
        sync(alice, OTHER_LIB, shelf + book("b3", 300))

        assertThat(announced).isEmpty()

        // Opened now: its back catalogue is not news, the next book is.
        watch.markOpened(alice, OTHER_LIB)
        sync(alice, OTHER_LIB, shelf + book("b3", 300))
        sync(alice, OTHER_LIB, shelf + book("b3", 300) + book("b4", 400))
        assertThat(announced.single().second.map { it.libraryItemId }).containsExactly("b4")
    }

    @Test
    fun `a library downloaded again into an empty Room tells nobody`() = runTest {
        settled(alice)
        room.getValue(alice.key).getValue(LIB).clear()

        sync(alice, LIB, shelf + book("b3", 300))

        assertThat(announced).isEmpty()
    }

    /** A library rebuilt on the server: new ids for every book, Room still full of the old ones. */
    @Test
    fun `a pass where every book is new tells nobody`() = runTest {
        settled(alice)

        sync(alice, LIB, listOf(book("n1", 300), book("n2", 301)))

        assertThat(announced).isEmpty()
    }

    @Test
    fun `a book already in Room is not new, even with a later date`() = runTest {
        settled(alice)
        // Fetched by the live socket, for example, before the full pass ran.
        room.getValue(alice.key).getValue(LIB) += "b3"

        sync(alice, LIB, shelf + book("b3", 300))

        assertThat(announced).isEmpty()
    }

    @Test
    fun `a podcast is not a book`() = runTest {
        settled(alice)

        sync(alice, LIB, shelf + book("p1", 300), podcasts = setOf("p1"))

        assertThat(announced).isEmpty()
    }

    @Test
    fun `with the setting off nothing is announced, and the marker still moves`() = runTest {
        settled(alice)
        enabled = false

        sync(alice, LIB, shelf + book("b3", 300))
        assertThat(announced).isEmpty()

        // Switched on later, it starts from today rather than replaying the past.
        enabled = true
        sync(alice, LIB, shelf + book("b3", 300))
        assertThat(announced).isEmpty()
    }

    @Test
    fun `the setting is off until somebody switches it on`() {
        assertThat(DownloadSettings().notifyNewBooks).isFalse()
    }

    @Test
    fun `a pass that fails half way leaves the marker where it was`() = runTest {
        settled(alice)

        val pass = watch.startPass(alice, LIB, libraryHadRows = true)
        pass.page(listOf(item(book("b3", 300)))) { emptyList() }
        // No finish: the server dropped the connection on the next page.

        assertThat(marks.seenUpTo(LibraryKey.of(alice, LIB))).isEqualTo(200)
        sync(alice, LIB, shelf + book("b3", 300))
        assertThat(announced.single().second.map { it.libraryItemId }).containsExactly("b3")
    }

    // --- The mirror pass, as `LibraryRepository` runs it -------------------------------

    private val shelf = listOf(book("b1", 100), book("b2", 200))

    /** Opened, synced once, so the next pass is an ordinary one. */
    private suspend fun settled(account: ActiveAccount) {
        watch.markOpened(account, LIB)
        sync(account, LIB, shelf)
        assertThat(announced).isEmpty()
    }

    private suspend fun sync(
        account: ActiveAccount,
        libraryId: String,
        books: List<NewBook>,
        podcasts: Set<String> = emptySet(),
    ) {
        val held = room.getOrPut(account.key) { mutableMapOf() }.getOrPut(libraryId) { mutableSetOf() }
        val pass = watch.startPass(account, libraryId, libraryHadRows = held.isNotEmpty())
        books.chunked(2).forEach { page ->
            pass.page(page.map { NewBookPass.Item(it, isBook = it.libraryItemId !in podcasts) }) { ids ->
                val all = room.getValue(account.key).values.flatten().toSet()
                ids.filter { it in all }
            }
            held += page.map { it.libraryItemId }
        }
        held.retainAll(books.map { it.libraryItemId }.toSet())
        watch.finish(account, pass)
    }

    private fun item(book: NewBook) = NewBookPass.Item(book, isBook = true)

    private fun book(id: String, addedAt: Long, title: String = "Book $id", author: String? = "A. Writer") = NewBook(
        libraryItemId = id,
        libraryId = LIB,
        title = title,
        authorName = author,
        description = null,
        addedAtMs = addedAt,
    )

    private val ActiveAccount.key get() = accountKeyOf(serverId, userId)

    private val alice = ActiveAccount(
        serverId = "srv_a",
        baseUrl = "https://abs.example.invalid",
        userId = "usr_a",
        username = "listener",
        defaultLibraryId = null,
    )

    private val bob = alice.copy(serverId = "srv_b", userId = "usr_b", username = "other")

    private class InMemoryMarks : NewBookMarks {
        private val seen = mutableMapOf<LibraryKey, Long>()
        private val opened = mutableSetOf<LibraryKey>()
        private var last: String? = null

        override suspend fun seenUpTo(key: LibraryKey): Long? = seen[key]

        override suspend fun setSeenUpTo(key: LibraryKey, addedAtMs: Long) {
            seen[key] = addedAtMs
        }

        override suspend fun isOpened(key: LibraryKey): Boolean = key in opened

        override suspend fun markOpened(key: LibraryKey) {
            opened += key
        }

        override suspend fun lastAccount(): String? = last

        override suspend fun startAccount(account: String) {
            seen.keys.removeAll { it.account == account }
            last = account
        }

        override suspend fun forgetLastAccount() {
            last = null
        }
    }

    private companion object {
        const val LIB = "lib_books"
        const val OTHER_LIB = "lib_other"
    }
}
