package io.github.lightheaded.lugu

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
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
import io.github.lightheaded.lugu.feature.library.DownloadsScreen
import io.github.lightheaded.lugu.feature.library.HomeScreen
import io.github.lightheaded.lugu.feature.library.ItemDetailScreen
import io.github.lightheaded.lugu.feature.library.QueueScreen
import io.github.lightheaded.lugu.feature.player.MiniPlayer
import io.github.lightheaded.lugu.feature.player.PlayerScreen
import io.github.lightheaded.lugu.feature.player.PlayerViewModel
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

    fun item(itemId: String) = "item/$itemId"
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
