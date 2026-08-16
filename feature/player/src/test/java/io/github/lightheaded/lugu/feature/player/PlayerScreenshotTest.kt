package io.github.lightheaded.lugu.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.lightheaded.lugu.core.model.formatClock
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.core.sync.TransportButton
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Catches a silent change to the one screen that is used with the phone at arm's length.
 *
 * The player's layout is an argument about hit targets: the seek pair flanks play/pause at
 * full size because re-hearing a missed sentence is what people actually do, and the
 * chapter pair sits outside it, smaller and dimmer, because skipping chapters is
 * occasional. None of that is expressible as an assertion about text, so it is
 * photographed instead — in both themes, since this screen is mostly used in the dark.
 *
 * [PlayerScreen] itself takes a Hilt view model wrapping `PlaybackConnection`, which owns
 * a bound media session and cannot be fabricated, so what is rendered here is the screen's
 * surface with the real [PlayerActionRow] and the real time formatting in it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class PlayerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the player shows the transport, the chapter readout and the action row`() {
        capture("player_light", dark = false) { PlayerPreview(playing = true) }
    }

    @Test
    fun `the player reads correctly in the dark`() {
        capture("player_dark", dark = true) { PlayerPreview(playing = true) }
    }

    @Test
    fun `a paused player offers play rather than pause`() {
        capture("player_paused_light", dark = false) { PlayerPreview(playing = false) }
    }

    @Test
    fun `a paused player reads correctly in the dark`() {
        capture("player_paused_dark", dark = true) { PlayerPreview(playing = false) }
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

private const val DURATION_SEC = 41_400.0
private const val POSITION_SEC = 17_412.0
private val SETTINGS = PlayerSettings()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerPreview(playing: Boolean) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now playing") },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.8f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "The Lighthouse Wakes",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "James T. R. Corven",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    "Nine: The Second Keeper",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Chapter 9 of 24 · ${formatClock(612.0)} / ${formatClock(2_040.0)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
            Slider(
                value = POSITION_SEC.toFloat(),
                onValueChange = {},
                valueRange = 0f..DURATION_SEC.toFloat(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(POSITION_SEC), style = MaterialTheme.typography.labelMedium)
                Text(
                    "-${formatClock(DURATION_SEC - POSITION_SEC)}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Spacer(Modifier.height(24.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (TransportButton.PREVIOUS_CHAPTER in SETTINGS.playerButtons) {
                    IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous chapter",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FilledTonalIconButton(onClick = {}, modifier = Modifier.size(60.dp)) {
                    SeekIconPreview(SETTINGS.skipBackSec, Icons.Default.Replay10, "Back")
                }
                FilledIconButton(onClick = {}, modifier = Modifier.size(76.dp)) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(38.dp),
                    )
                }
                FilledTonalIconButton(onClick = {}, modifier = Modifier.size(60.dp)) {
                    SeekIconPreview(SETTINGS.skipForwardSec, Icons.Default.Forward30, "Forward")
                }
                if (TransportButton.NEXT_CHAPTER in SETTINGS.playerButtons) {
                    IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next chapter",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            PlayerActionRow(
                speed = SETTINGS.speed.defaultSpeed,
                sleepArmed = true,
                canBookmark = true,
                onSpeed = {},
                onSleep = {},
                onAddBookmark = {},
                onBookmarks = {},
                onHistory = {},
            )
        }
    }
}

/** The seek amount is written on the icon, so a changed default is visible in the picture. */
@Composable
private fun SeekIconPreview(
    seconds: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
) {
    Box(contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = "$description $seconds seconds", modifier = Modifier.size(32.dp))
        Text("$seconds", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
    }
}
