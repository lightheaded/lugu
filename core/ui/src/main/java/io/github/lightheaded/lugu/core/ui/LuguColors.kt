package io.github.lightheaded.lugu.core.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * The warm brand scheme, every role set.
 *
 * With only primary and secondary set, every surface, container and tertiary role fell
 * back to Material's baseline purple, next to a brown primary. The roles are Material 3
 * tones of four palettes on the seed's hue: primary (the seed, tone 40 light and 80 dark),
 * secondary (same hue, low chroma), tertiary (hue turned 60 degrees, an olive) and a warm
 * neutral taken from the launch background. Every on-colour has at least 4.5:1 on its base.
 */

val LuguLightColors = lightColorScheme(
    primary = Color(0xFF7A5A36),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDBB),
    onPrimaryContainer = Color(0xFF281800),
    inversePrimary = Color(0xFFEDC08B),
    secondary = Color(0xFF6B5C4C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3DFCD),
    onSecondaryContainer = Color(0xFF261909),
    tertiary = Color(0xFF4E6540),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1EAC0),
    onTertiaryContainer = Color(0xFF111F00),
    background = Color(0xFFFFF8F3),
    onBackground = Color(0xFF211A16),
    surface = Color(0xFFFFF8F3),
    onSurface = Color(0xFF211A16),
    surfaceVariant = Color(0xFFF1DFD3),
    onSurfaceVariant = Color(0xFF52443B),
    surfaceTint = Color(0xFF7A5A36),
    inverseSurface = Color(0xFF362F2A),
    inverseOnSurface = Color(0xFFF9EFE8),
    error = Color(0xFFAF2D31),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF3E0400),
    outline = Color(0xFF84746A),
    outlineVariant = Color(0xFFD5C3B8),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F3),
    surfaceDim = Color(0xFFE2D8D2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCF2EB),
    surfaceContainer = Color(0xFFF6ECE5),
    surfaceContainerHigh = Color(0xFFF0E6E0),
    surfaceContainerHighest = Color(0xFFEBE1DA),
)

val LuguDarkColors = darkColorScheme(
    primary = Color(0xFFEDC08B),
    onPrimary = Color(0xFF452B07),
    primaryContainer = Color(0xFF5E411E),
    onPrimaryContainer = Color(0xFFFFDDBB),
    inversePrimary = Color(0xFF7A5A36),
    secondary = Color(0xFFD6C3B1),
    onSecondary = Color(0xFF3B2E20),
    secondaryContainer = Color(0xFF534435),
    onSecondaryContainer = Color(0xFFF3DFCD),
    tertiary = Color(0xFFB5CEA4),
    onTertiary = Color(0xFF203615),
    tertiaryContainer = Color(0xFF374D2A),
    onTertiaryContainer = Color(0xFFD1EAC0),
    background = Color(0xFF1A120B),
    onBackground = Color(0xFFEBE1DA),
    surface = Color(0xFF1A120B),
    onSurface = Color(0xFFEBE1DA),
    surfaceVariant = Color(0xFF52443B),
    onSurfaceVariant = Color(0xFFD5C3B8),
    surfaceTint = Color(0xFFEDC08B),
    inverseSurface = Color(0xFFEBE1DA),
    inverseOnSurface = Color(0xFF362F2A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF68000F),
    errorContainer = Color(0xFF91071B),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF9E8E83),
    outlineVariant = Color(0xFF52443B),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3F3833),
    surfaceDim = Color(0xFF1A120B),
    surfaceContainerLowest = Color(0xFF160C04),
    surfaceContainerLow = Color(0xFF211A16),
    surfaceContainer = Color(0xFF251E1A),
    surfaceContainerHigh = Color(0xFF302924),
    surfaceContainerHighest = Color(0xFF3B332E),
)
