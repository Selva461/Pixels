package com.pixels.enhancer.domain.local

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.BoxBlur
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.Srgb
import com.pixels.enhancer.domain.image.smoothstep
import com.pixels.enhancer.domain.processing.ops.BaseDetail
import com.pixels.enhancer.domain.processing.ops.ChannelGains
import com.pixels.enhancer.domain.processing.ops.ToneCurve
import com.pixels.enhancer.domain.processing.ops.WhiteBalanceGains
import com.pixels.enhancer.domain.regions.RegionDetector
import com.pixels.enhancer.domain.regions.RegionKind
import com.pixels.enhancer.domain.regions.SubjectHint
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Applies local adjustments to an edited image, after geometry, on a base/detail split of the
 * photo (see [BaseDetail]):
 *
 * 1. Log luminance is split into an edge-aware base and the detail on top of it.
 * 2. Each mask's tone moves (exposure and white balance as linear-light gains, then the tone curve
 *    for contrast, highlights and shadows, then saturation) act on the base only, scaled by the
 *    mask's weight so edges blend smoothly.
 * 3. The detail goes back on as a gain: `s' = base' + (1 + k)·detail`, where k is the masks'
 *    clarity. With no clarity the photo's own texture comes back exactly (gain 1), so lifting or
 *    compressing a region never flattens print, fabric folds or skin, and never adds noise to sky.
 *
 * All of it runs in float and is quantised once. Pixels no mask touches are left bit-for-bit.
 */
object LocalAdjustmentRenderer {
    private const val MIN_WEIGHT = 0.002f
    private const val LN2 = 0.6931472f

    /**
     * Detail gain k per unit of clarity: full clarity puts back 1.5× the photo's detail. Smart
     * edit's subject clarity (0.2 to 0.35) lands near the reference's k = 0.12.
     */
    const val CLARITY_DETAIL_GAIN = 0.5f

    /** Extra gain on the finest detail (1 px) per unit of sharpness. */
    const val SHARPNESS_DETAIL_GAIN = 1f

    /**
     * A lift fades from full on objects whose brightest channel averages +1 stop (relative to
     * mid-grey) to none at +2.4, just under white (+2.47): a warm white shirt has its red channel
     * near the top, and lifting it would clip red and flatten its folds.
     */
    private const val LIFT_FADE_LOW = 1.0f
    private const val LIFT_FADE_HIGH = 2.4f

    /**
     * The base's brightest channel rolls off above +2 stops towards +2.4 instead of running into
     * white, so the detail put back on top still has room.
     */
    private const val SHOULDER_STOPS = 2.0f
    private const val SHOULDER_RANGE = 0.4f

    private fun stopsOf(luminance: Float): Float = ln(max(luminance, BaseDetail.MIN_LUMINANCE) / BaseDetail.MID_GREY) / LN2

    /** Most the sharpness term may move a pixel, in stops. */
    private const val MAX_SHARPEN_STOPS = 0.25f

    /**
     * [reference] is the unedited photo in the same frame (same size as [image]); subject and sky
     * are found on it, so region masks don't move as the user's edits change the picture.
     */
    fun apply(image: PixelBuffer, adjustments: LocalAdjustments, reference: PixelBuffer = image, hint: SubjectHint? = null): PixelBuffer {
        val active = adjustments.items.filterNot { it.isNeutral }
        if (active.isEmpty()) return image
        val regions = regionsFor(active, reference, hint, image.width, image.height)
        val layers = Layers.of(image, sharpen = active.any { it.sharpness != 0f })
        active.forEach { applyOne(image, layers, it, regions) }
        return layers.recombine(image)
    }

    /** The final weight map of one adjustment on [image] — also used by the editor's mask overlay. */
    fun maskOf(image: PixelBuffer, adjustment: LocalAdjustment, reference: PixelBuffer = image, hint: SubjectHint? = null): FloatArray =
        maskOf(image, adjustment, regionsFor(listOf(adjustment), reference, hint, image.width, image.height))

