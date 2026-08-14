package io.github.lightheaded.lugu

import android.os.Bundle
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
import io.github.lightheaded.lugu.feature.library.ItemDetailScreen
import io.github.lightheaded.lugu.feature.library.LibraryScreen
import io.github.lightheaded.lugu.feature.player.MiniPlayer
import io.github.lightheaded.lugu.feature.player.PlayerScreen
import io.github.lightheaded.lugu.feature.player.PlayerViewModel
import io.github.lightheaded.lugu.feature.settings.LoginScreen
import io.github.lightheaded.lugu.ui.LuguTheme
import io.github.lightheaded.lugu.ui.RequestNotificationPermission

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LuguTheme {
                RequestNotificationPermission()
                LuguApp()
            }
        }
    }
}

private object Routes {
    const val LOGIN = "login"
    const val LIBRARY = "library"
    const val ITEM = "item/{itemId}"
    const val PLAYER = "player"

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
        StartupState.SignedIn -> Routes.LIBRARY
        StartupState.SignedOut -> Routes.LOGIN
    }

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onSignedIn = {
                    navController.navigate(Routes.LIBRARY) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                devServerUrl = BuildConfig.DEV_SERVER_URL,
                devUsername = BuildConfig.DEV_USER,
                devPassword = BuildConfig.DEV_PASS,
            )
        }

        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenItem = { navController.navigate(Routes.item(it)) },
                bottomContent = { MiniPlayer(onOpen = { navController.navigate(Routes.PLAYER) }) },
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
            PlayerScreen(onBack = { navController.popBackStack() })
        }
    }
}
