package com.pixels.enhancer.domain.local

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.BoxBlur
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.LumaPlane
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.Srgb
import com.pixels.enhancer.domain.processing.ops.ChannelGains
import com.pixels.enhancer.domain.processing.ops.ChromaOps
import com.pixels.enhancer.domain.processing.ops.ToneCurve
import com.pixels.enhancer.domain.processing.ops.WhiteBalanceGains
import com.pixels.enhancer.domain.regions.RegionDetector
import com.pixels.enhancer.domain.regions.RegionKind
import com.pixels.enhancer.domain.regions.SubjectHint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Applies local adjustments to an edited image, after geometry. Per mask: a weight map (shape,
 * brush, inversion, range), then per pixel exposure and white balance as linear-light gains, the
 * tone curve (contrast, highlights, shadows) on luma, clarity and sharpness as local contrast, and
 * saturation — all scaled by the weight so edges blend smoothly.
 */
object LocalAdjustmentRenderer {
    private const val MIN_WEIGHT = 0.002f

    /**
     * [reference] is the unedited photo in the same frame (same size as [image]); subject and sky
     * are found on it, so region masks don't move as the user's edits change the picture.
     */
    fun apply(image: PixelBuffer, adjustments: LocalAdjustments, reference: PixelBuffer = image, hint: SubjectHint? = null): PixelBuffer {
        val active = adjustments.items.filterNot { it.isNeutral }
        if (active.isEmpty()) return image
        val out = image.copy()
        val regions = regionsFor(active, reference, hint, image.width, image.height)
        active.forEach { applyOne(out, it, regions) }
        return out
    }

    /** The final weight map of one adjustment on [image] — also used by the editor's mask overlay. */
    fun maskOf(image: PixelBuffer, adjustment: LocalAdjustment, reference: PixelBuffer = image, hint: SubjectHint? = null): FloatArray =
        maskOf(image, adjustment, regionsFor(listOf(adjustment), reference, hint, image.width, image.height))

    /** Detects regions once per render, only when a mask needs them. */
    private fun regionsFor(items: List<LocalAdjustment>, reference: PixelBuffer, hint: SubjectHint?, width: Int, height: Int): ((RegionKind) -> FloatArray)? {
        if (items.none { it.shape is MaskShape.Region }) return null
        val maps = RegionDetector.detect(reference, hint)
        // Resized per use rather than kept: at export size each map is tens of megabytes.
        return { kind -> maps.weights(kind, width, height) }
    }

    private fun maskOf(image: PixelBuffer, adjustment: LocalAdjustment, regions: ((RegionKind) -> FloatArray)?): FloatArray {
        val weights = MaskRaster.weights(adjustment, image.width, image.height, regions)
        val range = adjustment.range ?: return weights
        for (i in weights.indices) {
            if (weights[i] >= MIN_WEIGHT) weights[i] *= range.weightFor(image.pixels[i])
        }
        return weights
    }

    private fun applyOne(image: PixelBuffer, adjustment: LocalAdjustment, regions: ((RegionKind) -> FloatArray)?) {
        val weights = maskOf(image, adjustment, regions)
        val tone = ToneCurve.build(
            contrast = adjustment.contrast * LocalAdjustment.MAX_CONTRAST,
            highlights = adjustment.highlights * LocalAdjustment.MAX_TONE,
            shadows = adjustment.shadows * LocalAdjustment.MAX_TONE,
        )
        val hasTone = adjustment.contrast != 0f || adjustment.highlights != 0f || adjustment.shadows != 0f
        val balance = WhiteBalanceGains.withCreativeShift(ChannelGains.IDENTITY, adjustment.temperature, adjustment.tint)
        val hasGain = adjustment.exposure != 0f || adjustment.temperature != 0f || adjustment.tint != 0f
        val fullGain = 2f.pow(adjustment.exposure * LocalAdjustment.MAX_EXPOSURE_EV)
        val detail = if (adjustment.needsNeighbourhood) LocalDetail.of(image, adjustment) else null

        for (i in 0 until image.pixelCount) {
            val weight = weights[i]
            if (weight < MIN_WEIGHT) continue
            var color = image.pixels[i]
            if (hasGain) color = gain(color, weight, fullGain, balance)
            if (hasTone || detail != null) {
                val luma = Luma.ofPixel(color)
                var target = if (hasTone) tone.map(luma) else luma
                if (detail != null) target += detail.delta(i)
                target = target.coerceIn(0f, 1f)
                color = ChromaOps.withLuma(color, luma, luma + (target - luma) * weight)
            }
            if (adjustment.saturation != 0f) color = ChromaOps.scaleChroma(color, (1f + adjustment.saturation * weight).coerceAtLeast(0f))
            image.pixels[i] = color
        }
    }

    private fun gain(color: Int, weight: Float, fullGain: Float, balance: ChannelGains): Int {
        val gain = 1f + (fullGain - 1f) * weight
        var red = Srgb.toLinear(Argb.red(color)) * gain * (1f + (balance.red - 1f) * weight)
        var green = Srgb.toLinear(Argb.green(color)) * gain * (1f + (balance.green - 1f) * weight)
        var blue = Srgb.toLinear(Argb.blue(color)) * gain * (1f + (balance.blue - 1f) * weight)
        val brightest = max(red, max(green, blue))
        // Scale back as a whole rather than clipping one channel, which would shift hue.
        if (brightest > 1f) {
            red /= brightest
            green /= brightest
            blue /= brightest
        }
        return Argb.pack(Argb.alpha(color), Srgb.toSrgb8(red), Srgb.toSrgb8(green), Srgb.toSrgb8(blue))
    }

    /** Clarity (large-radius) and sharpness (small-radius) luma deltas, computed once per mask. */
    private class LocalDetail(private val luma: FloatArray, private val wide: FloatArray?, private val fine: FloatArray?, private val clarity: Float, private val sharpness: Float) {
        fun delta(i: Int): Float {
            var d = 0f
            if (wide != null) d += (luma[i] - wide[i]) * clarity * CLARITY_GAIN
            if (fine != null) d += (luma[i] - fine[i]) * sharpness * (if (sharpness > 0f) SHARPEN_GAIN else SOFTEN_GAIN)
            return d.coerceIn(-MAX_DELTA, MAX_DELTA)
        }

        companion object {
            fun of(image: PixelBuffer, adjustment: LocalAdjustment): LocalDetail {
                val luma = LumaPlane.extract(image)
                val scratch = FloatArray(image.pixelCount)
                val wide = if (adjustment.clarity != 0f) {
                    val radius = max(2, (min(image.width, image.height) * CLARITY_RADIUS_FRACTION).toInt())
                    FloatArray(image.pixelCount).also { BoxBlur.blur(luma, it, image.width, image.height, radius, scratch) }
                } else {
                    null
                }
                val fine = if (adjustment.sharpness != 0f) {
                    FloatArray(image.pixelCount).also { BoxBlur.blur(luma, it, image.width, image.height, 1, scratch) }
                } else {
                    null
                }
                return LocalDetail(luma, wide, fine, adjustment.clarity, adjustment.sharpness)
            }

            const val CLARITY_RADIUS_FRACTION = 0.012f
            const val CLARITY_GAIN = 0.6f
            const val SHARPEN_GAIN = 1.5f
            const val SOFTEN_GAIN = 1f
            const val MAX_DELTA = 0.15f
        }
    }
}
