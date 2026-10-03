package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.planning.ColorMixer
import com.pixels.enhancer.domain.planning.HueBand
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import com.pixels.enhancer.domain.processing.ops.ChromaOps
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * HSL colour mixer (manual only). Each pixel's hue picks a blend of the two nearest bands, so
 * adjustments fade smoothly between colours. Hue is rotated in a luma-preserving chroma plane,
 * and luminance shifts are scaled by colourfulness so greys never move.
 */
class ColorMixerStage : ProcessingStage {
    override val id = StageIds.COLOR_MIXER
    override val displayName = "Color Mixer"

    override fun isEnabled(context: ProcessingContext) = !context.plan.colorMixer.isNeutral

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val table = HueTable.build(context.plan.colorMixer)
        val pixels = input.pixels
        for (index in pixels.indices) pixels[index] = apply(pixels[index], table)
        return input
    }

    private fun apply(color: Int, table: HueTable): Int {
        val red = Argb.red(color) * CHANNEL_SCALE
        val green = Argb.green(color) * CHANNEL_SCALE
        val blue = Argb.blue(color) * CHANNEL_SCALE
        val highest = max(red, max(green, blue))
        val lowest = min(red, min(green, blue))
        val chroma = highest - lowest
        if (chroma < MIN_CHROMA) return color
        val degree = hueDegrees(red, green, blue, highest, chroma).toInt().coerceIn(0, DEGREES - 1)

        // BT.601 luma/chroma: a rotation of (cb, cr) changes hue and keeps luma.
        val luma = BT601_RED * red + BT601_GREEN * green + BT601_BLUE * blue
        var cb = (blue - luma) * CB_SCALE
        var cr = (red - luma) * CR_SCALE
        val rotatedCb = cb * table.cos[degree] - cr * table.sin[degree]
        val rotatedCr = cb * table.sin[degree] + cr * table.cos[degree]
        cb = rotatedCb * table.saturation[degree]
        cr = rotatedCr * table.saturation[degree]
        val newLuma = (luma + table.luminance[degree] * min(1f, chroma * COLOURFULNESS_GAIN)).coerceIn(0f, 1f)

        val newRed = newLuma + cr / CR_SCALE
        val newBlue = newLuma + cb / CB_SCALE
        val newGreen = (newLuma - BT601_RED * newRed - BT601_BLUE * newBlue) / BT601_GREEN
        return ChromaOps.fitToGamut(Argb.alpha(color), newRed, newGreen, newBlue, newLuma)
    }

    private fun hueDegrees(red: Float, green: Float, blue: Float, highest: Float, chroma: Float): Float {
        val sector = when (highest) {
            red -> ((green - blue) / chroma).let { if (it < 0f) it + SECTORS else it }
            green -> (blue - red) / chroma + 2f
            else -> (red - green) / chroma + 4f
        }
        return sector * DEGREES_PER_SECTOR
    }

    /** Per-degree blended shifts, built once per render. */
    private class HueTable(val cos: FloatArray, val sin: FloatArray, val saturation: FloatArray, val luminance: FloatArray) {
        companion object {
            fun build(mixer: ColorMixer): HueTable {
                val cos = FloatArray(DEGREES)
                val sin = FloatArray(DEGREES)
                val saturation = FloatArray(DEGREES)
                val luminance = FloatArray(DEGREES)
                for (degree in 0 until DEGREES) {
                    var hue = 0f
                    var sat = 0f
                    var lum = 0f
                    for ((band, weight) in weights(degree.toFloat())) {
                        val shift = mixer[band]
                        hue += weight * shift.hue
                        sat += weight * shift.saturation
                        lum += weight * shift.luminance
                    }
                    val radians = Math.toRadians((hue * MAX_HUE_SHIFT_DEGREES).toDouble())
                    cos[degree] = cos(radians).toFloat()
                    sin[degree] = sin(radians).toFloat()
                    saturation[degree] = (1f + sat).coerceAtLeast(0f)
                    luminance[degree] = lum * MAX_LUMINANCE_SHIFT
                }
                return HueTable(cos, sin, saturation, luminance)
            }

            /** Linear blend between the two band centres around [degree] (bands wrap at 360°). */
            fun weights(degree: Float): List<Pair<HueBand, Float>> {
                val bands = HueBand.entries
                for (index in bands.indices) {
                    val start = bands[index]
                    val end = bands[(index + 1) % bands.size]
                    val startDegrees = start.centerDegrees
                    val endDegrees = if (end.centerDegrees <= startDegrees) end.centerDegrees + FULL_CIRCLE else end.centerDegrees
                    val position = if (degree < startDegrees) degree + FULL_CIRCLE else degree
                    if (position >= startDegrees && position < endDegrees) {
                        val t = (position - startDegrees) / (endDegrees - startDegrees)
                        return listOf(start to 1f - t, end to t)
                    }
                }
                return listOf(HueBand.RED to 1f)
            }
        }
    }

    private companion object {
        const val CHANNEL_SCALE = 1f / Argb.CHANNEL_MAX
        const val MIN_CHROMA = 0.01f
        const val DEGREES = 360
        const val FULL_CIRCLE = 360f
        const val SECTORS = 6f
        const val DEGREES_PER_SECTOR = 60f
        const val MAX_HUE_SHIFT_DEGREES = 30f
        const val MAX_LUMINANCE_SHIFT = 0.25f

        /** Chroma at which a colour gets the full luminance shift; greyer pixels get proportionally less. */
        const val COLOURFULNESS_GAIN = 4f
        const val BT601_RED = 0.299f
        const val BT601_GREEN = 0.587f
        const val BT601_BLUE = 0.114f
        const val CB_SCALE = 0.564f
        const val CR_SCALE = 0.713f
    }
}
