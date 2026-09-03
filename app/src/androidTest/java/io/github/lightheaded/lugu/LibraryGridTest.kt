package io.github.lightheaded.lugu

import android.content.Context
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.LibraryEntity
import io.github.lightheaded.lugu.core.db.LibraryItemEntity
import io.github.lightheaded.lugu.core.db.LibraryItemFtsEntity
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.db.ProgressEntity
import io.github.lightheaded.lugu.core.db.ServerEntity
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a cover tile says about how far through its item you are.
 *
 * Seeded straight into Room and never signed in, for the same reasons as
 * [AutoBrowseTreeTest]: the account row points at `books.example`, which cannot resolve, so
 * nothing on any of these tiles can have arrived from a server. That also means these run
 * on a bare CI emulator with nothing seeded on the host.
 *
 * ## The bug this exists for
 *
 * A podcast has no progress row at the item level — progress is stored per episode — and
 * the grid looked progress up by item id alone. So every podcast cover showed no progress
 * at all, however many hours of it had been listened to, and the "In progress" filter could
 * not see one either. Reported by Tom from daily driving, twice.
 *
 * The tile is asserted through its **spoken description** rather than by looking for three
 * device-independent pixels of colour. That is not a workaround: the bar carried no
 * description before this, so a screen reader was told nothing about it, and giving it one
 * is both the accessibility fix and the only honest handle a test has on it.
 *
 * The method names here are underscored rather than the backticked sentences the JVM suites
 * use. A name with spaces in it needs DEX version 040, which needs minSdk 30; lugu's minSdk
 * is 26, so the test APK will not dex with them.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LibraryGridTest {

    @get:Rule(order = 0)
    val notifications = grantNotificationPermission()

    /**
     * Empty on purpose: the activity is launched by hand, *after* Room has been seeded.
     *
     * `createAndroidComposeRule` launches on the way in to `@Before`, and a grid composed
     * before its rows exist reads the empty database and then has nothing to bring it back
     * — the first sync it starts goes to an address that cannot resolve.
     */
    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: LuguDatabase

    /** Whatever account the device was signed in to, put back in [restoreTheDevice]. */
    private var displacedServer: ServerEntity? = null

    private var scenario: ActivityScenario<MainActivity>? = null

    private lateinit var token: PlantedToken

    /** The listener's own library choice and grid filter, put back in [restoreTheDevice]. */
    private var displacedLibraryId: String? = null
    private var displacedFilter: ListFilter = ListFilter.ALL

    @Before
    fun seedAndSignOut() {
        db = LuguDatabase.build(context)
        token = PlantedToken.plant(context, db.serverDao(), TEST_SERVER_ID)
        runBlocking {
            displacedServer = db.serverDao().active()
            db.serverDao().clearActive()
            // The picked library outlives a test, so a run where the books chip was tapped
            // would otherwise decide which library the next test opens on — and on a real
            // phone it would decide which library its owner opens on.
            val prefs = LibraryPrefs(context)
            prefs.current().let {
                displacedLibraryId = it.selectedLibraryId
                displacedFilter = it.itemFilter
            }
            prefs.setSelectedLibraryId(null)
            prefs.setItemFilter(ListFilter.ALL)
        }
        wipeTestRows()
    }

    @After
    fun restoreTheDevice() {
        scenario?.close()
        scenario = null
        runBlocking {
            runCatching {
                wipeTestRows()
                displacedServer?.let { db.serverDao().setActive(it) }
                LibraryPrefs(context).setSelectedLibraryId(displacedLibraryId)
                LibraryPrefs(context).setItemFilter(displacedFilter)
            }
        }
        runCatching { token.restore() }
        db.close()
    }

    /**
     * The bug, in one assertion: a part-heard podcast draws the position of the episode
     * that would be resumed, and says which episode it is talking about.
     */
    @Test
    fun a_podcast_tile_shows_the_progress_of_its_latest_episode() {
        seedEverything()
        launchOnTheLibraryTab()

        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(PODCAST_TITLE)) }
        assertThat(nodeExists(hasContentDescription("Latest episode 62% listened"))).isTrue()
    }

    /**
     * And a book's says nothing about episodes, because it has none.
     *
     * Reached through the library picker, which is the other thing being asserted here:
     * more than one library shows as chips, and tapping one scopes the grid to it. That was
     * a manual check and is now not one.
     */
    @Test
    fun switching_library_scopes_the_grid_and_a_book_names_its_own_position() {
        seedEverything()
        launchOnTheLibraryTab()

        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(PODCAST_TITLE)) }
        // The podcasts library is the account's default, so the book is not on screen yet.
        assertThat(nodeExists(hasText(BOOK_TITLE))).isFalse()

        compose.onNodeWithText(BOOKS_LIBRARY_NAME).performClick()

        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(BOOK_TITLE)) }
        assertThat(nodeExists(hasContentDescription("40% listened"))).isTrue()
        assertThat(nodeExists(hasText(PODCAST_TITLE))).isFalse()
    }

    /**
     * "In progress" is a question about listening, and a podcast is being listened to.
     *
     * The same lookup that left the bar blank also left the filter blind: with no
     * item-level row, a part-heard podcast counted as not started, so the one filter that
     * exists to find what you are in the middle of hid it.
     */
    @Test
    fun the_in_progress_filter_finds_a_part_heard_podcast() {
        seedEverything()
        launchOnTheLibraryTab()

        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(PODCAST_TITLE)) }
        compose.onNodeWithText("In progress").performClick()

        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(PODCAST_TITLE)) }
    }

    // -- Seeding -------------------------------------------------------------------------

    private fun launchOnTheLibraryTab() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(LIBRARY_TAB)) }
        compose.onNodeWithText(LIBRARY_TAB).performClick()
    }

    private fun nodeExists(matcher: androidx.compose.ui.test.SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun seedEverything() {
        signIn()
        runBlocking {
            db.libraryDao().upsertAll(
                listOf(
                    LibraryEntity(TEST_SERVER_ID, TEST_USER_ID, BOOKS_LIBRARY_ID, BOOKS_LIBRARY_NAME, "book", 0),
                    LibraryEntity(
                        TEST_SERVER_ID,
                        TEST_USER_ID,
                        PODCASTS_LIBRARY_ID,
                        PODCASTS_LIBRARY_NAME,
                        "podcast",
                        1,
                    ),
                ),
            )
            db.libraryItemDao().upsertAll(listOf(bookItem(), podcastItem()))
            db.libraryItemFtsDao().insertAll(
                listOf(fts(BOOK_ID, BOOKS_LIBRARY_ID, BOOK_TITLE), fts(PODCAST_ID, PODCASTS_LIBRARY_ID, PODCAST_TITLE)),
            )
            db.progressDao().upsertAll(
                listOf(
                    // The book's own row: 40% of it heard.
                    progress(BOOK_ID, episodeKey = "", fraction = 0.4),
                    // The podcast has no row of its own — only this episode's, at 62%.
                    progress(PODCAST_ID, episodeKey = EPISODE_ID, fraction = 0.62),
                ),
            )
        }
    }

    private fun signIn() = runBlocking {
        db.serverDao().setActive(
            ServerEntity(
                serverId = TEST_SERVER_ID,
                baseUrl = TEST_SERVER_URL,
                userId = TEST_USER_ID,
                username = "tester",
                // The podcasts library opens first, so the podcast tile is the one on
                // screen without anything being tapped.
                defaultLibraryId = PODCASTS_LIBRARY_ID,
                serverVersion = null,
                isActive = true,
            ),
        )
    }

    private fun bookItem() = item(BOOK_ID, BOOK_TITLE, BOOKS_LIBRARY_ID, "BOOK")

    private fun podcastItem() = item(PODCAST_ID, PODCAST_TITLE, PODCASTS_LIBRARY_ID, "PODCAST").copy(numEpisodes = 3)

    private fun item(id: String, title: String, libraryId: String, mediaType: String) = LibraryItemEntity(
        serverId = TEST_SERVER_ID,
        userId = TEST_USER_ID,
        id = id,
        libraryId = libraryId,
        mediaType = mediaType,
        title = title,
        subtitle = null,
        authorName = AUTHOR,
        narratorName = null,
        seriesName = null,
        seriesTitle = null,
        seriesSequence = null,
        description = null,
        durationSec = 3_600.0,
        sizeBytes = 1_000L,
        numEpisodes = 0,
        addedAtMs = 1_000L,
        updatedAtMs = 1_000L,
        coverPath = null,
        rawJson = null,
        syncedAtMs = 1_000L,
    )

    private fun progress(itemId: String, episodeKey: String, fraction: Double) = ProgressEntity(
        serverId = TEST_SERVER_ID,
        userId = TEST_USER_ID,
        libraryItemId = itemId,
        episodeKey = episodeKey,
        currentTimeSec = 3_600.0 * fraction,
        durationSec = 3_600.0,
        progress = fraction,
        isFinished = false,
        lastUpdateMs = 2_000L,
        startedAtMs = 1_000L,
        serverLastUpdateMs = 2_000L,
        isDirty = false,
    )

    private fun fts(itemId: String, libraryId: String, text: String) = LibraryItemFtsEntity(
        serverId = TEST_SERVER_ID,
        userId = TEST_USER_ID,
        itemId = itemId,
        libraryId = libraryId,
        text = text,
    )

    /** Removes everything this class writes, by `serverId`, and nothing else. */
    private fun wipeTestRows() {
        val database = db.openHelper.writableDatabase
        SEEDED_TABLES.forEach { table ->
            database.execSQL("DELETE FROM `$table` WHERE serverId = ?", arrayOf<Any>(TEST_SERVER_ID))
        }
    }

    private companion object {
        /** Reserved, so it cannot resolve. See RFC 2606. */
        const val TEST_SERVER_URL = "https://books.example"
        const val TEST_USER_ID = "library-grid-test-user"
        const val TEST_SERVER_ID = "$TEST_SERVER_URL#$TEST_USER_ID"

        const val BOOKS_LIBRARY_ID = "library-grid-test-books"
        const val PODCASTS_LIBRARY_ID = "library-grid-test-podcasts"
        const val BOOKS_LIBRARY_NAME = "Grid test books"
        const val PODCASTS_LIBRARY_NAME = "Grid test podcasts"

        const val BOOK_ID = "library-grid-test-book"
        const val PODCAST_ID = "library-grid-test-podcast"
        const val EPISODE_ID = "library-grid-test-episode"

        const val BOOK_TITLE = "Lighthouse Falls"
        const val PODCAST_TITLE = "Riverton"
        const val AUTHOR = "Marisol Fen"

        const val LIBRARY_TAB = "Library"
        const val UI_TIMEOUT_MS = 30_000L

        val SEEDED_TABLES = listOf(
            "server",
            "library",
            "library_item",
            "progress",
            "library_item_fts",
        )
    }
}
