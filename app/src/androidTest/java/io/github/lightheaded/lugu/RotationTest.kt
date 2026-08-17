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
import io.github.lightheaded.lugu.core.db.LibraryEntity
import io.github.lightheaded.lugu.core.db.LibraryItemEntity
import io.github.lightheaded.lugu.core.db.LibraryItemFtsEntity
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.db.ServerEntity
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "No crash on rotation on any screen" — the M0 checklist line, done by machine.
 *
 * A configuration change destroys and rebuilds the activity, which is where saved state
 * that cannot be restored, an argument that is not parcelable, or a view model rebuilt with
 * a null it did not expect all show up. It is the one manual check that is pure tedium —
 * eight screens, rotate, look — and is therefore the one most likely to be skipped.
 *
 * `recreate()` rather than a real orientation change. It puts the activity through exactly
 * the destroy-and-rebuild that rotating does, and it does not depend on the device allowing
 * landscape or on an animation settling. What it does not cover is a *layout* that breaks in
 * landscape, which is a thing to look at rather than a thing to assert.
 *
 * Each screen is asserted to still be the screen afterwards, not merely for the process to
 * be alive: a NavHost that quietly restarts at Home after a rebuild is a bug that survives
 * "it did not crash".
 *
 * The method names here are underscored rather than the backticked sentences the JVM suites
 * use. A name with spaces in it needs DEX version 040, which needs minSdk 30; lugu's minSdk
 * is 26, so the test APK will not dex with them.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RotationTest {

    @get:Rule(order = 0)
    val notifications = grantNotificationPermission()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: LuguDatabase
    private lateinit var token: PlantedToken
    private var displacedServer: ServerEntity? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun seedALibrary() {
        db = LuguDatabase.build(context)
        token = PlantedToken.plant(context)
        runBlocking {
            displacedServer = db.serverDao().active()
            db.serverDao().clearActive()
            LibraryPrefs(context).setSelectedLibraryId(null)
        }
        wipeTestRows()
        seed()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasContentDescription(SETTINGS)) }
    }

    @After
    fun restoreTheDevice() {
        scenario?.close()
        scenario = null
        runBlocking {
            runCatching {
                wipeTestRows()
                displacedServer?.let { db.serverDao().setActive(it) }
            }
        }
        runCatching { token.restore() }
        db.close()
    }

    @Test
    fun home_survives_being_rebuilt() = rebuildAndExpect(hasContentDescription(SETTINGS))

    @Test
    fun the_library_tab_survives_being_rebuilt() {
        compose.onNodeWithText(LIBRARY_TAB).performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(BOOK_TITLE)) }

        rebuildAndExpect(hasText(BOOK_TITLE))
    }

    @Test
    fun downloads_survives_being_rebuilt() {
        compose.onNodeWithContentDescription("Downloads").performClick()

        rebuildAndExpect(hasText("Downloads"))
    }

    @Test
    fun the_queue_survives_being_rebuilt() {
        compose.onNodeWithContentDescription("Up next").performClick()

        rebuildAndExpect(hasText("Up next"))
    }

    @Test
    fun settings_survives_being_rebuilt() {
        compose.onNodeWithContentDescription(SETTINGS).performClick()

        rebuildAndExpect(hasText("Settings"))
    }

    @Test
    fun the_browse_lists_survive_being_rebuilt() {
        openLibraryTab()
        compose.onNodeWithText("Narrators").performClick()

        rebuildAndExpect(hasText("Narrators"))
    }

    @Test
    fun collections_survives_being_rebuilt() {
        openLibraryTab()
        compose.onNodeWithText("Collections").performClick()

        rebuildAndExpect(hasText("Collections"))
    }

    /**
     * The item page is the one with a real argument in it, which is what makes it the most
     * interesting screen here: its id has to come back out of saved state.
     */
    @Test
    fun an_item_page_survives_being_rebuilt() {
        openLibraryTab()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(BOOK_TITLE)) }
        compose.onNodeWithText(BOOK_TITLE).performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(AUTHOR)) }

        rebuildAndExpect(hasText(AUTHOR))
    }

    // -- Machinery -----------------------------------------------------------------------

    private fun openLibraryTab() {
        compose.onNodeWithText(LIBRARY_TAB).performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText("Collections")) }
    }

    private fun rebuildAndExpect(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(matcher) }
        scenario?.recreate()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(matcher) }
    }

    private fun nodeExists(matcher: androidx.compose.ui.test.SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun seed() = runBlocking {
        db.serverDao().setActive(
            ServerEntity(
                serverId = TEST_SERVER_ID,
                baseUrl = TEST_SERVER_URL,
                userId = TEST_USER_ID,
                username = "tester",
                defaultLibraryId = LIBRARY_ID,
                serverVersion = null,
                isActive = true,
            ),
        )
        db.libraryDao().upsertAll(
            listOf(LibraryEntity(TEST_SERVER_ID, TEST_USER_ID, LIBRARY_ID, "Rotation test books", "book", 0)),
        )
        db.libraryItemDao().upsertAll(listOf(book()))
        db.libraryItemFtsDao().insertAll(
            listOf(
                LibraryItemFtsEntity(
                    serverId = TEST_SERVER_ID,
                    userId = TEST_USER_ID,
                    itemId = BOOK_ID,
                    libraryId = LIBRARY_ID,
                    text = "$BOOK_TITLE $AUTHOR",
                ),
            ),
        )
    }

    private fun book() = LibraryItemEntity(
        serverId = TEST_SERVER_ID,
        userId = TEST_USER_ID,
        id = BOOK_ID,
        libraryId = LIBRARY_ID,
        mediaType = "BOOK",
        title = BOOK_TITLE,
        subtitle = null,
        authorName = AUTHOR,
        narratorName = NARRATOR,
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

    private fun wipeTestRows() {
        val database = db.openHelper.writableDatabase
        listOf("server", "library", "library_item", "library_item_fts").forEach { table ->
            database.execSQL("DELETE FROM `$table` WHERE serverId = ?", arrayOf<Any>(TEST_SERVER_ID))
        }
    }

    private companion object {
        const val TEST_SERVER_URL = "https://books.example"
        const val TEST_USER_ID = "rotation-test-user"
        const val TEST_SERVER_ID = "$TEST_SERVER_URL#$TEST_USER_ID"
        const val LIBRARY_ID = "rotation-test-library"
        const val BOOK_ID = "rotation-test-book"
        const val BOOK_TITLE = "Lighthouse Wakes"
        const val AUTHOR = "James T. R. Corven"
        const val NARRATOR = "Jefferson Vale"

        const val LIBRARY_TAB = "Library"
        const val SETTINGS = "Settings"
        const val UI_TIMEOUT_MS = 30_000L
    }
}
