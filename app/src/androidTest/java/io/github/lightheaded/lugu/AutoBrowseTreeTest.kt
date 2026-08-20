package io.github.lightheaded.lugu

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.common.util.concurrent.ListenableFuture
import io.github.lightheaded.lugu.core.db.DownloadEntity
import io.github.lightheaded.lugu.core.db.DownloadState
import io.github.lightheaded.lugu.core.db.EpisodeEntity
import io.github.lightheaded.lugu.core.db.ItemSeriesEntity
import io.github.lightheaded.lugu.core.db.LibraryEntity
import io.github.lightheaded.lugu.core.db.LibraryItemEntity
import io.github.lightheaded.lugu.core.db.LibraryItemFtsEntity
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.db.ProgressEntity
import io.github.lightheaded.lugu.core.db.QueueEntity
import io.github.lightheaded.lugu.core.db.QueueSource
import io.github.lightheaded.lugu.core.db.SeriesOrigin
import io.github.lightheaded.lugu.core.db.ServerEntity
import io.github.lightheaded.lugu.playback.BrowseNode
import io.github.lightheaded.lugu.playback.LuguPlaybackService
import io.github.lightheaded.lugu.playback.NotificationLayout
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The car's browse tree, checked without a car.
 *
 * Everything Android Auto does to lugu, it does through a `MediaBrowser` bound to
 * [LuguPlaybackService]. So does this: it binds as an ordinary Media3 browser client and
 * asks the same questions a head unit asks — what is at the root, what is at the *suggested*
 * root, what is under this node, what does this search return, and what commands does the
 * session offer. What comes back is the same `MediaItem` list the Desktop Head Unit would
 * draw, which means the structural half of docs/qa/auto.md can be asserted here rather than
 * read off a screen.
 *
 * **No server, and nothing on the network.** The tree is served from Room, which is the
 * whole design — a car connects the moment the phone is plugged in, often before any sync
 * has run. These tests prove it by seeding Room directly and never signing in: the account
 * row they write points at `books.example`, a reserved domain that cannot resolve, so
 * nothing in the tree can have come from a server whether or not the emulator has a
 * network. That is also why none of this skips: it runs on a bare CI emulator.
 *
 * **Seeding goes through a second [LuguDatabase] handle on the same file.** The app's own
 * handle belongs to a Hilt graph an instrumented test cannot reach into — an `@EntryPoint`
 * declared out here is not part of the component the app generated at compile time — and
 * SQLite is perfectly happy with two connections in one process. Every seeded row carries a
 * test-only `serverId`, so a device that happens to be signed in to a real server keeps its
 * data invisible to these queries and gets it back afterwards: the real account is only
 * *deactivated* for the length of a test, never deleted.
 *
 * **What is not covered here.** Anything needing audio or a screen: that a book starts at
 * its remembered position and its remembered speed, that covers actually decode in the
 * car's own process, that the transport buttons move by a chapter. The buttons are asserted
 * as *advertised* — which is what a head unit reads to draw them — and no further.
 *
 * ## The trust model, and why half of it is not asserted
 *
 * `onConnect` hands a trusted controller `DEFAULT_SESSION_AND_LIBRARY_COMMANDS` and an
 * untrusted one the restricted set. The trusted half is asserted below and is the half that
 * matters: it is the branch a head unit takes, and a change that gave everyone the
 * restricted set would fail that test.
 *
 * The untrusted half cannot be produced from here, and the shell trick that looked like it
 * would do it does not. Media3 does not ask the platform's `isTrustedForMediaControl` at
 * all — it carries its own copy, `androidx.media3.session.legacy.MediaSessionManager`,
 * whose answer is
 *
 * ```
 * uid == SYSTEM_UID || uid == Process.myUid() || STATUS_BAR_SERVICE || MEDIA_CONTENT_CONTROL
 *     || an enabled notification listener
 * ```
 *
 * Instrumentation runs **inside** the process under test, so `uid == Process.myUid()` is
 * true before any of the rest is consulted, and every controller this test can build is
 * trusted no matter what is granted or revoked over shell. Granting notification-listener
 * access (`cmd notification allow_listener`) only adds a route to `true` that is already
 * taken; there is no way to subtract one. Producing an untrusted controller needs a second
 * application id — a `com.android.test` module, which is the same change
 * docs/qa/instrumented.md already names for the force-stop case. Until then the untrusted
 * branch stays a manual check.
 *
 * The method names here are underscored rather than the backticked sentences the JVM suites
 * use. A name with spaces in it needs DEX version 040, which needs minSdk 30; lugu's minSdk
 * is 26, so the test APK will not dex with them.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AutoBrowseTreeTest {

    @get:Rule(order = 0)
    val notifications = grantNotificationPermission()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: LuguDatabase

    /** Whatever account the device was signed in to, put back in [restoreTheDevice]. */
    private var displacedServer: ServerEntity? = null

    private var browser: MediaBrowser? = null

    @Before
    fun seedNothingAndSignOut() {
        db = LuguDatabase.build(context)
        runBlocking {
            displacedServer = db.serverDao().active()
            db.serverDao().clearActive()
        }
        wipeTestRows()
    }

    @After
    fun restoreTheDevice() {
        releaseBrowser()
        runBlocking {
            runCatching {
                wipeTestRows()
                displacedServer?.let { db.serverDao().setActive(it) }
            }
        }
        db.close()
    }

    // -- The browse tree -----------------------------------------------------------------

    /**
     * "The root shows Continue and Libraries always."
     *
     * Asserted with an account that has a library and nothing in it, because that is the
     * case the rule exists for: the other five rows are absent, and the two that are the
     * way back to everything are still there.
     */
    @Test
    fun the_root_always_offers_continue_and_libraries() {
        signIn()
        seedLibraries()

        assertThat(titlesOf(childrenOf(BrowseNode.ROOT)))
            .containsExactly("Continue", "Libraries")
            .inOrder()
    }

    /**
     * "Up next, Downloaded, Series and Podcasts only when they have something in them."
     *
     * Both directions in one test, because either alone is half the rule: the same account
     * is asked before and after the rows that justify those categories exist.
     */
    @Test
    fun the_optional_rows_appear_only_once_they_have_something_in_them() {
        signIn()
        seedLibraries()
        seedBooks()

        // A series exists now, and nothing else does.
        assertThat(titlesOf(childrenOf(BrowseNode.ROOT)))
            .containsExactly("Continue", "Series", "Libraries")
            .inOrder()

        seedPodcast()
        seedProgress()
        seedQueue()
        seedDownload()

        assertThat(titlesOf(childrenOf(BrowseNode.ROOT)))
            .containsExactly(
                "Continue",
                "Up next",
                "Latest episodes",
                "Downloaded",
                "Series",
                "Podcasts",
                "Libraries",
            )
            .inOrder()
    }

    /**
     * "Every category opens, and none of them opens onto an empty list."
     *
     * Walked rather than listed, so a node added later is covered without this test being
     * edited. The walk terminates on its own: only a browsable row is descended into, and
     * everything a leaf node returns is playable.
     */
    @Test
    fun no_category_opens_onto_an_empty_list() {
        signInWithAFullLibrary()

        assertBranchIsNotEmpty(BrowseNode.ROOT, "root")
    }

    /**
     * "Series lists series, not books; opening one lists its books in reading order."
     *
     * The two books are numbered #2 and #10 and are titled so that reading order and
     * alphabetical order disagree — *Lighthouse Wakes* is #2, *Lighthouse Falls* is #10. A
     * series ordered by title, or by the number as text, puts #10 first and fails here,
     * which is the failure the separate sequence column exists to prevent.
     */
    @Test
    fun series_lists_series_and_a_series_lists_its_books_in_reading_order() {
        signInWithAFullLibrary()

        val series = childrenOf(BrowseNode.AllSeries.id)
        assertThat(titlesOf(series)).containsExactly(SERIES_NUMBERED, SERIES_UNNUMBERED).inOrder()
        assertThat(series.map { it.mediaMetadata.isBrowsable }).doesNotContain(false)
        assertThat(titlesOf(series)).containsNoneOf(BOOK_TWO_TITLE, BOOK_TEN_TITLE)

        val books = childrenOf(BrowseNode.Series(SERIES_NUMBERED).id)
        assertThat(titlesOf(books)).containsExactly(BOOK_TWO_TITLE, BOOK_TEN_TITLE).inOrder()
        assertThat(books.map { it.mediaMetadata.isPlayable }).doesNotContain(false)
    }

    /** "A podcast opens onto its episodes, newest first." */
    @Test
    fun a_podcast_opens_onto_its_episodes_newest_first() {
        signInWithAFullLibrary()

        val podcasts = childrenOf(BrowseNode.AllPodcasts.id)
        assertThat(titlesOf(podcasts)).containsExactly(PODCAST_TITLE)

        val episodes = childrenOf(BrowseNode.Podcast(PODCAST_ID).id)
        assertThat(titlesOf(episodes))
            .containsExactly("Episode three", "Episode two", "Episode one")
            .inOrder()
        assertThat(episodes.map { it.mediaId })
            .containsExactly(
                BrowseNode.Playable(PODCAST_ID, "$EPISODE_ID_PREFIX-3").id,
                BrowseNode.Playable(PODCAST_ID, "$EPISODE_ID_PREFIX-2").id,
                BrowseNode.Playable(PODCAST_ID, "$EPISODE_ID_PREFIX-1").id,
            )
            .inOrder()
    }

    /**
     * Continue offers a row per thing being listened to, each addressing itself.
     *
     * The podcast row carries its own episode id rather than the show's, which is what lets
     * a car start that episode without opening the show first.
     */
    @Test
    fun continue_offers_one_row_per_thing_being_listened_to() {
        signInWithAFullLibrary()

        assertThat(childrenOf(BrowseNode.Continue.id).map { it.mediaId })
            .containsExactly(
                BrowseNode.Playable(PODCAST_ID, "$EPISODE_ID_PREFIX-2").id,
                BrowseNode.Playable(BOOK_TWO_ID, null).id,
            )
            .inOrder()
    }

    /**
     * Covers leave as `content://`, never as a server URL.
     *
     * A car fetches artwork in its own process, which has none of lugu's authentication, so
     * an `https://` artwork URI is a blank tile on every row — see `CoverProvider`. Whether
     * the bytes decode is a manual check; whether the row asks the right process for them
     * is this one.
     */
    @Test
    fun every_playable_row_offers_its_cover_through_the_content_provider() {
        signInWithAFullLibrary()

        val playable = childrenOf(BrowseNode.Library(BOOKS_LIBRARY_ID).id) +
            childrenOf(BrowseNode.Podcast(PODCAST_ID).id)
        assertThat(playable).isNotEmpty()
        playable.forEach { item ->
            val artwork = item.mediaMetadata.artworkUri
            assertWithMessage("${item.mediaMetadata.title} has no artwork uri").that(artwork).isNotNull()
            assertThat(artwork!!.scheme).isEqualTo("content")
        }
    }

    // -- The suggested root ---------------------------------------------------------------

    /**
     * The hint that reaches Android Auto's "For you" pane, and the root it is answered with.
     *
     * A host asks for suggestions by setting `EXTRA_SUGGESTED` in its root hints. A legacy
     * browser's hints reach the session as `LibraryParams.isSuggested`, so a Media3 browser
     * asks the same question by building the params directly. What must come back is a
     * *different* root: one id for both roots would make a browser cache the suggestions
     * over the browse tree.
     *
     * The flag must also come back, because the platform asks that of an app that can
     * serve suggestions.
     */
    @Test
    fun the_suggested_hint_answers_with_a_root_of_its_own() {
        signInWithAFullLibrary()

        val root = libraryRoot(suggestedParams())
        assertThat(root.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(root.value?.mediaId).isEqualTo(BrowseNode.SUGGESTED_ROOT)
        assertThat(root.value?.mediaId).isNotEqualTo(BrowseNode.ROOT)
        assertWithMessage("the suggestion flag is not repeated in the answer")
            .that(root.params?.isSuggested)
            .isTrue()
    }

    /**
     * "For you" holds what Continue holds, in the same order, and every row can be pressed.
     *
     * This is the whole of the ask: a car is for carrying on with something, so the
     * suggestion lugu makes is the one it already makes at the top of its tree. Asserted
     * against Continue's own answer rather than against a written-out list, so the two
     * cannot drift apart.
     */
    @Test
    fun the_suggested_root_holds_what_continue_holds() {
        signInWithAFullLibrary()

        val suggested = childrenOf(BrowseNode.SUGGESTED_ROOT)
        assertThat(suggested).isNotEmpty()
        assertThat(suggested.map { it.mediaId })
            .containsExactlyElementsIn(childrenOf(BrowseNode.Continue.id).map { it.mediaId })
            .inOrder()
        // Something to press, not something to open into.
        assertThat(suggested.map { it.mediaMetadata.isPlayable }).doesNotContain(false)
        assertThat(suggested.map { it.mediaMetadata.isBrowsable }).doesNotContain(true)
    }

    /**
     * An account that has started nothing.
     *
     * Continue is legitimately empty for it, so the suggested root is too. Empty and
     * successful, never an error — the same rule the ordinary root follows, for the same
     * reason (androidx/media#3158).
     */
    @Test
    fun a_suggested_root_with_nothing_in_progress_is_empty_rather_than_an_error() {
        signIn()
        seedLibraries()
        seedBooks()

        assertThat(libraryRoot(suggestedParams()).resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        // [childrenOf] asserts the result code itself.
        assertThat(childrenOf(BrowseNode.SUGGESTED_ROOT)).isEmpty()
    }

    /**
     * Signed out, the suggested root behaves the way the ordinary root does.
     *
     * A dashboard pane is drawn before anything is browsed, so this is the state a host
     * meets first on a phone nobody has signed in on. One row that explains itself, and no
     * error anywhere on the path.
     */
    @Test
    fun signed_out_the_suggested_root_answers_the_way_the_ordinary_root_does() {
        // No sign-in: @Before has already deactivated whatever account the device had.
        val root = libraryRoot(suggestedParams())
        assertThat(root.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(root.value?.mediaId).isEqualTo(BrowseNode.SUGGESTED_ROOT)

        val children = childrenOf(BrowseNode.SUGGESTED_ROOT)
        assertThat(children).hasSize(1)
        assertThat(children.single().mediaMetadata.title.toString()).contains("Sign in")
    }

    /**
     * Ordinary browsing is untouched.
     *
     * Three ways of not asking for suggestions — no params at all, params that say nothing,
     * and params that say `false` — all get today's root and today's rows.
     */
    @Test
    fun a_root_asked_for_without_the_suggestion_hint_is_unchanged() {
        signInWithAFullLibrary()

        listOf(
            "no hints at all" to null,
            "hints that say nothing" to LibraryParams.Builder().build(),
            "the hint set to false" to LibraryParams.Builder().setSuggested(false).build(),
            "a different hint" to LibraryParams.Builder().setOffline(true).build(),
        ).forEach { (described, params) ->
            val root = libraryRoot(params)
            assertWithMessage("$described answered with an error").that(root.resultCode)
                .isEqualTo(LibraryResult.RESULT_SUCCESS)
            assertWithMessage("$described was served the suggested root").that(root.value?.mediaId)
                .isEqualTo(BrowseNode.ROOT)
        }

        assertThat(titlesOf(childrenOf(BrowseNode.ROOT))).contains("Continue")
    }

    // -- Search --------------------------------------------------------------------------

    /**
     * The car's search box, and the voice request behind it.
     *
     * Both run through the same FTS index the phone's search uses, with no connection —
     * which is why a title, an author and a miss can all be asserted here.
     */
    @Test
    fun search_finds_a_book_by_title_and_by_author_and_answers_a_miss_with_nothing() {
        signInWithAFullLibrary()

        assertThat(titlesOf(searchFor("Lighthouse")))
            .containsExactly(BOOK_TEN_TITLE, BOOK_TWO_TITLE)
        assertThat(titlesOf(searchFor("Corven")))
            .containsExactly(BOOK_TEN_TITLE, BOOK_TWO_TITLE)
        // Nothing, rather than an error: [searchFor] asserts the result code itself.
        assertThat(searchFor("zzzznothinghere")).isEmpty()
    }

    // -- Failure modes -------------------------------------------------------------------

    /**
     * "Signed out: the tree shows one row saying to sign in, and lugu stays in the
     * launcher rather than disappearing or looping."
     *
     * The looping half is the load-bearing one. Returning an error from the root is what
     * sends Android Auto into a bind-retry loop (androidx/media#3158), and a loop is not
     * something a browser client can watch for — so what is asserted instead is its cause:
     * the root answers with an item and a success code even with no account at all.
     */
    @Test
    fun signed_out_the_tree_is_one_row_and_the_root_is_never_an_error() {
        // No sign-in: @Before has already deactivated whatever account the device had.
        val connected = browser()
        val root = onMainFuture { connected.getLibraryRoot(null) }
        assertThat(root.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(root.value?.mediaId).isEqualTo(BrowseNode.ROOT)

        val children = childrenOf(BrowseNode.ROOT)
        assertThat(children).hasSize(1)
        val only = children.single()
        assertThat(only.mediaMetadata.title.toString()).contains("Sign in")
        // Nothing to press, and nothing to open: a row that only explains itself.
        assertThat(only.mediaMetadata.isPlayable).isFalse()
        assertThat(only.mediaMetadata.isBrowsable).isFalse()
    }

    /**
     * An id a car kept from a previous session, for a node that no longer exists.
     *
     * A car hands ids back after the process has died, so this is routine rather than
     * hostile. Answering with an empty list rather than an error is the same rule as the
     * root's, for the same reason.
     */
    @Test
    fun an_id_we_no_longer_serve_answers_with_an_empty_list_rather_than_an_error() {
        signIn()
        seedLibraries()

        assertThat(childrenOf("lugu/series/A series nobody has")).isEmpty()
        assertThat(childrenOf("not an id lugu ever issued")).isEmpty()
    }

    // -- The session ---------------------------------------------------------------------

    /**
     * "Previous chapter / next chapter appear in the transport", and the speed button.
     *
     * A projection host draws these from the session: the commands have to be granted at
     * connection time and the buttons have to be in the layout it is handed. Both are
     * asserted, because either one missing is a car with no chapter buttons.
     */
    @Test
    fun the_session_advertises_the_chapter_and_speed_commands() {
        signIn()

        val connected = browser()
        val commands = onMain { connected.availableSessionCommands }
        listOf(
            NotificationLayout.COMMAND_CHAPTER_PREVIOUS,
            NotificationLayout.COMMAND_CHAPTER_NEXT,
            COMMAND_SPEED_CYCLE,
        ).forEach { action ->
            assertWithMessage("$action is not offered to a controller")
                .that(commands.contains(SessionCommand(action, android.os.Bundle.EMPTY)))
                .isTrue()
        }

        assertThat(onMain { connected.customLayout }.map { it.displayName.toString() })
            .containsAtLeast("Previous chapter", "Next chapter", "Speed")
    }

    /**
     * The trusted branch of `onConnect`, which is the branch a head unit takes.
     *
     * Asserted against Media3's own two constants rather than against a list of command
     * codes, so it keeps meaning what it says when Media3 moves a command from one set to
     * the other. See this class's KDoc for why the untrusted branch is not here.
     */
    @Test
    fun a_trusted_controller_receives_the_full_command_set() {
        signIn()

        val trustedOnly = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.commands -
            MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_AND_LIBRARY_COMMANDS.commands
        assertWithMessage("the two default command sets no longer differ, so this proves nothing")
            .that(trustedOnly)
            .isNotEmpty()

        val connected = browser()
        assertThat(onMain { connected.availableSessionCommands }.commands)
            .containsAtLeastElementsIn(trustedOnly)
    }

    // -- Browsing ------------------------------------------------------------------------

    private fun browser(): MediaBrowser = browser ?: connectBrowser()

    private fun connectBrowser(): MediaBrowser {
        val token = SessionToken(context, ComponentName(context, LuguPlaybackService::class.java))
        lateinit var future: ListenableFuture<MediaBrowser>
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            future = MediaBrowser.Builder(context, token).buildAsync()
        }
        return future.get(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS).also { browser = it }
    }

    private fun releaseBrowser() {
        val held = browser ?: return
        browser = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { held.release() }
    }

    /** The root, asked for the way a host asks: with hints, or with none. */
    private fun libraryRoot(params: LibraryParams?): LibraryResult<MediaItem> {
        val connected = browser()
        return onMainFuture { connected.getLibraryRoot(params) }
    }

    /**
     * What a legacy browser's `EXTRA_SUGGESTED` hint becomes on the way in.
     *
     * `LegacyConversions.convertToLibraryParams` sets this field from that key, so a
     * Media3 browser that sets the field asks the session the same question a car asks.
     * What no browser client can do is prove that the car sets the key at all — see
     * docs/qa/auto.md.
     */
    private fun suggestedParams(): LibraryParams =
        LibraryParams.Builder().setSuggested(true).build()

    private fun childrenOf(parentId: String): List<MediaItem> {
        // Connected here rather than inside the block below: connecting itself hops to the
        // main thread, and hopping to it from itself deadlocks.
        val connected = browser()
        val result = onMainFuture { connected.getChildren(parentId, 0, PAGE_SIZE, null) }
        assertWithMessage("$parentId answered with an error").that(result.resultCode)
            .isEqualTo(LibraryResult.RESULT_SUCCESS)
        return result.value.orEmpty()
    }

    /** The two calls a car's search box makes, in the order it makes them. */
    private fun searchFor(query: String): List<MediaItem> {
        val connected = browser()
        val started = onMainFuture { connected.search(query, null) }
        assertThat(started.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        val result = onMainFuture { connected.getSearchResult(query, 0, PAGE_SIZE, null) }
        assertWithMessage("searching for \"$query\" answered with an error").that(result.resultCode)
            .isEqualTo(LibraryResult.RESULT_SUCCESS)
        return result.value.orEmpty()
    }

    private fun assertBranchIsNotEmpty(parentId: String, path: String) {
        val children = childrenOf(parentId)
        assertWithMessage("$path opens onto an empty list").that(children).isNotEmpty()
        children.filter { it.mediaMetadata.isBrowsable == true }.forEach { child ->
            assertBranchIsNotEmpty(child.mediaId, "$path > ${child.mediaMetadata.title}")
        }
    }

    private fun titlesOf(items: List<MediaItem>): List<String> =
        items.map { it.mediaMetadata.title?.toString().orEmpty() }

    /** Every [MediaBrowser] member has to be touched on the thread that built it. */
    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /**
     * Asks on the browser's thread and waits on this one.
     *
     * The wait cannot be on the main thread: the service answers there, so blocking it
     * would be waiting for something that can only happen after the wait ends.
     */
    private fun <T> onMainFuture(block: () -> ListenableFuture<T>): T {
        lateinit var future: ListenableFuture<T>
        InstrumentationRegistry.getInstrumentation().runOnMainSync { future = block() }
        return future.get(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    // -- Seeding -------------------------------------------------------------------------

    /**
     * An account, and nothing else.
     *
     * Written straight to Room rather than through `AuthRepository`, which would need a
     * server to log in to. The browse tree asks for the active account and never for a
     * token, so this is the whole of what "signed in" means to it.
     */
    private fun signIn() = runBlocking {
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
    }

    private fun signInWithAFullLibrary() {
        signIn()
        seedLibraries()
        seedBooks()
        seedPodcast()
        seedProgress()
        seedQueue()
        seedDownload()
    }

    private fun seedLibraries() = runBlocking {
        db.libraryDao().upsertAll(
            listOf(
                LibraryEntity(TEST_SERVER_ID, TEST_USER_ID, BOOKS_LIBRARY_ID, "Books", "book", 0),
                LibraryEntity(TEST_SERVER_ID, TEST_USER_ID, PODCASTS_LIBRARY_ID, "Podcasts", "podcast", 1),
            ),
        )
    }

    /**
     * Two books in one series, numbered so that reading order and alphabetical order
     * disagree, plus a second series membership on one of them.
     *
     * The second membership is not decoration: a book can be in several series, and it is
     * what makes "Series lists series" assertable with two entries rather than one.
     */
    private fun seedBooks() = runBlocking {
        db.libraryItemDao().upsertAll(
            listOf(
                book(BOOK_TWO_ID, BOOK_TWO_TITLE),
                book(BOOK_TEN_ID, BOOK_TEN_TITLE),
            ),
        )
        db.itemSeriesDao().upsertAll(
            listOf(
                series(BOOK_TWO_ID, SERIES_NUMBERED, sequence = 2.0),
                series(BOOK_TEN_ID, SERIES_NUMBERED, sequence = 10.0),
                series(BOOK_TEN_ID, SERIES_UNNUMBERED, sequence = null),
            ),
        )
        db.libraryItemFtsDao().insertAll(
            listOf(
                fts(BOOK_TWO_ID, "$BOOK_TWO_TITLE $AUTHOR $SERIES_NUMBERED"),
                fts(BOOK_TEN_ID, "$BOOK_TEN_TITLE $AUTHOR $SERIES_NUMBERED $SERIES_UNNUMBERED"),
            ),
        )
    }

    private fun seedPodcast() = runBlocking {
        db.libraryItemDao().upsertAll(
            listOf(
                book(PODCAST_ID, PODCAST_TITLE).copy(
                    libraryId = PODCASTS_LIBRARY_ID,
                    mediaType = "PODCAST",
                    numEpisodes = 3,
                ),
            ),
        )
        // Published out of order on purpose: the node's order must come from the date
        // rather than from the order the rows happen to be stored in.
        db.episodeDao().upsertAll(
            listOf(
                episode("$EPISODE_ID_PREFIX-2", "Episode two", publishedAtMs = 2_000_000L, position = 2),
                episode("$EPISODE_ID_PREFIX-3", "Episode three", publishedAtMs = 3_000_000L, position = 3),
                episode("$EPISODE_ID_PREFIX-1", "Episode one", publishedAtMs = 1_000_000L, position = 1),
            ),
        )
        db.libraryItemFtsDao().insertAll(listOf(fts(PODCAST_ID, PODCAST_TITLE)))
    }

    /**
     * One book and one episode part-heard.
     *
     * The episode row is what makes the podcast a followed one, which is what puts "Latest
     * episodes" at the root — lugu has no subscribe button, so having started something is
     * the closest thing to a subscription it can know.
     */
    private fun seedProgress() = runBlocking {
        db.progressDao().upsertAll(
            listOf(
                progress(BOOK_TWO_ID, episodeKey = "", lastUpdateMs = 1_000L),
                progress(PODCAST_ID, episodeKey = "$EPISODE_ID_PREFIX-2", lastUpdateMs = 2_000L),
            ),
        )
    }

    private fun seedQueue() = runBlocking {
        db.queueDao().upsertAll(
            listOf(
                QueueEntity(
                    serverId = TEST_SERVER_ID,
                    userId = TEST_USER_ID,
                    libraryItemId = BOOK_TEN_ID,
                    episodeKey = "",
                    position = 0,
                    addedAtMs = 1_000L,
                    source = QueueSource.USER,
                ),
            ),
        )
    }

    private fun seedDownload() = runBlocking {
        db.downloadDao().upsert(
            DownloadEntity(
                serverId = TEST_SERVER_ID,
                userId = TEST_USER_ID,
                libraryItemId = BOOK_TWO_ID,
                episodeKey = "",
                title = BOOK_TWO_TITLE,
                author = AUTHOR,
                mediaType = "BOOK",
                state = DownloadState.COMPLETED,
                tracksJson = "[]",
                durationSec = 3_600.0,
                bytesTotal = 1_000L,
                bytesDownloaded = 1_000L,
                percent = 100f,
                requestedAtMs = 1_000L,
                completedAtMs = 2_000L,
                error = null,
            ),
        )
    }

    private fun book(id: String, title: String) = LibraryItemEntity(
        serverId = TEST_SERVER_ID,
        userId = TEST_USER_ID,
        id = id,
        libraryId = BOOKS_LIBRARY_ID,
        mediaType = "BOOK",
        title = title,
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

    private fun series(itemId: String, name: String, sequence: Double?) = ItemSeriesEntity(
        serverId = TEST_SERVER_ID,
        userId = TEST_USER_ID,
        libraryItemId = itemId,
        libraryId = BOOKS_LIBRARY_ID,
        seriesName = name,
        seriesId = null,
        sequence = sequence,
        serverRank = null,
        origin = SeriesOrigin.SERVER,
        syncedAtMs = 1_000L,
    )

    private fun episode(id: String, title: String, publishedAtMs: Long, position: Int) = EpisodeEntity(
        serverId = TEST_SERVER_ID,
        userId = TEST_USER_ID,
        id = id,
        libraryItemId = PODCAST_ID,
        title = title,
        subtitle = PODCAST_TITLE,
        description = null,
        episodeNumber = position.toString(),
        season = null,
        publishedAtMs = publishedAtMs,
        durationSec = 1_800.0,
        position = position,
    )

    private fun progress(itemId: String, episodeKey: String, lastUpdateMs: Long) = ProgressEntity(
        serverId = TEST_SERVER_ID,
        userId = TEST_USER_ID,
        libraryItemId = itemId,
        episodeKey = episodeKey,
        currentTimeSec = 120.0,
        durationSec = 3_600.0,
        progress = 0.03,
        isFinished = false,
        lastUpdateMs = lastUpdateMs,
        startedAtMs = 1_000L,
        serverLastUpdateMs = lastUpdateMs,
        isDirty = false,
    )

    private fun fts(itemId: String, text: String) = LibraryItemFtsEntity(
        serverId = TEST_SERVER_ID,
        userId = TEST_USER_ID,
        itemId = itemId,
        libraryId = BOOKS_LIBRARY_ID,
        text = text,
    )

    /**
     * Removes everything this class writes, and nothing else.
     *
     * By `serverId`, which is the column every user-scoped table carries — so a device
     * signed in to a real server cannot lose a row to this no matter which test fails or
     * how.
     */
    private fun wipeTestRows() {
        val database = db.openHelper.writableDatabase
        SEEDED_TABLES.forEach { table ->
            database.execSQL("DELETE FROM `$table` WHERE serverId = ?", arrayOf<Any>(TEST_SERVER_ID))
        }
    }

    private companion object {
        /**
         * A reserved domain, so nothing can resolve it and nothing in the tree can have
         * come from a server. See RFC 2606.
         */
        const val TEST_SERVER_URL = "https://books.example"
        const val TEST_USER_ID = "browse-tree-test-user"
        const val TEST_SERVER_ID = "$TEST_SERVER_URL#$TEST_USER_ID"

        const val BOOKS_LIBRARY_ID = "browse-tree-test-books"
        const val PODCASTS_LIBRARY_ID = "browse-tree-test-podcasts"

        const val BOOK_TWO_ID = "browse-tree-test-book-2"
        const val BOOK_TEN_ID = "browse-tree-test-book-10"
        const val PODCAST_ID = "browse-tree-test-podcast"
        const val EPISODE_ID_PREFIX = "browse-tree-test-episode"

        /**
         * Invented names, as everything in this repository's fixtures is. The pairing is
         * deliberate: #2 sorts *after* #10 alphabetically, so reading order and every other
         * order disagree.
         */
        const val BOOK_TWO_TITLE = "Lighthouse Wakes"
        const val BOOK_TEN_TITLE = "Lighthouse Falls"
        const val AUTHOR = "James T. R. Corven"
        const val NARRATOR = "Jefferson Vale"
        const val SERIES_NUMBERED = "Riverton"
        const val SERIES_UNNUMBERED = "The Tidelands"
        const val PODCAST_TITLE = "The Breakwater"

        /**
         * The speed button's action, copied rather than imported: it is a private constant
         * of `LuguPlaybackService`, and it is a string a head unit reads off the session, so
         * writing it out here is the assertion rather than a shortcut around one.
         */
        const val COMMAND_SPEED_CYCLE = "io.github.lightheaded.lugu.SPEED_CYCLE"

        /** Larger than anything seeded here, so nothing is lost to paging. */
        const val PAGE_SIZE = 100

        const val CONNECT_TIMEOUT_SEC = 20L
        const val CALL_TIMEOUT_SEC = 20L

        val SEEDED_TABLES = listOf(
            "server",
            "library",
            "library_item",
            "item_series",
            "episode",
            "progress",
            "queue",
            "download",
            "library_item_fts",
        )
    }
}
