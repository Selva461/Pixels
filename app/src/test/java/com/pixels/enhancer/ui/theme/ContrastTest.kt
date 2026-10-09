package com.pixels.enhancer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.2 contrast for every colour pair the app draws (FEATURE_SPEC A-06): 4.5:1 for text,
 * 3:1 for icons, borders, slider tracks, the slider thumb and the curve lines. Both schemes are
 * checked, so the light one is ready if it ships (U-08).
 */
class ContrastTest {

    private class Pair(val use: String, val foreground: Color, val background: Color, val minimum: Double)

    /** WCAG relative luminance of an opaque sRGB colour. */
    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double = if (channel <= 0.04045f) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }

    /** [foreground] painted over [background], as the screen shows it (sRGB blending, like Android). */
    private fun over(foreground: Color, background: Color): Color {
        val a = foreground.alpha
        return Color(
            red = foreground.red * a + background.red * (1 - a),
            green = foreground.green * a + background.green * (1 - a),
            blue = foreground.blue * a + background.blue * (1 - a),
        )
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val front = luminance(over(foreground, background))
        val back = luminance(background)
        return (max(front, back) + 0.05) / (min(front, back) + 0.05)
    }

    private fun pairs(s: ColorScheme) = listOf(
        Pair("text", s.onSurface, s.surface, TEXT),
        Pair("text on panels", s.onSurface, s.surfaceContainer, TEXT),
        Pair("text on sunken areas", s.onSurface, s.surfaceContainerHighest, TEXT),
        Pair("text on the background", s.onBackground, s.background, TEXT),
        Pair("secondary text", s.onSurfaceVariant, s.surface, TEXT),
        Pair("secondary text on panels", s.onSurfaceVariant, s.surfaceContainer, TEXT),
        Pair("secondary text on sunken areas", s.onSurfaceVariant, s.surfaceContainerHighest, TEXT),
        Pair("text buttons and values", s.primary, s.surface, TEXT),
        Pair("text buttons and values on panels", s.primary, s.surfaceContainer, TEXT),
        Pair("filled button label", s.onPrimary, s.primary, TEXT),
        Pair("accent container text", s.onPrimaryContainer, s.primaryContainer, TEXT),
        Pair("secondary container text", s.onSecondaryContainer, s.secondaryContainer, TEXT),
        Pair("selected chip label", s.surface, s.onSurface, TEXT),
        Pair("error text", s.error, s.surface, TEXT),
        Pair("error text on panels", s.error, s.surfaceContainer, TEXT),
        Pair("borders and slider rails", s.outline, s.surface, NON_TEXT),
        Pair("borders and slider rails on panels", s.outline, s.surfaceContainer, NON_TEXT),
        Pair("slider fill and focus", s.primary, s.surfaceContainer, NON_TEXT),
        Pair("slider thumb and icons", s.onSurface, s.surfaceContainer, NON_TEXT),
    )

    private fun failures(pairs: List<Pair>): List<String> = pairs.mapNotNull { pair ->
        val ratio = contrast(pair.foreground, pair.background)
        if (ratio + 1e-3 >= pair.minimum) null else String.format(Locale.ROOT, "%s: %.2f:1 (needs %.1f:1)", pair.use, ratio, pair.minimum)
    }

    @Test
    fun darkSchemeMeetsWcagContrast() {
        val failed = failures(pairs(pixelsColorScheme(darkTheme = true)))
        assertTrue("dark scheme: $failed", failed.isEmpty())
    }

    @Test
    fun lightSchemeMeetsWcagContrast() {
        val failed = failures(pairs(pixelsColorScheme(darkTheme = false)))
        assertTrue("light scheme: $failed", failed.isEmpty())
    }

    @Test
    fun labelsOnThePhotoStayReadableOverAnyPhoto() {
        val failed = failures(
            listOf(
                Pair("text on the photo surround", OnPhotoCanvas, PhotoCanvas, TEXT),
                Pair("photo label over a white area", OnPhotoCanvas, over(PhotoLabelBacking, Color.White), TEXT),
                Pair("photo label over a black area", OnPhotoCanvas, over(PhotoLabelBacking, Color.Black), TEXT),
            ),
        )
        assertTrue(failed.toString(), failed.isEmpty())
    }

    @Test
    fun curveLinesStandOutFromPanelAndHistogram() {
        // The editor is always dark; the curve is drawn on the panel and over the histogram bars.
        val dark = pixelsColorScheme(darkTheme = true)
        val failed = failures(
            listOf(CurveInk.Red, CurveInk.Green, CurveInk.Blue, dark.onSurface).flatMap { ink ->
                listOf(
                    Pair("curve line $ink on the panel", ink, dark.surfaceContainer, NON_TEXT),
                    Pair("curve line $ink over the histogram", ink, dark.surfaceVariant, NON_TEXT),
                )
            },
        )
        assertTrue(failed.toString(), failed.isEmpty())
    }

    private companion object {
        const val TEXT = 4.5
        const val NON_TEXT = 3.0
    }
}
