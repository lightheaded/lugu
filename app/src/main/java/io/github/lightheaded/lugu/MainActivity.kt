package io.github.lightheaded.lugu

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import io.github.lightheaded.lugu.feature.library.BrowseGroupScreen
import io.github.lightheaded.lugu.feature.library.BrowseScreen
import io.github.lightheaded.lugu.feature.library.CollectionScreen
import io.github.lightheaded.lugu.feature.library.CollectionsScreen
import io.github.lightheaded.lugu.feature.library.DownloadsScreen
import io.github.lightheaded.lugu.feature.library.HomeScreen
import io.github.lightheaded.lugu.feature.library.ItemDetailScreen
import io.github.lightheaded.lugu.feature.library.QueueScreen
import io.github.lightheaded.lugu.feature.player.MiniPlayer
import io.github.lightheaded.lugu.feature.player.PlayerScreen
import io.github.lightheaded.lugu.feature.player.PlayerViewModel
import io.github.lightheaded.lugu.feature.settings.ConnectionScreen
import io.github.lightheaded.lugu.feature.settings.LoginScreen
import io.github.lightheaded.lugu.feature.settings.SettingsScreen
import io.github.lightheaded.lugu.playback.PlaybackConnection
import io.github.lightheaded.lugu.ui.CrashPrompt
import io.github.lightheaded.lugu.ui.FeedbackScreen
import io.github.lightheaded.lugu.ui.LicensesScreen
import io.github.lightheaded.lugu.ui.LuguTheme
import io.github.lightheaded.lugu.ui.PlaybackRecordScreen
import io.github.lightheaded.lugu.ui.RequestNotificationPermission
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * The connection rather than a view model, because a spoken request has to be
     * honoured whether or not the player screen is ever composed.
     */
    @Inject lateinit var playback: PlaybackConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleSearchIntent(intent)
        setContent {
            LuguTheme {
                RequestNotificationPermission()
                LuguApp()
            }
        }
    }

    /** `singleTop`, so a second spoken request arrives here rather than in a new activity. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSearchIntent(intent)
    }

    private fun handleSearchIntent(intent: Intent?) {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = intent.getStringExtra(SearchManager.QUERY)?.takeIf { it.isNotBlank() } ?: return
        playback.playFromSearch(query)
    }
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
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val DOWNLOADS = "downloads"
    const val QUEUE = "queue"
    const val LICENSES = "licenses"
    const val PLAYBACK_RECORD = "playback-record"
    const val FEEDBACK = "feedback"
    const val CONNECTION = "connection"
    const val COLLECTIONS = "collections"
    const val COLLECTION = "collections/{collectionId}"

    /** Authors, series or narrators — the three groupings the item page links to. */
    const val BROWSE = "browse/{kind}"
    const val BROWSE_GROUP = "browse/{kind}/{name}"

    fun item(itemId: String) = "item/$itemId"

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
private fun LuguApp(startViewModel: StartupViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val startState by startViewModel.state.collectAsStateWithLifecycle()

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

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onSignedIn = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
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
                bottomContent = { MiniPlayer(onOpen = { navController.navigate(Routes.PLAYER) }) },
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
                // The author, series and narrator on an item page are links now that there
                // is somewhere for them to lead.
                onBrowseGroup = { kind, name ->
                    navController.navigate(Routes.browseGroup(kind, name))
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

        composable(Routes.FEEDBACK) {
            FeedbackScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.LICENSES) {
            LicensesScreen(onBack = { navController.popBackStack() })
        }
    }
}
