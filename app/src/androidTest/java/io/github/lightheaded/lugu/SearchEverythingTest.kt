package io.github.lightheaded.lugu

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.EpisodeEntity
import io.github.lightheaded.lugu.core.db.LibraryEntity
import io.github.lightheaded.lugu.core.db.LibraryItemEntity
import io.github.lightheaded.lugu.core.db.LibraryItemFtsEntity
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.db.ServerEntity
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The search on Home, from the button in the top bar to the page a result opens.
 *
 * Seeded straight into Room and never signed in, the same as [LibraryGridTest]: the account
 * points at `books.example`, which cannot resolve, so every result on screen came from the
 * mirror. The episode pass that the search screen starts fails against that address, which
 * is also what a phone with no connection does, and the search must still answer.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SearchEverythingTest {

    @get:Rule(order = 0)
    val notifications = grantNotificationPermission()

    /** Empty on purpose: the activity is launched by hand, after Room has been seeded. */
    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: LuguDatabase
    private lateinit var token: PlantedToken
    private var displacedServer: ServerEntity? = null
    private var displacedHidden: Set<MediaType> = emptySet()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun seed() {
        db = LuguDatabase.build(context)
        token = PlantedToken.plant(context, db.serverDao(), TEST_SERVER_ID)
        runBlocking {
            displacedServer = db.serverDao().active()
            db.serverDao().clearActive()
            displacedHidden = LibraryPrefs(context).current().hiddenMediaTypes
            MediaType.entries.forEach { LibraryPrefs(context).setMediaTypeHidden(it, false) }
        }
        wipeTestRows()
        seedEverything()
    }

    @After
    fun restoreTheDevice() {
        scenario?.close()
        scenario = null
        runBlocking {
            runCatching {
                wipeTestRows()
                displacedServer?.let { db.serverDao().setActive(it) }
                val prefs = LibraryPrefs(context)
                MediaType.entries.forEach { prefs.setMediaTypeHidden(it, it in displacedHidden) }
            }
        }
        runCatching { token.restore() }
        db.close()
    }

    /** One word finds a book by its title, a podcast episode by its title and a narrator. */
    @Test
    fun one_search_finds_books_episodes_and_narrators_together() {
        openSearch()
        type("harbour")

        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(BOOK_TITLE)) }
        assertThat(nodeExists(hasText(EPISODE_TITLE))).isTrue()
        assertThat(nodeExists(hasText(NARRATOR))).isTrue()
        assertThat(nodeExists(hasText("Books · 1"))).isTrue()
        assertThat(nodeExists(hasText("Episodes · 1"))).isTrue()
        assertThat(nodeExists(hasText("Narrators · 1"))).isTrue()
    }

    /** An episode result opens that episode's page, not the podcast. */
    @Test
    fun an_episode_result_opens_the_episode() {
        openSearch()
        type("harbour")

        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(EPISODE_TITLE)) }
        compose.onNodeWithText(EPISODE_TITLE).performClick()

        compose.waitUntil(UI_TIMEOUT_MS) { !nodeExists(hasText("Episodes · 1")) }
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(EPISODE_TITLE)) }
    }

    /** A narrator result opens the narrator's page, which lists the book they read. */
    @Test
    fun a_narrator_result_opens_their_page() {
        openSearch()
        type("harbour")

        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(NARRATOR)) }
        compose.onNodeWithText(NARRATOR).performClick()

        compose.waitUntil(UI_TIMEOUT_MS) { !nodeExists(hasText("Narrators · 1")) }
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(BOOK_TITLE)) }
    }

    /** A library type that the settings hide stays hidden from the search as well. */
    @Test
    fun a_hidden_library_type_is_not_searched() {
        runBlocking { LibraryPrefs(context).setMediaTypeHidden(MediaType.PODCAST, true) }
        openSearch()
        type("beacon")

        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText("Nothing matches “beacon”.")) }
        assertThat(nodeExists(hasText(PODCAST_EPISODE_ONLY_TITLE))).isFalse()
    }

    // -- Driving -------------------------------------------------------------------------

    private fun openSearch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasContentDescription("Search")) }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasSetTextAction()) }
    }

    private fun type(text: String) {
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(text)
    }

    private fun nodeExists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    // -- Seeding -------------------------------------------------------------------------

    private fun seedEverything() = runBlocking {
        db.serverDao().setActive(
            ServerEntity(
                serverId = TEST_SERVER_ID,
                baseUrl = TEST_SERVER_URL,
                userId = TEST_USER_ID,
                username = "tester",
                defaultLibraryId = BOOKS_LIBRARY_ID,
                serverVersion = null,
                isActive = true,
            ),
        )
        db.libraryDao().upsertAll(
            listOf(
                LibraryEntity(TEST_SERVER_ID, TEST_USER_ID, BOOKS_LIBRARY_ID, "Search test books", "book", 0),
                LibraryEntity(TEST_SERVER_ID, TEST_USER_ID, PODCASTS_LIBRARY_ID, "Search test podcasts", "podcast", 1),
            ),
        )
        db.libraryItemDao().upsertAll(
            listOf(
                item(BOOK_ID, BOOK_TITLE, BOOKS_LIBRARY_ID, "BOOK", narrator = NARRATOR),
                item(PODCAST_ID, PODCAST_TITLE, PODCASTS_LIBRARY_ID, "PODCAST").copy(numEpisodes = 2),
            ),
        )
        db.libraryItemFtsDao().insertAll(
            listOf(
                fts(BOOK_ID, BOOKS_LIBRARY_ID, "$BOOK_TITLE $NARRATOR"),
                fts(PODCAST_ID, PODCASTS_LIBRARY_ID, PODCAST_TITLE),
            ),
        )
        db.episodeDao().upsertAll(
            listOf(
                episode(EPISODE_ID, EPISODE_TITLE, publishedAtMs = 2_000L),
                episode("$EPISODE_ID-2", PODCAST_EPISODE_ONLY_TITLE, publishedAtMs = 1_000L),
            ),
        )
    }

    private fun item(id: String, title: String, libraryId: String, mediaType: String, narrator: String? = null) =
        LibraryItemEntity(
            serverId = TEST_SERVER_ID,
            userId = TEST_USER_ID,
            id = id,
            libraryId = libraryId,
            mediaType = mediaType,
            title = title,
            subtitle = null,
            authorName = AUTHOR,
            narratorName = narrator,
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

    private fun episode(id: String, title: String, publishedAtMs: Long) = EpisodeEntity(
        serverId = TEST_SERVER_ID,
        userId = TEST_USER_ID,
        id = id,
        libraryItemId = PODCAST_ID,
        title = title,
        subtitle = null,
        description = null,
        episodeNumber = null,
        season = null,
        publishedAtMs = publishedAtMs,
        durationSec = 1_800.0,
        position = 0,
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
        const val TEST_USER_ID = "search-test-user"
        const val TEST_SERVER_ID = "$TEST_SERVER_URL#$TEST_USER_ID"

        const val BOOKS_LIBRARY_ID = "search-test-books"
        const val PODCASTS_LIBRARY_ID = "search-test-podcasts"

        const val BOOK_ID = "search-test-book"
        const val PODCAST_ID = "search-test-podcast"
        const val EPISODE_ID = "search-test-episode"

        // "harbour" is in the book's title, the episode's title and the narrator's name, and
        // in nothing else, so one word reaches three sections.
        const val BOOK_TITLE = "The Harbourmaster's Daughter"
        const val NARRATOR = "Wren Harbourne"
        const val PODCAST_TITLE = "Tidewater Hours"
        const val EPISODE_TITLE = "Fog over the harbour"
        const val PODCAST_EPISODE_ONLY_TITLE = "The beacon keeper"
        const val AUTHOR = "Marisol Fen"

        const val UI_TIMEOUT_MS = 30_000L

        val SEEDED_TABLES = listOf("server", "library", "library_item", "library_item_fts", "episode")
    }
}
