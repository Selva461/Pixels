package com.pixels.enhancer.domain.local

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.Srgb
import com.pixels.enhancer.domain.processing.ops.ChromaOps
import com.pixels.enhancer.domain.processing.ops.ChannelGains
import com.pixels.enhancer.domain.processing.ops.ToneCurve
import com.pixels.enhancer.domain.processing.ops.WhiteBalanceGains
import kotlin.math.max
import kotlin.math.pow

/**
 * Applies local adjustments to an edited image, after geometry. Per pixel and per mask: exposure
 * and temperature as linear-light gains, then contrast on luma, then saturation — all scaled by the
 * mask weight so edges blend smoothly. Pixel-wise, so it needs no tiling at any resolution.
 */
object LocalAdjustmentRenderer {
    private const val MIN_WEIGHT = 0.002f

    fun apply(image: PixelBuffer, adjustments: LocalAdjustments): PixelBuffer {
        val active = adjustments.items.filterNot { it.isNeutral }
        if (active.isEmpty()) return image
        val out = image.copy()
        val aspect = image.width.toFloat() / image.height
        active.forEach { applyOne(out, it, aspect) }
        return out
    }

    private fun applyOne(image: PixelBuffer, adjustment: LocalAdjustment, aspect: Float) {
        val contrastCurve = ToneCurve.build(contrast = adjustment.contrast * LocalAdjustment.MAX_CONTRAST, highlights = 0f, shadows = 0f)
        val temperatureGains = WhiteBalanceGains.withCreativeShift(ChannelGains.IDENTITY, adjustment.temperature, 0f)
        val fullGain = 2f.pow(adjustment.exposure * LocalAdjustment.MAX_EXPOSURE_EV)
        for (y in 0 until image.height) {
            val ny = (y + 0.5f) / image.height
            for (x in 0 until image.width) {
                val weight = adjustment.weightAt((x + 0.5f) / image.width, ny, aspect)
                if (weight < MIN_WEIGHT) continue
                val index = y * image.width + x
                image.pixels[index] = adjustPixel(image.pixels[index], weight, fullGain, temperatureGains, contrastCurve, adjustment)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun adjustPixel(color: Int, weight: Float, fullGain: Float, temperature: ChannelGains, contrast: ToneCurve, adjustment: LocalAdjustment): Int {
        var result = color
        if (adjustment.exposure != 0f || adjustment.temperature != 0f) {
            val gain = 1f + (fullGain - 1f) * weight
            var red = Srgb.toLinear(Argb.red(result)) * gain * (1f + (temperature.red - 1f) * weight)
            var green = Srgb.toLinear(Argb.green(result)) * gain * (1f + (temperature.green - 1f) * weight)
            var blue = Srgb.toLinear(Argb.blue(result)) * gain * (1f + (temperature.blue - 1f) * weight)
            val brightest = max(red, max(green, blue))
            // Scale back as a whole rather than clipping one channel, which would shift hue.
            if (brightest > 1f) {
                red /= brightest
                green /= brightest
                blue /= brightest
            }
            result = Argb.pack(Argb.alpha(result), Srgb.toSrgb8(red), Srgb.toSrgb8(green), Srgb.toSrgb8(blue))
        }
        if (adjustment.contrast != 0f) {
            val luma = Luma.ofPixel(result)
            result = ChromaOps.withLuma(result, luma, luma + (contrast.map(luma) - luma) * weight)
        }
        if (adjustment.saturation != 0f) {
            result = ChromaOps.scaleChroma(result, (1f + adjustment.saturation * weight).coerceAtLeast(0f))
        }
        return result
    }
}
