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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import io.github.lightheaded.lugu.feature.library.BrowseGroupScreen
import io.github.lightheaded.lugu.feature.library.BrowseScreen
import io.github.lightheaded.lugu.feature.library.CollectionScreen
import io.github.lightheaded.lugu.feature.library.CollectionsScreen
import io.github.lightheaded.lugu.feature.library.DownloadsScreen
import io.github.lightheaded.lugu.feature.library.EpisodeScreen
import io.github.lightheaded.lugu.feature.library.HomeScreen
import io.github.lightheaded.lugu.feature.library.ItemDetailScreen
import io.github.lightheaded.lugu.feature.library.QueueScreen
import io.github.lightheaded.lugu.feature.player.MiniPlayer
import io.github.lightheaded.lugu.feature.player.PlayerScreen
import io.github.lightheaded.lugu.feature.player.PlayerViewModel
import io.github.lightheaded.lugu.feature.settings.AccountsScreen
import io.github.lightheaded.lugu.feature.settings.ConnectionScreen
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
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        handleOidcIntent(intent)
        setContent {
            LuguTheme {
                RequestNotificationPermission()
                LuguApp(
                    playback = playback,
                    pendingEpisode = pendingEpisode,
                    onEpisodeHandled = { pendingEpisode.value = null },
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

/** One episode, named by the two ids a route needs. */
internal data class EpisodeTarget(val itemId: String, val episodeId: String)

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
        ?.takeIf { it.isNotBlank() } ?: return null
    return EpisodeTarget(itemId, episodeId)
}

private object Routes {
    const val LOGIN = "login"

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
}

@Composable
private fun LuguApp(
    playback: PlaybackConnection,
    pendingEpisode: StateFlow<EpisodeTarget?>,
    onEpisodeHandled: () -> Unit,
    pendingOidcRedirect: MutableStateFlow<String?>,
    onOidcHandled: () -> Unit,
    startViewModel: StartupViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val startState by startViewModel.state.collectAsStateWithLifecycle()
    val episodeTarget by pendingEpisode.collectAsStateWithLifecycle()
    val oidcRedirect by pendingOidcRedirect.collectAsStateWithLifecycle()

    // Wait for the signed-in check before choosing a start destination, so a returning
    // user never sees the login screen flash past.
    val start = when (startState) {
        StartupState.Checking -> return
        StartupState.SignedIn -> Routes.HOME
        StartupState.SignedOut -> Routes.LOGIN
    }

    // Offered once per crash, above whatever is on screen: the moment someone can say
    // what they were doing is the moment right after it broke.
    CrashPrompt(onOpenFeedback = { navController.navigate(Routes.FEEDBACK) })

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
    val pendingJump by playback.pendingJump.collectAsStateWithLifecycle()
    val playerSettings by playback.settings.collectAsStateWithLifecycle(initialValue = PlayerSettings())
    val noticeMillis = playerSettings.noticeSeconds.coerceAtLeast(1) * 1000L

    LaunchedEffect(pendingJump, noticeMillis) {
        pendingJump?.let { pending ->
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

    val showShellMiniPlayer = currentRoute != null &&
        !routeOwnsItsBottom &&
        currentRoute != Routes.PLAYER &&
        currentRoute != Routes.LOGIN

    Scaffold(
        // Bottom-anchored, like every other `SnackbarHost` in this codebase — Material3
        // places it above `bottomBar` automatically, so it never sits over the mini
        // player, and it is nowhere near the fixed top controls (chips, browse links)
        // the Compose overlay rule in CLAUDE.md is about.
        snackbarHost = { SnackbarHost(shellSnackbarHostState) },
        bottomBar = {
            // MiniPlayer itself renders nothing when there is nothing to play, so no
            // separate playable check is needed here — with no floor to draw either it
            // emits no layout node at all, and the padding below then falls back to the
            // system bar inset alone.
            if (showShellMiniPlayer) {
                MiniPlayer(onOpen = { navController.navigate(Routes.PLAYER) })
            }
        },
    ) { shellPadding ->
        NavHost(
            navController = navController,
            startDestination = start,
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
            composable(Routes.LOGIN) {
                // The redirect is handed to this screen's own view model, because the
                // attempt it has to be matched against belongs to the sign-in in progress.
                // Read here rather than in the activity so it reaches the same view model
                // instance that started the sign-in.
                LoginScreen(
                    onSignedIn = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
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
                    onOpenItem = { navController.navigate(Routes.item(it)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) },
                    onOpenQueue = { navController.navigate(Routes.QUEUE) },
                    onBrowse = { kind -> navController.navigate(Routes.browse(kind)) },
                    onOpenCollections = { navController.navigate(Routes.COLLECTIONS) },
                    // A shelf tap on something already in progress means "carry on", so it
                    // plays rather than opening a page and asking again.
                    onPlay = { itemId, episodeId ->
                        playerViewModel.play(itemId, episodeId)
                        navController.navigate(Routes.PLAYER)
                    },
                    // The same call as the shell's own bottom bar above, with Home's tab
                    // bar handed in as the floor under the mini player.
                    bottomBar = { tabBar ->
                        MiniPlayer(
                            onOpen = { navController.navigate(Routes.PLAYER) },
                            floor = tabBar,
                        )
                    },
                )
            }

            composable(Routes.DOWNLOADS) {
                DownloadsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { navController.navigate(Routes.item(it)) },
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
                        navController.navigate(Routes.PLAYER)
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
                        navController.navigate(Routes.PLAYER)
                    },
                    // A tap on an episode reads it; the row's own play button plays it.
                    onOpenEpisode = { itemId, episodeId ->
                        navController.navigate(Routes.episode(itemId, episodeId))
                    },
                    // The author, series and narrator on an item page are links now that there
                    // is somewhere for them to lead.
                    onBrowseGroup = { kind, name ->
                        navController.navigate(Routes.browseGroup(kind, name))
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
                        navController.navigate(Routes.PLAYER)
                    },
                )
            }

            composable(
                route = Routes.BROWSE,
                arguments = listOf(navArgument("kind") { type = NavType.StringType }),
            ) {
                BrowseScreen(
                    onBack = { navController.popBackStack() },
                    onOpenGroup = { kind, name ->
                        navController.navigate(Routes.browseGroup(kind, name))
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
                    onOpenItem = { navController.navigate(Routes.item(it)) },
                )
            }

            composable(Routes.PLAYER) {
                PlayerScreen(
                    onBack = { navController.popBackStack() },
                    // Consistent navigation: the title is a link to the item wherever it appears.
                    onOpenItem = { navController.navigate(Routes.item(it)) },
                    // The same route Home's top bar opens, so the queue is one screen and
                    // not two. The player is where a listener asks "what is after this",
                    // and until now only Home could answer.
                    onOpenQueue = { navController.navigate(Routes.QUEUE) },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onSignedOut = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onOpenLicenses = { navController.navigate(Routes.LICENSES) },
                    onOpenPlaybackRecord = { navController.navigate(Routes.PLAYBACK_RECORD) },
                    onOpenStats = { navController.navigate(Routes.STATS) },
                    onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                    onOpenFeedback = { navController.navigate(Routes.FEEDBACK) },
                    onOpenConnection = { navController.navigate(Routes.CONNECTION) },
                )
            }

            composable(Routes.CONNECTION) {
                ConnectionScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.COLLECTIONS) {
                CollectionsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCollection = { navController.navigate(Routes.collection(it)) },
                )
            }

            composable(
                route = Routes.COLLECTION,
                arguments = listOf(navArgument("collectionId") { type = NavType.StringType }),
            ) {
                CollectionScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { navController.navigate(Routes.item(it)) },
                )
            }

            composable(Routes.PLAYBACK_RECORD) {
                PlaybackRecordScreen(
                    onBack = { navController.popBackStack() },
                    onSendFeedback = { navController.navigate(Routes.FEEDBACK) },
                )
            }

            composable(Routes.ACCOUNTS) {
                AccountsScreen(
                    onBack = { navController.popBackStack() },
                    // Adding an account reuses the sign-in screen. On success it lands on
                    // Home with the new account active and the stack cleared, which is
                    // exactly what adding an account should do.
                    onAddAccount = { navController.navigate(Routes.LOGIN) },
                )
            }

            composable(Routes.STATS) {
                StatsScreen(onBack = { navController.popBackStack() })
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
            navController.navigate(Routes.episode(target.itemId, target.episodeId)) {
                launchSingleTop = true
            }
        }
        onEpisodeHandled()
    }
}
