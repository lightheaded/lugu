package io.github.lightheaded.lugu

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import io.github.lightheaded.lugu.core.download.NewEpisodeIntent
import io.github.lightheaded.lugu.core.model.formatClock
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.feature.library.AllEpisodesScreen
import io.github.lightheaded.lugu.feature.library.BrowseGroupScreen
import io.github.lightheaded.lugu.feature.library.BrowseScreen
import io.github.lightheaded.lugu.feature.library.CollectionScreen
import io.github.lightheaded.lugu.feature.library.CollectionsScreen
import io.github.lightheaded.lugu.feature.library.DownloadsScreen
import io.github.lightheaded.lugu.feature.library.EpisodeScreen
import io.github.lightheaded.lugu.feature.library.HomeScreen
import io.github.lightheaded.lugu.feature.library.ItemDetailScreen
import io.github.lightheaded.lugu.feature.library.QueueScreen
import io.github.lightheaded.lugu.feature.library.SearchScreen
import io.github.lightheaded.lugu.feature.player.MiniPlayer
import io.github.lightheaded.lugu.feature.player.PlayerLink
import io.github.lightheaded.lugu.feature.player.PlayerScreen
import io.github.lightheaded.lugu.feature.player.PlayerViewModel
import io.github.lightheaded.lugu.feature.settings.AccountsScreen
import io.github.lightheaded.lugu.feature.settings.ConnectionScreen
import io.github.lightheaded.lugu.feature.settings.LoginArgs
import io.github.lightheaded.lugu.feature.settings.LoginScreen
import io.github.lightheaded.lugu.feature.settings.SettingsScreen
import io.github.lightheaded.lugu.playback.PlaybackConnection
import io.github.lightheaded.lugu.ui.CrashPrompt
import io.github.lightheaded.lugu.ui.FeedbackScreen
import io.github.lightheaded.lugu.ui.LicensesScreen
import io.github.lightheaded.lugu.ui.LuguTheme
import io.github.lightheaded.lugu.ui.PlaybackRecordScreen
import io.github.lightheaded.lugu.ui.StatsScreen
import io.github.lightheaded.lugu.ui.RequestNotificationPermission
import io.github.lightheaded.lugu.ui.yieldCrashPrompt
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * The connection rather than a view model, because a spoken request has to be
     * honoured whether or not the player screen is ever composed.
     */
    @Inject lateinit var playback: PlaybackConnection

    /**
     * The episode a notification asked for, waiting for the graph to exist.
     *
     * An intent arrives before anything is composed, and on a cold start there is no
     * navigation controller to hand it to yet. So it is parked here and taken by [LuguApp]
     * once the graph is up, which also means one path serves both a cold start and a tap
     * while the app is already open.
     */
    private val pendingEpisode = MutableStateFlow<EpisodeTarget?>(null)

    /** A tap on the media notification or the widget, parked like [pendingEpisode]. */
    private val pendingOpenPlayer = MutableStateFlow(false)

    /**
     * The same signed-in check [LuguApp] waits on to pick a start destination — resolved
     * here too, so the splash screen below can hold on exactly as long as that check runs
     * and not a frame longer.
     */
    private val startup: StartupViewModel by viewModels()

    /** The identity-provider redirect waiting to be handed to the sign-in screen. */
    private val pendingOidcRedirect = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { startup.state.value == StartupState.Checking }
        handleSearchIntent(intent)
        handleEpisodeIntent(intent)
        // Not after a restore: the system hands the first intent over again then, and the
        // listener who had moved on would be taken back to the player.
        if (savedInstanceState == null) handlePlayerIntent(intent)
        handleOidcIntent(intent)
        setContent {
            LuguTheme {
                LuguApp(
                    playback = playback,
                    pendingEpisode = pendingEpisode,
                    onEpisodeHandled = { pendingEpisode.value = null },
                    pendingOpenPlayer = pendingOpenPlayer,
                    onOpenPlayerHandled = { pendingOpenPlayer.value = false },
                    pendingOidcRedirect = pendingOidcRedirect,
                    onOidcHandled = { pendingOidcRedirect.value = null },
                )
            }
        }
    }

    /** `singleTop`, so a second spoken request arrives here rather than in a new activity. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSearchIntent(intent)
        handleEpisodeIntent(intent)
        handlePlayerIntent(intent)
        handleOidcIntent(intent)
    }

    private fun handleSearchIntent(intent: Intent?) {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = intent.getStringExtra(SearchManager.QUERY)?.takeIf { it.isNotBlank() } ?: return
        playback.playFromSearch(query)
    }

    /**
     * The new-episode notification, which is the second kind of intent this activity routes.
     *
     * The extras are stripped once read. An activity keeps the intent that started it, so a
     * rotation runs `onCreate` again with the same one — and without this, turning the phone
     * on the player screen would throw the reader back onto the episode page they had left.
     */
    private fun handleEpisodeIntent(intent: Intent?) {
        val target = episodeTargetOf(intent) ?: return
        intent?.removeExtra(NewEpisodeIntent.EXTRA_LIBRARY_ITEM_ID)
        intent?.removeExtra(NewEpisodeIntent.EXTRA_EPISODE_ID)
        pendingEpisode.value = target
    }

    /** The media notification or the widget, which open the player. Cleared once read. */
    private fun handlePlayerIntent(intent: Intent?) {
        if (!opensPlayer(intent)) return
        intent?.removeExtra(OpenPlayerIntent.EXTRA_OPEN_PLAYER)
        pendingOpenPlayer.value = true
    }

    /**
     * The identity provider's redirect, landed on `lugu://oauth`.
     *
     * The data is cleared once read, for the same reason the episode extras are: an
     * activity keeps the intent that started it, so a rotation would replay the redirect —
     * and a replayed `code` is one the server has already spent, so the second attempt
     * fails and reports a sign-in problem to somebody who is already signed in.
     */
    private fun handleOidcIntent(intent: Intent?) {
        val data = intent?.data?.takeIf { it.scheme == OIDC_SCHEME } ?: return
        intent.data = null
        pendingOidcRedirect.value = data.toString()
    }

    private companion object {
        /** Matches the `android:scheme` on this activity's intent filter. */
        const val OIDC_SCHEME = "lugu"
    }
}

