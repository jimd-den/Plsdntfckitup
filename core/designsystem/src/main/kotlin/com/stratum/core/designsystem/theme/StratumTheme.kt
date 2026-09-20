package com.stratum.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.stratum.core.domain.content.PackPalette

/**
 * The app's colours, derived from whichever content pack is loaded.
 *
 * The interface has no palette of its own. Swapping the pack restyles the HUD,
 * the panels and the type colour along with the terrain, because they all read
 * the same seven values.
 */
@Immutable
data class StratumColors(
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val ink: Color,
    val inkMuted: Color,
    val accent: Color,
    val accentAlt: Color,
    val danger: Color,
    val bevel: Color,
    val hairline: Color,
) {
    companion object {
        fun from(palette: PackPalette): StratumColors {
            val surface = Color(palette.surface)
            val raised = Color(palette.surfaceRaised)
            val ink = Color(palette.ink)
            val accent = Color(palette.accent)
            return StratumColors(
                surface = surface,
                surfaceRaised = raised,
                // Sunken wells are the surface pushed further down, not a new hue,
                // so depth stays readable whatever palette a pack supplies.
                surfaceSunken = surface.darkenBy(0.35f),
                ink = ink,
                inkMuted = Color(palette.inkMuted),
                accent = accent,
                accentAlt = Color(palette.accentAlt),
                danger = Color(palette.danger),
                bevel = ink.copy(alpha = 0.10f),
                hairline = ink.copy(alpha = 0.16f),
            )
        }
    }
}

private fun Color.darkenBy(fraction: Float): Color = Color(
    red = red * (1f - fraction),
    green = green * (1f - fraction),
    blue = blue * (1f - fraction),
    alpha = alpha,
)

/**
 * Type is condensed and tightly tracked at display sizes, generous at reading
 * sizes. Labels are spaced out and uppercase so a two-word control reads as a
 * control rather than as prose.
 */
val StratumTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.8.sp,
    ),
)

val LocalStratumColors = staticCompositionLocalOf {
    StratumColors.from(PackPalette())
}

/**
 * Wraps Material so existing Material components still work and pick up the
 * pack's colours, while [StratumColors] carries the tokens Material has no slot
 * for -- bevels, hairlines and sunken wells.
 */
@Composable
fun StratumTheme(
    palette: PackPalette = PackPalette(),
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = remember(palette) { StratumColors.from(palette) }

    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.surface,
            secondary = colors.accentAlt,
            onSecondary = colors.surface,
            background = colors.surface,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.inkMuted,
            error = colors.danger,
            outline = colors.hairline,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.ink,
            secondary = colors.accentAlt,
            background = colors.ink,
            onBackground = colors.surface,
            surface = colors.ink,
            onSurface = colors.surface,
            error = colors.danger,
        )
    }

    CompositionLocalProvider(LocalStratumColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = StratumTypography,
            shapes = Shapes(
                extraSmall = Cut.tiny,
                small = Cut.small,
                medium = Cut.medium,
                large = Cut.large,
                extraLarge = Cut.large,
            ),
            content = content,
        )
    }
}

/** Shorthand for the pack-derived tokens Material does not carry. */
object StratumTheme {
    val colors: StratumColors
        @Composable get() = LocalStratumColors.current
}
