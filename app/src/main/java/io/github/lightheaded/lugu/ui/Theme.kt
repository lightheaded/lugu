package io.github.lightheaded.lugu.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.lightheaded.lugu.core.ui.LuguDarkColors
import io.github.lightheaded.lugu.core.ui.LuguLightColors

/**
 * [dynamicColor] picks the wallpaper's scheme on Android 12 and later, as today. False
 * gives the brand scheme on every version, so that a setting can offer it.
 */
@Composable
fun LuguTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> LuguDarkColors
        else -> LuguLightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