/** One episode, named by the two ids a route needs, or a book when [episodeId] is null. */
internal data class EpisodeTarget(val itemId: String, val episodeId: String?)

/**
 * What a notification's intent asks for, or null when it asks for nothing.
 *
 * A free function rather than a method, so the round trip — the notifier writes the extras,
 * this reads them back — can be tested without an activity.
 */
internal fun episodeTargetOf(intent: Intent?): EpisodeTarget? {
    val itemId = intent?.getStringExtra(NewEpisodeIntent.EXTRA_LIBRARY_ITEM_ID)
        ?.takeIf { it.isNotBlank() } ?: return null
    val episodeId = intent.getStringExtra(NewEpisodeIntent.EXTRA_EPISODE_ID)
        ?.takeIf { it.isNotBlank() }
    return EpisodeTarget(itemId, episodeId)
}

/**
 * The extra that makes a launch open the player.
 *
 * Set by the media notification's tap (`LuguPlaybackService.openAppIntent`, which spells
 * the same string out, because `:playback` cannot see this module) and by the widget.
 */
internal object OpenPlayerIntent {
    const val EXTRA_OPEN_PLAYER = "io.github.lightheaded.lugu.extra.OPEN_PLAYER"
}

/** Whether an intent asks for the player. A free function, so a test needs no activity. */
internal fun opensPlayer(intent: Intent?): Boolean =
    intent?.getBooleanExtra(OpenPlayerIntent.EXTRA_OPEN_PLAYER, false) == true

/**
 * Opens the player, and only one of it.
 *
 * A play from a page that the player itself opened would otherwise stack a second player
 * on the first, and Back would show the old one.
 */
private fun NavHostController.openPlayer() {
    // Already on top: a second open would build the player again and close its sheets.
    if (currentDestination?.route == Routes.PLAYER) return
    navigate(Routes.PLAYER) {
        launchSingleTop = true
        popUpTo(Routes.PLAYER) { inclusive = true }
    }
}

/**
 * Navigates only from a page that has settled.
 *
 * During a transition the new page is not yet RESUMED, so a second tap on the same row
 * does nothing, instead of pushing the same page twice.
 */
private fun NavHostController.navigateFromResumed(route: String) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        navigate(route)
    }
}

private const val PAGE_MS = 230
private const val PLAYER_MS = 300

private fun NavBackStackEntry.isPlayer(): Boolean = destination.route == Routes.PLAYER

/*
 * Short and plain, so that two pages never show through each other for long. A page
 * enters from the end side with a small slide and a fade, and Back mirrors it. The player
 * comes up from the bottom and goes back down.
 */
