package com.stratum.feature.studio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * CHAPTER 13: PRESENTATION THEME - TACTILE RESPONSIVE X ANCIENT IGBO ART
 *
 * Palette Philosophy:
 * - Igbo-Ukwu Bronze Patina (#CD7F32, #A75D28)
 * - Terracotta Earth Clay (#D95B30, #93280B)
 * - Nri Sacred Ivory (#FAF6EE, #F2EADB)
 * - Nsibidi Charcoal Black (#121714, #1B221E)
 * - Amadioha Electric Lightning (#00E5FF)
 * - Anyanwu Solar Gold (#FFB300)
 * - Sacred Vitality Crimson Globe (#D32F2F)
 * - Amadioha Spirit Blue Globe (#0288D1)
 */

object IgboArtColors {
    val BronzePrimary = Color(0xFFC6772E)
    val BronzeDark = Color(0xFF884914)
    val BronzeLight = Color(0xFFE4A25F)
    
    val Terracotta = Color(0xFFD95B30)
    val TerracottaDark = Color(0xFF93280B)
    
    val SacredIvory = Color(0xFFFAF6EE)
    val SacredSand = Color(0xFFF1E8D5)
    
    val NsibidiNight = Color(0xFF111714)
    val NsibidiSurface = Color(0xFF1B231F)
    val NsibidiCard = Color(0xFF242F2A)
    
    val AmadiohaCyan = Color(0xFF00E5FF)
    val AnyanwuGold = Color(0xFFFFB300)
    
    val LifeGlobeRed = Color(0xFFE53935)
    val SpiritGlobeBlue = Color(0xFF00B0FF)
    
    val RarityNormal = Color(0xFFCFD8DC)
    val RarityMagic = Color(0xFF42A5F5)
    val RarityRare = Color(0xFFFFCA28)
    val RarityLegendary = Color(0xFFFF7043)
    val RarityRelic = Color(0xFF26A69A)
}

val IgboDarkColorScheme = darkColorScheme(
    primary = IgboArtColors.BronzePrimary,
    onPrimary = Color.White,
    primaryContainer = IgboArtColors.BronzeDark,
    onPrimaryContainer = IgboArtColors.SacredIvory,
    secondary = IgboArtColors.AnyanwuGold,
    onSecondary = IgboArtColors.NsibidiNight,
    secondaryContainer = Color(0xFF422C0A),
    onSecondaryContainer = IgboArtColors.AnyanwuGold,
    tertiary = IgboArtColors.AmadiohaCyan,
    onTertiary = IgboArtColors.NsibidiNight,
    background = IgboArtColors.NsibidiNight,
    onBackground = IgboArtColors.SacredIvory,
    surface = IgboArtColors.NsibidiSurface,
    onSurface = IgboArtColors.SacredIvory,
    surfaceVariant = IgboArtColors.NsibidiCard,
    onSurfaceVariant = Color(0xFFD0D7D3),
    error = IgboArtColors.LifeGlobeRed,
    onError = Color.White
)

val IgboLightColorScheme = lightColorScheme(
    primary = IgboArtColors.Terracotta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCE),
    onPrimaryContainer = IgboArtColors.TerracottaDark,
    secondary = IgboArtColors.BronzePrimary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0B2),
    onSecondaryContainer = IgboArtColors.BronzeDark,
    tertiary = Color(0xFF00838F),
    onTertiary = Color.White,
    background = IgboArtColors.SacredIvory,
    onBackground = IgboArtColors.NsibidiNight,
    surface = Color.White,
    onSurface = IgboArtColors.NsibidiNight,
    surfaceVariant = IgboArtColors.SacredSand,
    onSurfaceVariant = Color(0xFF463E32),
    error = IgboArtColors.LifeGlobeRed,
    onError = Color.White
)

val IgboArtTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.15.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 0.5.sp
    )
)

@Composable
fun IgboArpgTheme(
    darkTheme: Boolean = true, // Default to immersive dark ancestral atmosphere
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) IgboDarkColorScheme else IgboLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = IgboArtTypography,
        content = content
    )
}
