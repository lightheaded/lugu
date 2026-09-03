package io.github.lightheaded.lugu

import android.content.Context
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.DownloadEntity
import io.github.lightheaded.lugu.core.db.DownloadState
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.db.ServerEntity
import io.github.lightheaded.lugu.core.model.ItemSort
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An ordering somebody chose is a decision, and it has to survive leaving the screen.
 *
 * Reported by Tom from daily driving: the downloads screen forgot its sort and its filter
 * every time it was opened. They were held in a `MutableStateFlow` in the view model, which
 * is destroyed with the screen — deliberately, at the time, to avoid tying this screen's
 * ordering to the library grid's. The answer was two keys of its own rather than no keys.
 *
 * Driven through the screen rather than through the store, because the store was never the
 * broken part: a `DataStore` round trip would have passed on the day the bug was reported.
 * What has to be true is that the chip reads the same thing after going away and coming
 * back, which is what somebody complained about.
 *
 * No server: two download rows are seeded straight into Room under a `serverId` no real
 * account can have.
 *
 * The method names here are underscored rather than the backticked sentences the JVM suites
 * use. A name with spaces in it needs DEX version 040, which needs minSdk 30; lugu's minSdk
 * is 26, so the test APK will not dex with them.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RememberedListControlsTest {

    @get:Rule(order = 0)
    val notifications = grantNotificationPermission()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: LuguDatabase

    private var displacedServer: ServerEntity? = null

    private var scenario: ActivityScenario<MainActivity>? = null

    private lateinit var token: PlantedToken

    /** Every preference this class writes, as the device had it. */
    private var displacedPrefs: Triple<ItemSort, ListFilter, ItemSort>? = null

    @Before
    fun seedTwoDownloads() {
        db = LuguDatabase.build(context)
        token = PlantedToken.plant(context, db.serverDao(), TEST_SERVER_ID)
        runBlocking {
            displacedServer = db.serverDao().active()
            db.serverDao().clearActive()
            // Whatever an earlier test or an earlier session left, so this starts from the
            // declared default rather than from somebody else's choice — and remembered,
            // so a phone that ran these does not find its own choices rewritten.
            prefs().current().let {
                displacedPrefs = Triple(it.downloadSort, it.downloadFilter, it.itemSort)
            }
            prefs().setDownloadSort(ItemSort.ADDED)
            prefs().setDownloadFilter(ListFilter.ALL)
        }
        wipeTestRows()
        signInAndSeed()
    }

    @After
    fun restoreTheDevice() {
        scenario?.close()
        scenario = null
        runBlocking {
            runCatching {
                wipeTestRows()
                displacedServer?.let { db.serverDao().setActive(it) }
                displacedPrefs?.let { (sort, filter, itemSort) ->
                    prefs().setDownloadSort(sort)
                    prefs().setDownloadFilter(filter)
                    prefs().setItemSort(itemSort)
                }
            }
        }
        runCatching { token.restore() }
        db.close()
    }

    @Test
    fun the_downloads_sort_survives_leaving_the_screen() {
        openDownloads()

        // The declared default, so the chip is a real readout rather than a coincidence.
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(RECENTLY_ADDED)) }

        compose.onNodeWithText(RECENTLY_ADDED).performClick()
        compose.onNodeWithText(BY_TITLE).performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(BY_TITLE)) }

        leaveAndReopenDownloads()

        assertThat(nodeExists(hasText(BY_TITLE))).isTrue()
        assertThat(nodeExists(hasText(RECENTLY_ADDED))).isFalse()
    }

    @Test
    fun the_downloads_filter_survives_leaving_the_screen() {
        openDownloads()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(FIRST_TITLE)) }

        // "Downloaded" keeps both rows and is the one filter this screen offers that can be
        // told apart from "All" by asking the store rather than by counting rows.
        compose.onNodeWithText(DOWNLOADED_FILTER).performClick()
        compose.waitUntil(UI_TIMEOUT_MS) {
            runBlocking { prefs().current().downloadFilter } == ListFilter.DOWNLOADED
        }

        leaveAndReopenDownloads()

        assertThat(runBlocking { prefs().current().downloadFilter }).isEqualTo(ListFilter.DOWNLOADED)
        assertThat(nodeExists(hasText(FIRST_TITLE))).isTrue()
    }

    /**
     * The library grid keeps its own ordering, which is why this screen got keys of its own.
     *
     * Without this, "remember the downloads sort" has an obvious wrong implementation —
     * reuse `itemSort` — that passes every other test in this class and re-orders somebody's
     * library because they visited Downloads.
     */
    @Test
    fun the_downloads_sort_is_not_the_library_grids_sort() {
        runBlocking { prefs().setItemSort(ItemSort.TITLE) }

        openDownloads()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(RECENTLY_ADDED)) }
        compose.onNodeWithText(RECENTLY_ADDED).performClick()
        compose.onNodeWithText(LARGEST_FIRST).performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(LARGEST_FIRST)) }

        assertThat(runBlocking { prefs().current().itemSort }).isEqualTo(ItemSort.TITLE)
    }

    // -- Getting there -------------------------------------------------------------------

    private fun openDownloads() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasContentDescription(DOWNLOADS_BUTTON)) }
        compose.onNodeWithContentDescription(DOWNLOADS_BUTTON).performClick()
    }

    /** Back to Home and in again — the trip that used to reset the controls. */
    private fun leaveAndReopenDownloads() {
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasContentDescription(DOWNLOADS_BUTTON)) }
        compose.onNodeWithContentDescription(DOWNLOADS_BUTTON).performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(FIRST_TITLE)) }
    }

    private fun nodeExists(matcher: androidx.compose.ui.test.SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun prefs() = LibraryPrefs(context)

    private fun signInAndSeed() = runBlocking {
        db.serverDao().setActive(
            ServerEntity(
                serverId = TEST_SERVER_ID,
                baseUrl = TEST_SERVER_URL,
                userId = TEST_USER_ID,
                username = "tester",
                defaultLibraryId = null,
                serverVersion = null,
                isActive = true,
            ),
        )
        // Titles and sizes chosen so that every ordering this screen offers produces a
        // different first row.
        db.downloadDao().upsert(download(FIRST_ID, FIRST_TITLE, bytes = 900L, requestedAtMs = 2_000L))
        db.downloadDao().upsert(download(SECOND_ID, SECOND_TITLE, bytes = 9_000L, requestedAtMs = 1_000L))
    }

    private fun download(id: String, title: String, bytes: Long, requestedAtMs: Long) = DownloadEntity(
        serverId = TEST_SERVER_ID,
        userId = TEST_USER_ID,
        libraryItemId = id,
        episodeKey = "",
        title = title,
        author = AUTHOR,
        mediaType = "BOOK",
        state = DownloadState.COMPLETED,
        tracksJson = "[]",
        durationSec = 3_600.0,
        bytesTotal = bytes,
        bytesDownloaded = bytes,
        percent = 100f,
        requestedAtMs = requestedAtMs,
        completedAtMs = requestedAtMs + 1,
        error = null,
    )

    private fun wipeTestRows() {
        val database = db.openHelper.writableDatabase
        listOf("server", "download").forEach { table ->
            database.execSQL("DELETE FROM `$table` WHERE serverId = ?", arrayOf<Any>(TEST_SERVER_ID))
        }
    }

    private companion object {
        const val TEST_SERVER_URL = "https://books.example"
        const val TEST_USER_ID = "downloads-controls-test-user"
        const val TEST_SERVER_ID = "$TEST_SERVER_URL#$TEST_USER_ID"

        const val FIRST_ID = "downloads-controls-test-a"
        const val SECOND_ID = "downloads-controls-test-b"
        const val FIRST_TITLE = "Riverton"
        const val SECOND_TITLE = "Lighthouse Falls"
        const val AUTHOR = "Marisol Fen"

        const val DOWNLOADS_BUTTON = "Downloads"
        const val RECENTLY_ADDED = "Recently added"
        const val BY_TITLE = "Title"
        const val LARGEST_FIRST = "Largest first"
        const val DOWNLOADED_FILTER = "Downloaded"

        const val UI_TIMEOUT_MS = 30_000L
    }
}