private val pageEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (targetState.isPlayer()) {
        slideInVertically(tween(PLAYER_MS)) { it } + fadeIn(tween(PLAYER_MS))
    } else {
        slideIntoContainer(
            AnimatedContentTransitionScope.SlideDirection.Start,
            tween(PAGE_MS),
        ) { it / 10 } + fadeIn(tween(PAGE_MS))
    }
}

private val pageExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (targetState.isPlayer()) {
        fadeOut(tween(PLAYER_MS))
    } else {
        slideOutOfContainer(
            AnimatedContentTransitionScope.SlideDirection.Start,
            tween(PAGE_MS),
        ) { it / 10 } + fadeOut(tween(PAGE_MS))
    }
}

private val pagePopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (initialState.isPlayer()) {
        fadeIn(tween(PLAYER_MS))
    } else {
        slideIntoContainer(
            AnimatedContentTransitionScope.SlideDirection.End,
            tween(PAGE_MS),
        ) { it / 10 } + fadeIn(tween(PAGE_MS))
    }
}

private val pagePopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (initialState.isPlayer()) {
        slideOutVertically(tween(PLAYER_MS)) { it } + fadeOut(tween(PLAYER_MS))
    } else {
        slideOutOfContainer(
            AnimatedContentTransitionScope.SlideDirection.End,
            tween(PAGE_MS),
        ) { it / 10 } + fadeOut(tween(PAGE_MS))
    }
}

internal object Routes {
    /** The nav argument that marks the sign-in the lapse effect pushed. See [LOGIN]. */
    const val LOGIN_OFFER = "offer"

    /**
     * The sign-in screen, with an optional account to sign in to again. See [LoginArgs].
     *
     * [LOGIN_OFFER] is true only on the screen that the lapse effect pushed. That screen,
     * and no other sign-in, offers "Not now" and lets a notification tap open the player
     * over it. Read from the entry, and not guessed from the route under it, because the
     * same route under it (Accounts, say) can mean a lapse or a choice to add an account.
     */
    const val LOGIN = "login?${LoginArgs.ACCOUNT}={${LoginArgs.ACCOUNT}}&$LOGIN_OFFER={$LOGIN_OFFER}"

    fun login(serverId: String? = null, offer: Boolean = false): String {
        val query = listOfNotNull(
            serverId?.let { "${LoginArgs.ACCOUNT}=${LoginArgs.encode(it)}" },
            "$LOGIN_OFFER=true".takeIf { offer },
        )
        return if (query.isEmpty()) "login" else "login?" + query.joinToString("&")
    }

    /** True when [entry] is the sign-in screen that the lapse effect pushed. */
    fun isLapseOffer(entry: NavBackStackEntry?): Boolean =
        entry?.destination?.route == LOGIN && entry.arguments?.getBoolean(LOGIN_OFFER) == true

    /**
     * The signed-in destination. It hosts both Home — the computed shelves, answering
     * "what should I play now" — and the library browse, which answers "show me
     * everything". They are two jobs and two tabs; one route, because switching between
     * them is not navigation anyone wants in their back stack.
     */
    const val HOME = "home"
    const val ITEM = "item/{itemId}"

    /**
     * One episode of one podcast.
     *
     * Nested under the item it belongs to rather than keyed on the episode id alone.
     * Audiobookshelf's episode ids are unique on their own, but a route that reads
     * `item/{itemId}/episode/{episodeId}` says what the page is part of, and the screen
     * needs the item id anyway — to play, to download and to name the show in the bar.
     */
    const val EPISODE = "item/{itemId}/episode/{episodeId}"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val DOWNLOADS = "downloads"
    const val QUEUE = "queue"
    const val LICENSES = "licenses"
    const val PLAYBACK_RECORD = "playback-record"
    const val STATS = "stats"
    const val ACCOUNTS = "accounts"
    const val FEEDBACK = "feedback"
    const val CONNECTION = "connection"
    const val COLLECTIONS = "collections"
    const val COLLECTION = "collections/{collectionId}"

    /** The episodes of every podcast in the library in view, in one list. */
    const val ALL_EPISODES = "episodes"

    /** One search across books, podcasts, episodes, authors, narrators, series and collections. */
    const val SEARCH = "search"

    /** Authors, series or narrators — the three groupings the item page links to. */
    const val BROWSE = "browse/{kind}"
    const val BROWSE_GROUP = "browse/{kind}/{name}"

    fun item(itemId: String) = "item/$itemId"

    fun episode(itemId: String, episodeId: String) = "item/$itemId/episode/$episodeId"

