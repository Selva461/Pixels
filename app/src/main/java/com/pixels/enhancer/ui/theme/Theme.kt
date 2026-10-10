package com.pixels.enhancer.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Pixels' own palette instead of Material's default purple or wallpaper-derived colours: warm
 * paper and ink neutrals with one muted burnt-orange accent. A photo editor should not tint the
 * photo's surroundings, so everything except the accent is close to neutral.
 */
private object Palette {
    val Paper = Color(0xFFF3F1EC)
    val PaperRaised = Color(0xFFFAF8F4)
    val PaperSunken = Color(0xFFE4E0D8)
    val Ink = Color(0xFF1C1B19)
    val InkSoft = Color(0xFF4A4741)
    val Rule = Color(0xFF8A857C)
    val Accent = Color(0xFFA9471F)
    val AccentPale = Color(0xFFF0DCD1)
    val AccentDeep = Color(0xFF3A1406)

    // Editor greys are neutral (no warm cast) so they never bias how colour in the photo is judged.
    val Night = Color(0xFF141414)
    val NightRaised = Color(0xFF1D1D1D)
    val NightSunken = Color(0xFF292929)
    val NightRule = Color(0xFF3A3A3A)
    val Bone = Color(0xFFEDEDED)
    val BoneSoft = Color(0xFFA6A6A6)
    val AccentLight = Color(0xFFE39A72)
    val AccentMuted = Color(0xFF5A2A14)

    val Error = Color(0xFFB3261E)
    val ErrorLight = Color(0xFFF2B8B5)
}

/** Neutral grey behind photos (not pure black), so dark images keep visible edges. */
val PhotoCanvas = Color(0xFF101010)

/** Text drawn on [PhotoCanvas], in both light and dark themes. */
val OnPhotoCanvas = Color(0xFFE8E4DD)

private val LightColors = lightColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    primaryContainer = Palette.AccentPale,
    onPrimaryContainer = Palette.AccentDeep,
    secondary = Palette.InkSoft,
    onSecondary = Palette.Paper,
    secondaryContainer = Palette.PaperSunken,
    onSecondaryContainer = Palette.Ink,
    background = Palette.Paper,
    onBackground = Palette.Ink,
    surface = Palette.Paper,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.PaperSunken,
    onSurfaceVariant = Palette.InkSoft,
    surfaceContainer = Palette.PaperRaised,
    surfaceContainerHigh = Palette.PaperRaised,
    surfaceContainerHighest = Palette.PaperSunken,
    outline = Palette.Rule,
    outlineVariant = Palette.PaperSunken,
    error = Palette.Error,
)

private val DarkColors = darkColorScheme(
    primary = Palette.AccentLight,
    onPrimary = Palette.AccentDeep,
    primaryContainer = Palette.AccentMuted,
    onPrimaryContainer = Palette.AccentPale,
    secondary = Palette.BoneSoft,
    onSecondary = Palette.Night,
    secondaryContainer = Palette.NightSunken,
    onSecondaryContainer = Palette.Bone,
    background = Palette.Night,
    onBackground = Palette.Bone,
    surface = Palette.Night,
    onSurface = Palette.Bone,
    surfaceVariant = Palette.NightSunken,
    onSurfaceVariant = Palette.BoneSoft,
    surfaceContainer = Palette.NightRaised,
    surfaceContainerHigh = Palette.NightRaised,
    surfaceContainerHighest = Palette.NightSunken,
    outline = Palette.Rule,
    outlineVariant = Palette.NightRule,
    error = Palette.ErrorLight,
)

/** Tight corners: controls read as tools, not bubbles. */
private val PixelsShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

/** Serif for the wordmark and headings gives the app its own voice; UI text stays in the system sans. */
private val PixelsTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Serif),
    )
}

/**
 * The app is dark like a darkroom or a pro editor: photos are judged against a neutral dark
 * surround, whatever the system theme. The light scheme is kept for previews and tests.
 */
@Composable
fun PixelsTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = PixelsShapes,
        typography = PixelsTypography,
        content = content,
    )
}
