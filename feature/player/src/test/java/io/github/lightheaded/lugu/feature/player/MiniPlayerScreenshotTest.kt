package io.github.lightheaded.lugu.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Catches the mini player merging back into the tab bar.
 *
 * The two sit one on top of the other at the bottom of the shell, and for a while they
 * were painted the same colour, so they read as a single slab with a play button on it.
 * The fix is a tonal step and a hairline, and neither is expressible as an assertion about
 * text — the only way to know it still reads as two controls is to look at it. Both themes
 * are photographed because a tonal step is the part of the answer that a palette can flatten,
 * and it flattens in one theme at a time.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class MiniPlayerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the mini player is separate from the tab bar beneath it`() {
        capture("mini_player_light", dark = false) { BottomOfTheShell() }
    }

    @Test
    fun `the mini player is separate from the tab bar in the dark`() {
        capture("mini_player_dark", dark = true) { BottomOfTheShell() }
    }

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { ScreenshotTheme(dark = dark, content = content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }
}

/**
 * lugu's palette without dynamic colour.
 *
 * The app prefers the wallpaper's colours from Android 12 onwards, which are by definition
 * not the same on two phones and so cannot be a baseline. These are the colours lugu falls
 * back to, which is what runs below Android 12 and wherever dynamic colour is off.
 */
@Composable
private fun ScreenshotTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) {
        darkColorScheme(primary = Color(0xFFEDC08B), secondary = Color(0xFFD6C3AE))
    } else {
        lightColorScheme(primary = Color(0xFF7A5A36), secondary = Color(0xFF6B5D4D))
    }
    MaterialTheme(colorScheme = colors) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

/** The mini player sitting on the tab bar, which is the only place it is ever drawn. */
@Composable
private fun BottomOfTheShell() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        MiniPlayerBar(
            title = "The Lighthouse Wakes",
            subtitle = "Nine: The Second Keeper",
            coverUrl = null,
            progress = 0.42f,
            isPlaying = true,
            onOpen = {},
            onPlayPause = {},
        )
        NavigationBar {
            NavigationBarItem(
                selected = true,
                onClick = {},
                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                label = { Text("Home") },
            )
            NavigationBarItem(
                selected = false,
                onClick = {},
                icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
                label = { Text("Library") },
            )
        }
    }
}
