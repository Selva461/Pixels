package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.smoothstep
import com.pixels.enhancer.domain.planning.ColorGrading
import com.pixels.enhancer.domain.planning.GradeWheel
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import com.pixels.enhancer.domain.processing.ops.ChromaOps
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Black-and-white treatment and colour grading (manual only). Each pixel's luma decides how much
 * of the shadow, midtone and highlight wheels it receives; a wheel adds a tint in the luma/chroma
 * plane (luma unchanged) and an optional luminance shift.
 */
class ColorGradingStage : ProcessingStage {
    override val id = StageIds.COLOR_GRADING
    override val displayName = "Color Grading"

    override fun isEnabled(context: ProcessingContext) = !context.plan.colorGrading.isNeutral

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val grading = context.plan.colorGrading.clamped()
        val shadows = Tint.of(grading.shadows)
        val midtones = Tint.of(grading.midtones)
        val highlights = Tint.of(grading.highlights)
        val global = Tint.of(grading.global)
        val width = MIN_OVERLAP + grading.blending * OVERLAP_RANGE
        val shift = grading.balance * BALANCE_SHIFT
        val shadowEdge = SHADOW_EDGE - shift
        val highlightEdge = HIGHLIGHT_EDGE - shift
        val pixels = input.pixels
        for (i in pixels.indices) {
            val color = pixels[i]
            val red = Argb.red(color) * CHANNEL_SCALE
            val green = Argb.green(color) * CHANNEL_SCALE
            val blue = Argb.blue(color) * CHANNEL_SCALE
            val luma = BT601_RED * red + BT601_GREEN * green + BT601_BLUE * blue
            var cb = if (grading.monochrome) 0f else (blue - luma) * CB_SCALE
            var cr = if (grading.monochrome) 0f else (red - luma) * CR_SCALE
            val ws = 1f - smoothstep(shadowEdge - width / 2f, shadowEdge + width / 2f, luma)
            val wh = smoothstep(highlightEdge - width / 2f, highlightEdge + width / 2f, luma)
            val wm = max(0f, 1f - ws - wh)
            var newLuma = luma
            for ((tint, weight) in arrayOf(shadows to ws, midtones to wm, highlights to wh, global to 1f)) {
                if (tint == null || weight <= 0f) continue
                cb += tint.cb * weight
                cr += tint.cr * weight
                newLuma += tint.luminance * weight
            }
            newLuma = newLuma.coerceIn(0f, 1f)
            val newRed = newLuma + cr / CR_SCALE
            val newBlue = newLuma + cb / CB_SCALE
            val newGreen = (newLuma - BT601_RED * newRed - BT601_BLUE * newBlue) / BT601_GREEN
            pixels[i] = ChromaOps.fitToGamut(Argb.alpha(color), newRed, newGreen, newBlue, newLuma)
        }
        return input
    }

    /** A wheel as a chroma offset (direction of its hue, length by saturation) and luma shift. */
    private class Tint(val cb: Float, val cr: Float, val luminance: Float) {
        companion object {
            fun of(wheel: GradeWheel): Tint? {
                if (wheel.isNeutral) return null
                val (r, g, b) = hueToRgb(wheel.hue)
                val luma = BT601_RED * r + BT601_GREEN * g + BT601_BLUE * b
                val cb = (b - luma) * CB_SCALE
                val cr = (r - luma) * CR_SCALE
                val length = sqrt(cb * cb + cr * cr).coerceAtLeast(MIN_LENGTH)
                val amount = wheel.saturation * MAX_TINT_CHROMA
                return Tint(cb / length * amount, cr / length * amount, wheel.luminance * MAX_LUMINANCE)
            }

            private fun hueToRgb(hue: Float): Triple<Float, Float, Float> {
                val h = (hue / DEGREES_PER_SECTOR) % SECTORS
                val x = 1f - kotlin.math.abs(h % 2f - 1f)
                return when (h.toInt()) {
                    0 -> Triple(1f, x, 0f)
                    1 -> Triple(x, 1f, 0f)
                    2 -> Triple(0f, 1f, x)
                    3 -> Triple(0f, x, 1f)
                    4 -> Triple(x, 0f, 1f)
                    else -> Triple(1f, 0f, x)
                }
            }
        }
    }

    private companion object {
        const val CHANNEL_SCALE = 1f / Argb.CHANNEL_MAX
        const val BT601_RED = 0.299f
        const val BT601_GREEN = 0.587f
        const val BT601_BLUE = 0.114f
        const val CB_SCALE = 0.564f
        const val CR_SCALE = 0.713f
        const val SHADOW_EDGE = 0.33f
        const val HIGHLIGHT_EDGE = 0.67f
        const val MIN_OVERLAP = 0.1f
        const val OVERLAP_RANGE = 0.4f
        const val BALANCE_SHIFT = 0.2f

        /** Chroma added at full wheel saturation — a clear but not garish tint. */
        const val MAX_TINT_CHROMA = 0.1f
        const val MAX_LUMINANCE = 0.15f
        const val MIN_LENGTH = 1e-4f
        const val DEGREES_PER_SECTOR = 60f
        const val SECTORS = 6f
    }
}