    fun browse(kind: String) = "browse/$kind"

    fun collection(collectionId: String) = "collections/$collectionId"

    /**
     * A name can be anything the server holds — a slash, a question mark, a hash, an
     * accent — so it travels through the route as URL-safe base64 rather than as
     * percent-encoded text.
     *
     * Percent-encoding is the obvious choice and the wrong one here: Navigation decodes
     * path arguments itself, so the screen must not decode again, and whether it decodes
     * is a property of the Navigation version rather than of anything in this file. That
     * leaves a name containing a literal percent sign decoding twice on one version and
     * once on another — a bug that appears on an upgrade nobody connects it to. Base64
     * contains no character Navigation treats as special, so both sides agree by
     * construction.
     */
    fun browseGroup(kind: String, name: String): String {
        val encoded = Base64.encodeToString(
            name.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "browse/$kind/$encoded"
    }

    /** The route of a page the player links to. See [PlayerLink] for which tap leads where. */
    fun of(link: PlayerLink): String = when (link) {
        is PlayerLink.Item -> item(link.itemId)
        is PlayerLink.Episode -> episode(link.itemId, link.episodeId)
        is PlayerLink.Group -> browseGroup(link.kind, link.name)
    }
}

@Composable
private fun LuguApp(
    playback: PlaybackConnection,
    pendingEpisode: StateFlow<EpisodeTarget?>,
    onEpisodeHandled: () -> Unit,
    pendingOpenPlayer: StateFlow<Boolean>,
    onOpenPlayerHandled: () -> Unit,
    pendingOidcRedirect: MutableStateFlow<String?>,
    onOidcHandled: () -> Unit,
    startViewModel: StartupViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val startState by startViewModel.state.collectAsStateWithLifecycle()
    val episodeTarget by pendingEpisode.collectAsStateWithLifecycle()
    val openPlayerRequested by pendingOpenPlayer.collectAsStateWithLifecycle()
    val oidcRedirect by pendingOidcRedirect.collectAsStateWithLifecycle()

    // Wait for the account check before choosing a start destination, so a returning
    // user never sees the login screen flash past. An account whose sign-in has ended
    // still starts on Home: its downloads play and its library reads from Room, and the
    // sign-in is offered on top by the lapse effect below. Starting it on the sign-in
    // screen left nothing under that screen, so Back closed the app and the downloads
    // were out of reach (maintainer, 25 November 2026).
    val start = when (startState) {
        StartupState.Checking -> return
        StartupState.HasAccount -> Routes.HOME
        StartupState.NoAccount -> Routes.LOGIN
    }

    /*
     * A sign-in that ends while the app runs. A rejected refresh token clears the tokens
     * deep inside a request, and before this nothing answered it: the library stayed on
     * screen, unable to load, and the only way out was Sign out, which deletes the
     * downloads (issue #12).
     *
     * Opened on top of whatever is on screen, rather than in place of it, once per lapse
     * for as long as this activity lives. Back, or the sign-in screen's own "Not now",
     * returns to the screen under it, where the downloads still play and the Accounts
     * screen can switch to another account. The account that lapsed is remembered across a
     * rotation, so turning the phone does not push the screen a second time. A new launch
     * of the activity asks again.
     *
     * Never over the player: while the player is on top, the offer waits, and it comes when
     * the player closes. A tap on the media notification after the activity was gone opens
     * the player at once, and the lapse is read a moment later; the offer then came up over
     * the book the tap asked for. `lapseOffer` holds the rule, and its test the cases.
     *
     * A lapse present at launch is answered here too, because the start destination is
     * Home for it. The effect can run before the graph has a destination, so it waits for
     * an entry instead of reading `currentDestination` once. The lapse counts as answered
     * only once the screen is pushed (or found on top), so an effect cancelled during a
     * wait — a rotation with the player open — leaves it to the next run.
     */
    val lapsed by startViewModel.lapsedAccount.collectAsStateWithLifecycle()
    var answeredLapse by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(lapsed?.serverId) {
        val serverId = lapsed?.serverId
        if (serverId == null) {
            answeredLapse = null
            return@LaunchedEffect
        }
        // Each change of the screen on top asks again, so a wait ends when the player closes.
        val offer = navController.currentBackStackEntryFlow
            .map { lapseOffer(it.destination.route, serverId, answeredLapse) }
            .first { it != LapseOffer.WAIT }
        when (offer) {
            LapseOffer.PUSH -> {
                answeredLapse = serverId
                navController.navigate(Routes.login(offer = true)) { launchSingleTop = true }
            }
            LapseOffer.ALREADY_SHOWN -> answeredLapse = serverId
            LapseOffer.NONE, LapseOffer.WAIT -> Unit
        }
    }

    /*
     * The trim-skip undo, raised to the shell so it reaches the mini player and every
     * other screen, not only the full player. `pendingJump` is `PlaybackConnection`'s one
     * automatic-correction notice — a trim skip, a large deliberate seek, or a position
     * adopted from another device — so hoisting its host here covers all three at once
     * rather than requiring a second channel just for trims. See `SkipRegionEnforcer`'s
     * KDoc on `undoFor` for why there is only the one.
     *
     * This `Scaffold` is composed for every route, so the same host now shows the notice
     * and its Undo wherever the listener is. `PlayerScreen` no longer shows this notice
     * itself, which is what stops it appearing twice while the full player is open — the
     * shell's copy is the only one left.
     */
    val shellSnackbarHostState = remember { SnackbarHostState() }

    // Offered once per crash, in the shell's snackbar: the moment someone can say what
    // they were doing is the moment right after it broke.
    CrashPrompt(
        snackbarHostState = shellSnackbarHostState,
        onOpenFeedback = { navController.navigate(Routes.FEEDBACK) },
    )
    val pendingJump by playback.pendingJump.collectAsStateWithLifecycle()
    val playerSettings by playback.settings.collectAsStateWithLifecycle(initialValue = PlayerSettings())
    val noticeMillis = playerSettings.noticeSeconds.coerceAtLeast(1) * 1000L

    LaunchedEffect(pendingJump, noticeMillis) {
        pendingJump?.let { pending ->
            // The crash offer waits for an answer, and this notice cannot wait behind it.
            shellSnackbarHostState.yieldCrashPrompt()
            val result = withTimeoutOrNull(noticeMillis) {
                shellSnackbarHostState.showSnackbar(
                    message = "${pending.reason ?: "Jumped"} from " +
                        "${formatClock(pending.fromSec)} to ${formatClock(pending.toSec)}",
                    actionLabel = "Undo",
                    withDismissAction = true,
                    duration = SnackbarDuration.Indefinite,
                )
            }
            if (result == SnackbarResult.ActionPerformed) playback.undoJump() else playback.dismissJump()
        }
    }

    /*
     * Whatever is playing outlives the screen it was started from, so the mini player
     * belongs to the shell and not to any one destination. It is composed by
     * `MiniPlayer`, which is now the one place in the app that draws the bar, decides
     * what sits under it and decides which of the two takes the gesture inset.
     *
     * Home is the route that made that necessary. Home owns the bottom of its own screen:
     * its tab bar is the floor, as every other media app places it. So Home hands its tab
     * bar out through its `bottomBar` slot, this file passes it in as the mini player's
     * floor, and the bar lands above it — with no second copy of the bar and no second
     * rule about the inset. Every other route hands in no floor, so the bar is the lowest
     * thing on the screen and takes the inset itself.
     *
     * The full player already shows playback state in full and the sign-in flow has
     * nothing to play, so both are left alone.
     */
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // True where the destination draws the bottom of its own screen. Only Home does.
    val routeOwnsItsBottom = currentRoute == Routes.HOME

    /*
     * Asked when playback first starts, not at launch: on the sign-in screen the request
     * explains nothing, and at the first play it is plainly about the playback controls.
     */
    val playerState by playback.state.collectAsStateWithLifecycle()
    RequestNotificationPermission(
        ask = playerState.isPlaying && currentRoute != null && currentRoute != Routes.LOGIN,
    )

    val showShellMiniPlayer = currentRoute != null &&
        !routeOwnsItsBottom &&
        currentRoute != Routes.PLAYER &&
        currentRoute != Routes.LOGIN

    Scaffold(
        // Bottom-anchored, like every other `SnackbarHost` in this codebase — Material3
        // places it above `bottomBar` automatically, so it never sits over the mini
        // player, and it is nowhere near the fixed top controls (chips, browse links)
        // the Compose overlay rule in CLAUDE.md is about.
        // Home draws this host in its own Scaffold, above its tab bar and mini player. Here
        // the bottom bar is empty on Home, so the snackbar would cover both.
        snackbarHost = {
            if (!routeOwnsItsBottom) SnackbarHost(shellSnackbarHostState)
        },
        bottomBar = {
            // MiniPlayer itself renders nothing when there is nothing to play, so no
            // separate playable check is needed here — with no floor to draw either it
            // emits no layout node at all, and the padding below then falls back to the
            // system bar inset alone.
            if (showShellMiniPlayer) {
                MiniPlayer(onOpen = { navController.openPlayer() })
            }
        },
    ) { shellPadding ->
        NavHost(
            navController = navController,
            startDestination = start,
            enterTransition = pageEnter,
            exitTransition = pageExit,
            popEnterTransition = pagePopEnter,
            popExitTransition = pagePopExit,
            /*
             * Reused by every destination without an edit of its own: each screen's own
             * Scaffold still asks for the system bar inset it always did, but that inset
             * is already spent here, so it is consumed rather than counted twice.
             *
             * Home is left to do all of it. A `Scaffold` with an empty bottom bar hands
             * its content the system bar inset as bottom padding, which would lift Home's
             * tab bar off the floor of the screen and leave a band of the shell's own
             * colour under it — and the tab bar, not the shell, is what has to paint
             * behind the gesture bar. Adding nothing here leaves Home exactly as it was
             * before the shell existed: its top bar takes the status bar, its tab bar
             * takes the gesture inset.
             */
            modifier = if (routeOwnsItsBottom) {
                Modifier
            } else {
                Modifier
                    .padding(shellPadding)
                    .consumeWindowInsets(shellPadding)
            },
        ) {
            composable(
                route = Routes.LOGIN,
                arguments = listOf(
                    navArgument(LoginArgs.ACCOUNT) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(Routes.LOGIN_OFFER) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { entry ->
                // "Not now" is offered on the screen the lapse effect pushed, at launch or
                // later, and only with a screen under it to return to. Not on the start
                // destination, and not on a sign-in somebody chose on the Accounts screen:
                // Back is the way out of a choice. Decided once per entry and before the
                // screen draws, so the button never appears under a thumb that is already
                // on its way to a field.
                val offersLibrary = remember(entry) {
                    offersNotNow(
                        pushedForLapse = Routes.isLapseOffer(entry),
                        hasEntryUnder = navController.previousBackStackEntry != null,
                    )
                }
                // The redirect is handed to this screen's own view model, because the
                // attempt it has to be matched against belongs to the sign-in in progress.
                // Read here rather than in the activity so it reaches the same view model
                // instance that started the sign-in.
                LoginScreen(
                    // The whole stack goes. A sign-in from the Accounts screen or after a
                    // lapse has the old library under it, and Back must not return there.
                    onSignedIn = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    // Only from a settled screen: a second tap during the pop would take
                    // the library off the stack as well, and Back would close the app.
                    onNotNow = if (offersLibrary) {
                        {
                            if (entry.lifecycle.currentState == Lifecycle.State.RESUMED) {
                                navController.popBackStack()
                            }
                        }
                    } else {
                        null
                    },
                    providerRedirect = oidcRedirect,
                    onProviderRedirectHandled = onOidcHandled,
                    devServerUrl = BuildConfig.DEV_SERVER_URL,
                    devUsername = BuildConfig.DEV_USER,
                    devPassword = BuildConfig.DEV_PASS,
                )
            }

            composable(Routes.HOME) {
                val playerViewModel: PlayerViewModel = hiltViewModel()
                HomeScreen(
                    onOpenItem = { navController.navigateFromResumed(Routes.item(it)) },
                    onOpenSearch = { navController.navigateFromResumed(Routes.SEARCH) },
                    onOpenSettings = { navController.navigateFromResumed(Routes.SETTINGS) },
                    onOpenDownloads = { navController.navigateFromResumed(Routes.DOWNLOADS) },
                    onOpenQueue = { navController.navigateFromResumed(Routes.QUEUE) },
                    onBrowse = { kind -> navController.navigateFromResumed(Routes.browse(kind)) },
                    onOpenCollections = { navController.navigateFromResumed(Routes.COLLECTIONS) },
                    onOpenEpisodes = { navController.navigateFromResumed(Routes.ALL_EPISODES) },
                    // A shelf tap on something already in progress means "carry on", so it
                    // plays rather than opening a page and asking again.
                    onPlay = { itemId, episodeId ->
                        playerViewModel.play(itemId, episodeId)
                        navController.openPlayer()
                    },
                    // The same call as the shell's own bottom bar above, with Home's tab
                    // bar handed in as the floor under the mini player.
                    bottomBar = { tabBar ->
                        MiniPlayer(
                            onOpen = { navController.openPlayer() },
                            floor = tabBar,
                        )
                    },
                    // The shell's host, drawn by Home so that it sits above the tab bar.
                    snackbarHost = { SnackbarHost(shellSnackbarHostState) },
                    onOpenPlayer = { navController.openPlayer() },
                )
            }

            composable(Routes.DOWNLOADS) {
                DownloadsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { navController.navigateFromResumed(Routes.item(it)) },
                    // A downloaded episode opens its own page, not the show it is part of.
                    onOpenEpisode = { itemId, episodeId ->
                        navController.navigateFromResumed(Routes.episode(itemId, episodeId))
                    },
                )
            }

            composable(Routes.QUEUE) {
                val playerViewModel: PlayerViewModel = hiltViewModel()
                QueueScreen(
                    onBack = { navController.popBackStack() },
                    // Tapping a queued item plays it now rather than opening its page: the
                    // queue is a list of things to play, so that is what a tap must mean.
                    onPlay = { itemId, episodeId ->
                        playerViewModel.play(itemId, episodeId)
                        navController.openPlayer()
                    },
                )
            }

            composable(
                route = Routes.ITEM,
                arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
            ) {
                val playerViewModel: PlayerViewModel = hiltViewModel()
                ItemDetailScreen(
                    onBack = { navController.popBackStack() },
                    onPlay = { itemId, episodeId ->
                        playerViewModel.play(itemId, episodeId)
                        navController.openPlayer()
                    },
                    // A tap on an episode reads it; the row's own play button plays it.
                    onOpenEpisode = { itemId, episodeId ->
                        navController.navigateFromResumed(Routes.episode(itemId, episodeId))
                    },
                    // The author, series and narrator on an item page are links now that there
                    // is somewhere for them to lead.
                    onBrowseGroup = { kind, name ->
                        navController.navigateFromResumed(Routes.browseGroup(kind, name))
                    },
                )
            }

            composable(
                route = Routes.EPISODE,
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                    navArgument("episodeId") { type = NavType.StringType },
                ),
            ) {
                val playerViewModel: PlayerViewModel = hiltViewModel()
                EpisodeScreen(
                    onBack = { navController.popBackStack() },
                    onPlay = { itemId, episodeId ->
                        playerViewModel.play(itemId, episodeId)
                        navController.openPlayer()
                    },
                    onOpenShow = { navController.navigateFromResumed(Routes.item(it)) },
                )
            }

            composable(
                route = Routes.BROWSE,
                arguments = listOf(navArgument("kind") { type = NavType.StringType }),
            ) {
                BrowseScreen(
                    onBack = { navController.popBackStack() },
                    onOpenGroup = { kind, name ->
                        navController.navigateFromResumed(Routes.browseGroup(kind, name))
                    },
                )
            }

            composable(
                route = Routes.BROWSE_GROUP,
                arguments = listOf(
                    navArgument("kind") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType },
                ),
            ) {
                BrowseGroupScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { navController.navigateFromResumed(Routes.item(it)) },
                )
            }

            composable(Routes.SEARCH) {
                SearchScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { navController.navigateFromResumed(Routes.item(it)) },
                    onOpenEpisode = { itemId, episodeId ->
                        navController.navigateFromResumed(Routes.episode(itemId, episodeId))
                    },
                    onOpenGroup = { kind, name -> navController.navigateFromResumed(Routes.browseGroup(kind, name)) },
                    onOpenCollection = { navController.navigateFromResumed(Routes.collection(it)) },
                )
            }