    /** Detects regions once per render, only when a mask needs them. */
    private fun regionsFor(items: List<LocalAdjustment>, reference: PixelBuffer, hint: SubjectHint?, width: Int, height: Int): ((RegionKind) -> FloatArray)? {
        if (items.none { it.shape is MaskShape.Region }) return null
        val maps = RegionDetector.detect(reference, hint)
        // Built per use rather than kept: at export size each map is tens of megabytes. Edges
        // follow the reference photo's own edges when it is the frame being edited.
        val guide = reference.takeIf { it.width == width && it.height == height }
        return { kind -> if (guide != null) maps.weights(kind, guide) else maps.weights(kind, width, height) }
    }

    /** Range masks are evaluated on the picture as it comes in, so one mask's edit never re-selects another's pixels. */
    private fun maskOf(image: PixelBuffer, adjustment: LocalAdjustment, regions: ((RegionKind) -> FloatArray)?): FloatArray {
        val weights = MaskRaster.weights(adjustment, image.width, image.height, regions)
        val range = adjustment.range ?: return weights
        for (i in weights.indices) {
            if (weights[i] >= MIN_WEIGHT) weights[i] *= range.weightFor(image.pixels[i])
        }
        return weights
    }

    private fun applyOne(image: PixelBuffer, layers: Layers, adjustment: LocalAdjustment, regions: ((RegionKind) -> FloatArray)?) {
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
        val clarity = adjustment.clarity * CLARITY_DETAIL_GAIN
        val sharpness = adjustment.sharpness * SHARPNESS_DETAIL_GAIN
        val red = layers.red
        val green = layers.green
        val blue = layers.blue

        for (i in 0 until image.pixelCount) {
            val weight = weights[i]
            if (weight < MIN_WEIGHT) continue
            if (weight > layers.cover[i]) layers.cover[i] = weight
            var r = red[i]
            var g = green[i]
            var b = blue[i]
            if (hasGain) {
                // A lift fades out on bright objects so it never pushes them past white. It is keyed
                // on the object's average brightness, so the folds of a white shirt all move together.
                val lift = if (fullGain > 1f) 1f - smoothstep(LIFT_FADE_LOW, LIFT_FADE_HIGH, layers.coarse[i]) else 1f
                val gain = 1f + (fullGain - 1f) * weight * lift
                r *= gain * (1f + (balance.red - 1f) * weight)
                g *= gain * (1f + (balance.green - 1f) * weight)
                b *= gain * (1f + (balance.blue - 1f) * weight)
            }
            if (hasTone) {
                // The curve is defined on encoded values; it moves the base's luminance as a gain.
                val y = Luma.of(r, g, b)
                if (y > 0f && y < 1f) {
                    val encoded = Srgb.encode(y)
                    val target = encoded + (tone.mapSmooth(encoded) - encoded) * weight
                    val scale = Srgb.decode(target) / y
                    r *= scale
                    g *= scale
                    b *= scale
                }
            }
            if (adjustment.saturation != 0f) {
                val y = Luma.of(r, g, b)
                var factor = (1f + adjustment.saturation * weight).coerceAtLeast(0f)
                // Never push a channel below zero: shorten the step instead, which keeps hue.
                val lowest = min(r, min(g, b))
                val dip = y + (lowest - y) * factor
                if (dip < 0f && y > lowest) factor = y / (y - lowest)
                r = y + (r - y) * factor
                g = y + (g - y) * factor
                b = y + (b - y) * factor
            }
            red[i] = r
            green[i] = g
            blue[i] = b
            if (clarity != 0f) layers.detailGain[i] += clarity * weight
            if (sharpness != 0f) layers.sharpenGain!![i] += sharpness * weight
        }
    }

