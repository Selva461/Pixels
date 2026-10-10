package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.Srgb
import com.pixels.enhancer.domain.planning.Calibration
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import com.pixels.enhancer.domain.processing.ops.ChannelGains
import com.pixels.enhancer.domain.processing.ops.WhiteBalanceGains
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Calibration (manual only). The red, green and blue primaries are rotated in hue and scaled in
 * saturation in a luma-preserving chroma plane (linear Rec. 709), and the three new primaries form
 * a 3×3 matrix applied in linear light. Each matrix row is normalised so (1, 1, 1) maps to itself:
 * greys stay grey whatever the settings. The shadows tint is a white-balance-style gain that fades
 * out above the midtones.
 */
class CalibrationStage : ProcessingStage {
    override val id = StageIds.CALIBRATION
    override val displayName = "Calibration"

    override fun isEnabled(context: ProcessingContext) = !context.plan.calibration.isNeutral

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val calibration = context.plan.calibration.clamped()
        val matrix = CalibrationMatrix.of(calibration)
        val tint = ShadowTintTable.of(calibration.shadowsTint)
        val pixels = input.pixels
        for (index in pixels.indices) {
            val color = pixels[index]
            val r = Srgb.toLinear(Argb.red(color))
            val g = Srgb.toLinear(Argb.green(color))
            val b = Srgb.toLinear(Argb.blue(color))
            var outR = matrix[0] * r + matrix[1] * g + matrix[2] * b
            var outG = matrix[3] * r + matrix[4] * g + matrix[5] * b
            var outB = matrix[6] * r + matrix[7] * g + matrix[8] * b
            if (tint != null) {
                // Shadow weight from the encoded brightness, so "shadows" means what the eye sees as dark.
                val code = ((Argb.red(color) * LUMA_R + Argb.green(color) * LUMA_G + Argb.blue(color) * LUMA_B)).toInt().coerceIn(0, Argb.CHANNEL_MAX)
                val gains = tint[code]
                outR *= gains.red
                outG *= gains.green
                outB *= gains.blue
            }
            pixels[index] = pack(Argb.alpha(color), outR, outG, outB)
        }
        return input
    }

    /** Negative light is impossible; above white, all channels scale down together so hue holds. */
    private fun pack(alpha: Int, red: Float, green: Float, blue: Float): Int {
        var r = max(0f, red)
        var g = max(0f, green)
        var b = max(0f, blue)
        val brightest = max(r, max(g, b))
        if (brightest > 1f) {
            r /= brightest
            g /= brightest
            b /= brightest
        }
        return Argb.pack(alpha, Srgb.toSrgb8(r), Srgb.toSrgb8(g), Srgb.toSrgb8(b))
    }

    private companion object {
        const val LUMA_R = 0.2126f
        const val LUMA_G = 0.7152f
        const val LUMA_B = 0.0722f
    }
}

/**
 * Builds the neutral-preserving calibration matrix (row-major 3×3, linear RGB). The new primaries
 * are the columns; each column is then scaled (like a white balance on the input side) so that
 * (1, 1, 1) still maps to (1, 1, 1). Scaling columns keeps each primary's new hue and saturation;
 * scaling rows instead would largely undo a saturation change.
 */
object CalibrationMatrix {
    private const val KR = 0.2126f
    private const val KG = 0.7152f
    private const val KB = 0.0722f

    fun of(calibration: Calibration): FloatArray {
        val red = primary(1f, 0f, 0f, calibration.redHue, calibration.redSaturation)
        val green = primary(0f, 1f, 0f, calibration.greenHue, calibration.greenSaturation)
        val blue = primary(0f, 0f, 1f, calibration.blueHue, calibration.blueSaturation)
        val m = floatArrayOf(
            red[0], green[0], blue[0],
            red[1], green[1], blue[1],
            red[2], green[2], blue[2],
        )
        val scale = solveForWhite(m) ?: return rowNormalised(m)
        for (row in 0..2) for (col in 0..2) m[row * 3 + col] *= scale[col]
        return m
    }

    /** k with M·k = (1, 1, 1), by Cramer's rule; null if M is (near) singular. */
    private fun solveForWhite(m: FloatArray): FloatArray? {
        val det = determinant(m)
        if (kotlin.math.abs(det) < MIN_DETERMINANT) return null
        return FloatArray(3) { col ->
            val replaced = m.copyOf()
            for (row in 0..2) replaced[row * 3 + col] = 1f
            determinant(replaced) / det
        }.takeIf { k -> k.all { it > 0f } }
    }

    private fun determinant(m: FloatArray): Float =
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

    /** Fallback that still keeps greys grey. */
    private fun rowNormalised(m: FloatArray): FloatArray {
        for (row in 0..2) {
            val sum = m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2]
            if (sum > MIN_ROW_SUM) for (col in 0..2) m[row * 3 + col] /= sum
        }
        return m
    }

    /** One primary rotated by [hue] (−1..1 → ±30°) and saturated by [saturation], keeping its luma. */
    private fun primary(red: Float, green: Float, blue: Float, hue: Float, saturation: Float): FloatArray {
        val luma = KR * red + KG * green + KB * blue
        val cb = (blue - luma) / (2f * (1f - KB))
        val cr = (red - luma) / (2f * (1f - KR))
        val angle = Math.toRadians((hue * Calibration.MAX_HUE_DEGREES).toDouble())
        val scale = 1f + saturation * Calibration.MAX_SATURATION_CHANGE
        val rotatedCb = (cb * cos(angle) - cr * sin(angle)).toFloat() * scale
        val rotatedCr = (cb * sin(angle) + cr * cos(angle)).toFloat() * scale
        val newRed = luma + 2f * (1f - KR) * rotatedCr
        val newBlue = luma + 2f * (1f - KB) * rotatedCb
        val newGreen = (luma - KR * newRed - KB * newBlue) / KG
        return floatArrayOf(newRed, newGreen, newBlue)
    }

    private const val MIN_ROW_SUM = 1e-4f
    private const val MIN_DETERMINANT = 1e-4f
}

/** Per-luma-code channel gains for the shadows tint (full effect at black, none above mid-grey). */
private object ShadowTintTable {
    private const val FADE_END = 0.5f

    fun of(tint: Float): Array<ChannelGains>? {
        if (tint == 0f) return null
        return Array(Argb.CHANNEL_MAX + 1) { code ->
            val weight = (1f - code / (Argb.CHANNEL_MAX * FADE_END)).coerceIn(0f, 1f)
            WhiteBalanceGains.withCreativeShift(ChannelGains.IDENTITY, 0f, tint * weight * weight)
        }
    }
}