            composable(Routes.ALL_EPISODES) {
                val playerViewModel: PlayerViewModel = hiltViewModel()
                AllEpisodesScreen(
                    onBack = { navController.popBackStack() },
                    onPlay = { itemId, episodeId ->
                        playerViewModel.play(itemId, episodeId)
                        navController.openPlayer()
                    },
                    onOpenEpisode = { itemId, episodeId ->
                        navController.navigateFromResumed(Routes.episode(itemId, episodeId))
                    },
                )
            }

            composable(Routes.PLAYER) {
                PlayerScreen(
                    onBack = { navController.popBackStack() },
                    // The cover and title lead to what plays (the episode, for a podcast), and
                    // the credit under them to the author or the show. See `PlayerLink`.
                    onOpenLink = { navController.navigateFromResumed(Routes.of(it)) },
                    // The same route Home's top bar opens, so the queue is one screen and
                    // not two. The player is where a listener asks "what is after this",
                    // and until now only Home could answer.
                    onOpenQueue = { navController.navigateFromResumed(Routes.QUEUE) },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onSignedOut = {
                        navController.navigate(Routes.login()) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onOpenLicenses = { navController.navigateFromResumed(Routes.LICENSES) },
                    onOpenPlaybackRecord = { navController.navigateFromResumed(Routes.PLAYBACK_RECORD) },
                    onOpenStats = { navController.navigateFromResumed(Routes.STATS) },
                    onOpenAccounts = { navController.navigateFromResumed(Routes.ACCOUNTS) },
                    onOpenFeedback = { navController.navigateFromResumed(Routes.FEEDBACK) },
                    onOpenConnection = { navController.navigateFromResumed(Routes.CONNECTION) },
                )
            }

            composable(Routes.CONNECTION) {
                ConnectionScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.COLLECTIONS) {
                CollectionsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCollection = { navController.navigateFromResumed(Routes.collection(it)) },
                )
            }

