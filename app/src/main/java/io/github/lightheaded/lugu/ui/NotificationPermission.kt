package io.github.lightheaded.lugu.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Asks for notification permission once, the first time [ask] turns true.
 *
 * Without it on Android 13+ the media notification never appears, which takes the
 * lock-screen and headset transport controls with it — playback still works but the
 * app looks broken. The caller turns [ask] on when playback first starts: then the
 * request is plainly about the playback controls, which on the sign-in screen it was not.
 * Asked once per run at most, so a refusal is not met with the same question at every play.
 */
@Composable
fun RequestNotificationPermission(ask: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denied is survivable: playback continues without a notification. */ }

    LaunchedEffect(ask) {
        if (!ask || asked) return@LaunchedEffect
        asked = true
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
