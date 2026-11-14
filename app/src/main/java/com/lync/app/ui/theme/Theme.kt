package com.lync.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.core.graphics.toColorInt

/**
 * The whole app is neutral (black / white / greys). The ONLY color comes from
 * the user's university: [accent] and [accent2]. Switching university re-tints
 * everything with an animation — that's the "the app takes your school's colors" effect.
 *
 * iOS port: mirror this as a `CampusPalette` struct injected with `.environment`.
 */
@Immutable
data class CampusColors(
    val accent: Color,
    val accent2: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val text: Color,
    val textMuted: Color,
    val border: Color,
    val isDark: Boolean,
)

val LocalCampusColors = staticCompositionLocalOf {
    lightPalette(Color(0xFF912338), Color(0xFFD4A12A))
}

/** Shortcut: `Campus.colors.accent` */
object Campus {
    val colors: CampusColors
        @Composable get() = LocalCampusColors.current
}

fun String.toColorOr(fallback: Color): Color =
    runCatching { Color(this.toColorInt()) }.getOrDefault(fallback)

private fun onColorFor(c: Color) = if (c.luminance() > 0.5f) Tokens.Night else Color.White

private fun lightPalette(accent: Color, accent2: Color) = CampusColors(
    accent = accent,
    accent2 = accent2,
    onAccent = onColorFor(accent),
    accentSoft = accent.copy(alpha = 0.09f),
    bg = Tokens.Paper,
    surface = Tokens.Mist,
    surfaceAlt = Tokens.Fog,
    text = Tokens.Night,
    textMuted = Tokens.Slate,
    border = Tokens.Line,
    isDark = false,
)

private fun darkPalette(accent: Color, accent2: Color) = CampusColors(
    accent = accent,
    accent2 = accent2,
    onAccent = onColorFor(accent),
    accentSoft = accent.copy(alpha = 0.2f),
    bg = Tokens.NightBg,
    surface = Tokens.NightSurface,
    surfaceAlt = Tokens.NightSurfaceAlt,
    text = Tokens.Paper,
    textMuted = Tokens.NightSlate,
    border = Tokens.NightLine,
    isDark = true,
)

@Composable
fun CampusTheme(
    accentHex: String,
    accent2Hex: String,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val anim = tween<Color>(durationMillis = 600)
    val accent by animateColorAsState(accentHex.toColorOr(Color(0xFF912338)), anim, label = "accent")
    val accent2 by animateColorAsState(accent2Hex.toColorOr(Color(0xFFD4A12A)), anim, label = "accent2")

    val palette = if (dark) darkPalette(accent, accent2) else lightPalette(accent, accent2)

    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accent, onPrimary = palette.onAccent,
            secondary = palette.accent2,
            background = palette.bg, onBackground = palette.text,
            surface = palette.bg, onSurface = palette.text,
            surfaceVariant = palette.surface, onSurfaceVariant = palette.textMuted,
            surfaceContainer = palette.surface, surfaceContainerHigh = palette.surfaceAlt,
            outline = palette.border, outlineVariant = palette.border,
        )
    } else {
        lightColorScheme(
            primary = palette.accent, onPrimary = palette.onAccent,
            secondary = palette.accent2,
            background = palette.bg, onBackground = palette.text,
            surface = palette.bg, onSurface = palette.text,
            surfaceVariant = palette.surface, onSurfaceVariant = palette.textMuted,
            surfaceContainer = palette.surface, surfaceContainerHigh = palette.surfaceAlt,
            outline = palette.border, outlineVariant = palette.border,
        )
    }

    CompositionLocalProvider(LocalCampusColors provides palette) {
        MaterialTheme(colorScheme = scheme, typography = CampusTypography, shapes = CampusShapes, content = content)
    }
}