            composable(
                route = Routes.COLLECTION,
                arguments = listOf(navArgument("collectionId") { type = NavType.StringType }),
            ) {
                CollectionScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { navController.navigateFromResumed(Routes.item(it)) },
                )
            }

            composable(Routes.PLAYBACK_RECORD) {
                PlaybackRecordScreen(
                    onBack = { navController.popBackStack() },
                    onSendFeedback = { navController.navigateFromResumed(Routes.FEEDBACK) },
                )
            }

            composable(Routes.ACCOUNTS) {
                AccountsScreen(
                    onBack = { navController.popBackStack() },
                    // Adding an account reuses the sign-in screen. On success it lands on
                    // Home with the new account active and the stack cleared, which is
                    // exactly what adding an account should do.
                    onAddAccount = { navController.navigateFromResumed(Routes.login()) },
                    // The same screen, filled in for that account, with its row, mirror and
                    // downloads left in place.
                    onSignInAgain = { serverId -> navController.navigateFromResumed(Routes.login(serverId)) },
                )
            }

            composable(Routes.STATS) {
                StatsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { navController.navigateFromResumed(Routes.item(it)) },
                )
            }

            composable(Routes.FEEDBACK) {
                FeedbackScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.LICENSES) {
                LicensesScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    /*
     * The new-episode notification, landed.
     *
     * Written after the NavHost so the graph is up before this runs, and expressed as a
     * navigation onto Home rather than as a start destination of its own — which is the
     * same shape the item page has and the reason it works from cold: whatever the app was
     * doing, back from here leads to the library.
     *
     * `launchSingleTop` covers the case the batch makes likely — the same notification
     * tapped twice while the page is already open — which would otherwise stack the page on
     * itself and need two presses of back to leave.
     *
     * A tap while signed out is dropped rather than queued. There is no library to open the
     * episode from until somebody signs in, and a page that appears several minutes later
     * on top of whatever they went on to do is worse than the notification going nowhere.
     */
    LaunchedEffect(episodeTarget, start) {
        val target = episodeTarget ?: return@LaunchedEffect
        if (start == Routes.HOME) {
            val route = target.episodeId?.let { Routes.episode(target.itemId, it) }
                ?: Routes.item(target.itemId)
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
        onEpisodeHandled()
    }

    /*
     * A tap on the media notification or the widget opens the player, on top of Home, so
     * that Back reaches the library from a cold start too. It waits a moment for the
     * session to say what is loaded. With nothing loaded, or no account, the tap opens
     * the app where it was.
     *
     * A sign-in screen on top normally keeps the player out: somebody adding an account
     * is in the middle of something. The sign-in that the lapse effect pushed is the
     * exception, and only that entry, whatever lapse state is read now. A
     * downloaded book plays through a lapse, and a tap on its notification asks for that
     * book, so the player opens over the offer, and Back from the player still finds it.
     * When the player opens first, the lapse effect above waits for it to close, so the
     * player is on top in either order.
     */
    LaunchedEffect(openPlayerRequested, start) {
        if (!openPlayerRequested) return@LaunchedEffect
        if (start == Routes.HOME) {
            val loaded = withTimeoutOrNull(PLAYER_WAIT_MS) {
                playback.nowPlaying.first { it != null }
            }
            val top = navController.currentBackStackEntry
            if (loaded != null && playerMayOpenOver(top?.destination?.route, Routes.isLapseOffer(top))) {
                navController.openPlayer()
            }
        }
        onOpenPlayerHandled()
    }
}

/** How long a notification or widget tap waits for the session to report what is loaded. */
private const val PLAYER_WAIT_MS = 2_000L