    /** The base layer (linear RGB with the detail divided out) plus what goes back on top of it. */
    private class Layers(
        val red: FloatArray,
        val green: FloatArray,
        val blue: FloatArray,
        /** Log-luminance detail, s − base. */
        val detail: FloatArray,
        /** Local average over whole objects (see [BaseDetail.coarse]) of the brightest channel, in stops. */
        val coarse: FloatArray,
        /** 1 px band of s, only when a mask sharpens. */
        val fine: FloatArray?,
        val detailGain: FloatArray,
        val sharpenGain: FloatArray?,
        /** Strongest mask weight on each pixel; 0 = untouched. */
        val cover: FloatArray,
    ) {
        /**
         * Base ⊕ (1 + k)·detail, quantised once; untouched pixels keep their original value. Bright
         * base tones that the masks pushed up roll off on a soft shoulder (in proportion to the mask
         * weight) instead of clipping, so detail on white fabric survives a lift.
         */
        fun recombine(image: PixelBuffer): PixelBuffer {
            val out = image.copy()
            for (i in 0 until image.pixelCount) {
                if (cover[i] < MIN_WEIGHT) continue
                var stops = (1f + detailGain[i]).coerceAtLeast(0f) * detail[i]
                val base = stopsOf(max(red[i], max(green[i], blue[i])))
                if (base > SHOULDER_STOPS) {
                    // Only what the masks added is rolled off: a bright pixel that was not lifted stays put.
                    val c = image.pixels[i]
                    val before = stopsOf(max(Srgb.toLinear(Argb.red(c)), max(Srgb.toLinear(Argb.green(c)), Srgb.toLinear(Argb.blue(c))))) - detail[i]
                    val over = base - SHOULDER_STOPS
                    val rolled = max(SHOULDER_STOPS + over / (1f + over / SHOULDER_RANGE), min(base, before))
                    stops += (rolled - base) * cover[i]
                }
                if (fine != null) stops += (sharpenGain!![i] * fine[i]).coerceIn(-MAX_SHARPEN_STOPS, MAX_SHARPEN_STOPS)
                val gain = 2f.pow(stops)
                var r = red[i] * gain
                var g = green[i] * gain
                var b = blue[i] * gain
                val brightest = max(r, max(g, b))
                // Scale back as a whole rather than clipping one channel, which would shift hue.
                if (brightest > 1f) {
                    r /= brightest
                    g /= brightest
                    b /= brightest
                }
                out.pixels[i] = Argb.pack(Argb.alpha(image.pixels[i]), Srgb.toSrgb8(r), Srgb.toSrgb8(g), Srgb.toSrgb8(b))
            }
            return out
        }

        companion object {
            fun of(image: PixelBuffer, sharpen: Boolean): Layers {
                val n = image.pixelCount
                val red = FloatArray(n)
                val green = FloatArray(n)
                val blue = FloatArray(n)
                val s = FloatArray(n)
                for (i in 0 until n) {
                    val c = image.pixels[i]
                    red[i] = Srgb.toLinear(Argb.red(c))
                    green[i] = Srgb.toLinear(Argb.green(c))
                    blue[i] = Srgb.toLinear(Argb.blue(c))
                    s[i] = stopsOf(Luma.of(red[i], green[i], blue[i]))
                }
                val detail = BaseDetail.detail(s, image.width, image.height)
                val coarse = BaseDetail.coarse(
                    FloatArray(n) { stopsOf(max(red[it], max(green[it], blue[it]))) },
                    image.width,
                    image.height,
                )
                // Divide the detail out of every channel: the base keeps each pixel's own colour.
                for (i in 0 until n) {
                    val toBase = 2f.pow(-detail[i])
                    red[i] *= toBase
                    green[i] *= toBase
                    blue[i] *= toBase
                }
                val fine = if (sharpen) {
                    val blurred = FloatArray(n)
                    BoxBlur.blur(s, blurred, image.width, image.height, 1, FloatArray(n))
                    for (i in 0 until n) s[i] -= blurred[i]
                    s
                } else {
                    null
                }
                return Layers(red, green, blue, detail, coarse, fine, FloatArray(n), if (sharpen) FloatArray(n) else null, FloatArray(n))
            }
        }
    }
}
