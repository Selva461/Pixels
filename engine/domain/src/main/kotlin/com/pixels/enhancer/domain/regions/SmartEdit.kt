package com.pixels.enhancer.domain.regions

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.BoxBlur
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.LumaPlane
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.PixelResampler
import com.pixels.enhancer.domain.image.Srgb
import com.pixels.enhancer.domain.image.smoothstep
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.local.MaskShape
import com.pixels.enhancer.domain.processing.ops.SkinToneDetector
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Measurements of one region of the (already globally corrected) photo. Luma is gamma-encoded 0..1. */
data class RegionStats(
    /** Share of the frame, 0..1. */
    val area: Float,
    val meanLuma: Float,
    val p10: Float,
    val p90: Float,
    val chroma: Float,
    val cb: Float,
    val cr: Float,
    /** Mean local contrast (|luma − blurred luma|): texture and detail. */
    val detail: Float,
    /** Share of the region that is skin-coloured. */
    val skin: Float,
) {
    companion object {
        val EMPTY = RegionStats(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }
}

/**
 * Smart edit: finds the subject, sky and background, measures each one on the photo as it looks
 * after the global corrections, and sets ordinary mask sliders for each so the photo is edited
 * part by part, the way a retoucher would:
 *
 * - **Subject** brighter where it is darker than the scene, open shadows, more detail and a
 *   little colour (gentler on skin).
 * - **Sky** highlights recovered, cloud detail and contrast brought out, its own colour deepened
 *   (bluer for blue or grey skies, warmer for sunsets).
 * - **Background** slightly darker, calmer and less saturated than the subject so the eye goes
 *   to the subject; with no subject, the land gets open shadows and detail instead.
 *
 * Every value is a normal slider the user can see and change in Masking. Nothing is generated:
 * masks only weight how existing pixels are adjusted.
 */
object SmartEdit {
    const val SUBJECT_NAME = "Subject"
    const val SKY_NAME = "Sky"
    const val BACKGROUND_NAME = "Background"
    const val LAND_NAME = "Land"
    private val NAMES = setOf(SUBJECT_NAME, SKY_NAME, BACKGROUND_NAME, LAND_NAME)

    /** True for masks Smart edit made (so a new Smart edit replaces them, not the user's own masks). */
    fun isSmartMask(item: LocalAdjustment): Boolean = item.shape is MaskShape.Region && item.brush.isEmpty() && item.name in NAMES

    /** [existing] without previous Smart edit masks, plus [suggested]; never more than the mask limit. */
    fun merge(existing: LocalAdjustments, suggested: List<LocalAdjustment>): LocalAdjustments {
        val kept = existing.items.filterNot(::isSmartMask)
        var next = (kept.maxOfOrNull { it.id } ?: 0) + 1
        val added = suggested.map { it.copy(id = next++) }
        return LocalAdjustments(kept + added.take(max(0, LocalAdjustments.MAX_ITEMS - kept.size)))
    }

    /**
     * Masks for [edited] (the photo after global corrections). [reference] is the same frame
     * before edits, where regions are detected, so masks stay put as sliders move. [hint] is the
     * people segmentation in the same frame, if the platform has one.
     */
    fun suggest(edited: PixelBuffer, reference: PixelBuffer = edited, hint: SubjectHint? = null): List<LocalAdjustment> {
        val maps = RegionDetector.detect(reference, hint)
        val small = PixelResampler.downscaleToFit(edited, RegionDetector.REFINE_LONG_EDGE)
        val luma = LumaPlane.extract(small)
        val detail = FloatArray(luma.size).also {
            BoxBlur.blur(luma, it, small.width, small.height, max(2, max(small.width, small.height) / DETAIL_RADIUS_DIVISOR), FloatArray(luma.size))
        }
        for (i in detail.indices) detail[i] = abs(luma[i] - detail[i])
        fun statsOf(kind: RegionKind?) = measure(small, luma, detail, kind?.let { maps.weights(it, small.width, small.height) })

        val scene = statsOf(null)
        val subject = statsOf(RegionKind.SUBJECT)
        val sky = statsOf(RegionKind.SKY)
        val background = statsOf(RegionKind.BACKGROUND)
        val hasSubject = maps.subjectConfidence >= RegionDetector.MIN_SUBJECT_CONFIDENCE && subject.area >= MIN_SUBJECT_AREA
        val hasSky = sky.area >= MIN_SKY_AREA

        val out = ArrayList<LocalAdjustment>()
        var subjectTarget = subject.meanLuma
        if (hasSubject) {
            val (edit, target) = subjectEdit(subject, scene, maps.subjectConfidence)
            subjectTarget = target
            out += edit
        }
        if (hasSky) out += skyEdit(sky)
        if (background.area >= MIN_BACKGROUND_AREA) {
            when {
                hasSubject -> out += backgroundEdit(background, subject, subjectTarget, maps.subjectConfidence)
                hasSky -> out += landEdit(background)
            }
        }
        return out.map { it.rounded() }.filterNot { it.isNeutral }
    }

    internal fun subjectEdit(s: RegionStats, scene: RegionStats, confidence: Float): Pair<LocalAdjustment, Float> {
        val k = SUBJECT_BASE + (1f - SUBJECT_BASE) * confidence.coerceIn(0f, 1f)
        val skinny = s.skin > SKIN_SHARE
        val target = max(scene.meanLuma + SUBJECT_LIFT_OVER_SCENE, SUBJECT_MIN_TARGET).coerceAtMost(SUBJECT_MAX_TARGET)
        val ev = when {
            s.meanLuma < target - TARGET_TOLERANCE -> evBetween(s.meanLuma, target).coerceIn(0f, SUBJECT_MAX_EV)
            s.meanLuma > SUBJECT_TOO_BRIGHT -> evBetween(s.meanLuma, SUBJECT_BRIGHT_TARGET).coerceIn(-SUBJECT_MAX_DARKEN_EV, 0f)
            else -> 0f
        }
        val edit = LocalAdjustment(
            id = 0,
            shape = MaskShape.Region(RegionKind.SUBJECT),
            name = SUBJECT_NAME,
            exposure = ev / LocalAdjustment.MAX_EXPOSURE_EV * k,
            shadows = if (s.p10 < DARK_P10) (DARK_P10 - s.p10) / DARK_P10 * SUBJECT_MAX_SHADOWS * k else 0f,
            highlights = if (s.p90 > BRIGHT_P90) -SUBJECT_HIGHLIGHTS * k else 0f,
            clarity = when {
                skinny -> SKIN_CLARITY
                s.detail > DETAILED -> SUBJECT_CLARITY_DETAILED
                else -> SUBJECT_CLARITY
            } * k,
            sharpness = (if (skinny) SKIN_SHARPNESS else SUBJECT_SHARPNESS) * k,
            saturation = when {
                skinny -> 0f
                s.chroma < MUTED_CHROMA -> SUBJECT_SATURATION * k
                else -> SUBJECT_SATURATION_COLOURFUL * k
            },
            temperature = if (skinny) SKIN_WARMTH * k else 0f,
        )
        val reached = s.meanLuma + (target - s.meanLuma) * k * if (ev > 0f) 1f else 0f
        return edit to reached
    }

    internal fun skyEdit(s: RegionStats): LocalAdjustment {
        // A real sunset (warm and colourful) keeps its warmth; a pale warm haze is cooled towards blue.
        val sunset = s.cr > s.cb + SUNSET_MARGIN && s.chroma > SUNSET_MIN_CHROMA
        val ev = if (s.meanLuma > SKY_TOO_BRIGHT) evBetween(s.meanLuma, SKY_TARGET).coerceIn(-SKY_MAX_DARKEN_EV, 0f) else 0f
        return LocalAdjustment(
            id = 0,
            shape = MaskShape.Region(RegionKind.SKY),
            name = SKY_NAME,
            exposure = ev / LocalAdjustment.MAX_EXPOSURE_EV,
            highlights = -(SKY_HIGHLIGHTS_BASE + SKY_HIGHLIGHTS_RANGE * smoothstep(SKY_P90_LOW, SKY_P90_HIGH, s.p90)),
            contrast = SKY_CONTRAST + if (s.detail < FLAT_SKY) SKY_CONTRAST_FLAT else 0f,
            clarity = if (s.detail < FLAT_SKY) SKY_CLARITY_FLAT else SKY_CLARITY,
            saturation = when {
                s.chroma < GREY_SKY_CHROMA -> SKY_SATURATION_GREY
                s.chroma > VIVID_SKY_CHROMA -> SKY_SATURATION_VIVID
                else -> SKY_SATURATION
            },
            temperature = if (sunset) SKY_SUNSET_WARMTH else -SKY_COOLING,
        )
    }

    internal fun backgroundEdit(b: RegionStats, subject: RegionStats, subjectLuma: Float, confidence: Float): LocalAdjustment {
        val k = confidence.coerceIn(0f, 1f)
        val gap = subjectLuma - b.meanLuma
        val darkenEv = if (gap < SUBJECT_SEPARATION) ((SUBJECT_SEPARATION - gap) * SEPARATION_EV_PER_LUMA).coerceIn(BG_MIN_DARKEN_EV, BG_MAX_DARKEN_EV) else BG_MIN_DARKEN_EV
        return LocalAdjustment(
            id = 0,
            shape = MaskShape.Region(RegionKind.BACKGROUND),
            name = BACKGROUND_NAME,
            exposure = -darkenEv / LocalAdjustment.MAX_EXPOSURE_EV * k,
            saturation = -BG_DESATURATE * k,
            clarity = if (b.detail < subject.detail * SOFT_BACKGROUND_RATIO) -BG_SOFTEN * k else 0f,
            shadows = if (b.p10 < CRUSHED_P10) BG_SHADOW_GUARD else 0f,
        )
    }

    internal fun landEdit(b: RegionStats): LocalAdjustment {
        val ev = if (b.meanLuma < LAND_DARK) evBetween(b.meanLuma, LAND_TARGET).coerceIn(0f, LAND_MAX_EV) else 0f
        return LocalAdjustment(
            id = 0,
            shape = MaskShape.Region(RegionKind.BACKGROUND),
            name = LAND_NAME,
            exposure = ev / LocalAdjustment.MAX_EXPOSURE_EV,
            shadows = if (b.p10 < DARK_P10) (DARK_P10 - b.p10) / DARK_P10 * LAND_MAX_SHADOWS else 0f,
            clarity = if (b.detail < DETAILED) LAND_CLARITY else LAND_CLARITY_DETAILED,
            saturation = if (b.chroma < VIVID_SKY_CHROMA) LAND_SATURATION else 0f,
        )
    }

    /** Stops of exposure that move a gamma-encoded luma [from] to [to]. */
    private fun evBetween(from: Float, to: Float): Float {
        val a = Srgb.decode(from.coerceIn(MIN_LUMA, 1f))
        val b = Srgb.decode(to.coerceIn(MIN_LUMA, 1f))
        return (ln(b / a) / LN2).toFloat()
    }

    /** Weighted statistics of the region [weights] (null = whole frame). */
    fun measure(image: PixelBuffer, luma: FloatArray, detail: FloatArray, weights: FloatArray?): RegionStats {
        val histogram = FloatArray(HISTOGRAM_BINS)
        var total = 0.0
        var sumL = 0.0
        var sumChroma = 0.0
        var sumCb = 0.0
        var sumCr = 0.0
        var sumDetail = 0.0
        var sumSkin = 0.0
        for (i in luma.indices) {
            val w = weights?.get(i) ?: 1f
            if (w < MIN_WEIGHT) continue
            val c = image.pixels[i]
            val r = Argb.red(c) / 255f
            val g = Argb.green(c) / 255f
            val b = Argb.blue(c) / 255f
            val y = Luma.of(r, g, b)
            total += w
            sumL += w * luma[i]
            sumChroma += w * (max(r, max(g, b)) - min(r, min(g, b)))
            sumCb += w * (b - y)
            sumCr += w * (r - y)
            sumDetail += w * detail[i]
            if (SkinToneDetector.isLikelySkin(c)) sumSkin += w
            histogram[(luma[i] * (HISTOGRAM_BINS - 1)).roundToInt().coerceIn(0, HISTOGRAM_BINS - 1)] += w
        }
        if (total < 1.0) return RegionStats.EMPTY
        return RegionStats(
            area = (total / luma.size).toFloat(),
            meanLuma = (sumL / total).toFloat(),
            p10 = percentile(histogram, total.toFloat(), LOW_PERCENTILE),
            p90 = percentile(histogram, total.toFloat(), HIGH_PERCENTILE),
            chroma = (sumChroma / total).toFloat(),
            cb = (sumCb / total).toFloat(),
            cr = (sumCr / total).toFloat(),
            detail = (sumDetail / total).toFloat(),
            skin = (sumSkin / total).toFloat(),
        )
    }

    private fun percentile(histogram: FloatArray, total: Float, q: Float): Float {
        var running = 0f
        for (b in histogram.indices) {
            running += histogram[b]
            if (running >= total * q) return b / (HISTOGRAM_BINS - 1f)
        }
        return 1f
    }

    private fun LocalAdjustment.rounded(): LocalAdjustment {
        fun r(v: Float) = (v * 100f).roundToInt() / 100f
        return copy(
            exposure = r(exposure), contrast = r(contrast), saturation = r(saturation), temperature = r(temperature),
            highlights = r(highlights), shadows = r(shadows), tint = r(tint), clarity = r(clarity), sharpness = r(sharpness),
        ).clamped()
    }

    private const val LN2 = 0.6931471805599453
    private const val MIN_LUMA = 0.01f
    private const val MIN_WEIGHT = 0.02f
    private const val HISTOGRAM_BINS = 128
    private const val LOW_PERCENTILE = 0.1f
    private const val HIGH_PERCENTILE = 0.9f
    private const val DETAIL_RADIUS_DIVISOR = 150

    private const val MIN_SUBJECT_AREA = 0.005f
    private const val MIN_SKY_AREA = 0.03f
    private const val MIN_BACKGROUND_AREA = 0.1f

    // Subject.
    private const val SUBJECT_BASE = 0.5f
    private const val SKIN_SHARE = 0.25f
    private const val SUBJECT_LIFT_OVER_SCENE = 0.08f
    private const val SUBJECT_MIN_TARGET = 0.46f
    private const val SUBJECT_MAX_TARGET = 0.62f
    private const val TARGET_TOLERANCE = 0.02f
    private const val SUBJECT_MAX_EV = 1.2f
    private const val SUBJECT_TOO_BRIGHT = 0.75f
    private const val SUBJECT_BRIGHT_TARGET = 0.68f
    private const val SUBJECT_MAX_DARKEN_EV = 0.4f
    private const val DARK_P10 = 0.15f
    private const val SUBJECT_MAX_SHADOWS = 0.7f
    private const val BRIGHT_P90 = 0.92f
    private const val SUBJECT_HIGHLIGHTS = 0.35f
    private const val DETAILED = 0.05f
    private const val SKIN_CLARITY = 0.1f
    private const val SUBJECT_CLARITY = 0.35f
    private const val SUBJECT_CLARITY_DETAILED = 0.2f
    private const val SKIN_SHARPNESS = 0.15f
    private const val SUBJECT_SHARPNESS = 0.35f
    private const val MUTED_CHROMA = 0.18f
    private const val SUBJECT_SATURATION = 0.2f
    private const val SUBJECT_SATURATION_COLOURFUL = 0.1f
    private const val SKIN_WARMTH = 0.05f

    // Sky.
    private const val SUNSET_MARGIN = 0.02f
    private const val SUNSET_MIN_CHROMA = 0.15f
    private const val SKY_TOO_BRIGHT = 0.7f
    private const val SKY_TARGET = 0.64f
    private const val SKY_MAX_DARKEN_EV = 0.6f
    private const val SKY_HIGHLIGHTS_BASE = 0.35f
    private const val SKY_HIGHLIGHTS_RANGE = 0.45f
    private const val SKY_P90_LOW = 0.8f
    private const val SKY_P90_HIGH = 0.98f
    private const val SKY_CONTRAST = 0.25f
    private const val SKY_CONTRAST_FLAT = 0.15f
    private const val FLAT_SKY = 0.025f
    private const val SKY_CLARITY_FLAT = 0.4f
    private const val SKY_CLARITY = 0.25f
    private const val GREY_SKY_CHROMA = 0.05f
    private const val VIVID_SKY_CHROMA = 0.3f
    private const val SKY_SATURATION_GREY = 0.2f
    private const val SKY_SATURATION_VIVID = 0.15f
    private const val SKY_SATURATION = 0.35f
    private const val SKY_SUNSET_WARMTH = 0.1f
    private const val SKY_COOLING = 0.35f

    // Background behind a subject.
    private const val SUBJECT_SEPARATION = 0.08f
    private const val SEPARATION_EV_PER_LUMA = 3f
    private const val BG_MIN_DARKEN_EV = 0.15f
    private const val BG_MAX_DARKEN_EV = 0.6f
    private const val BG_DESATURATE = 0.15f
    private const val SOFT_BACKGROUND_RATIO = 0.6f
    private const val BG_SOFTEN = 0.2f
    private const val CRUSHED_P10 = 0.06f
    private const val BG_SHADOW_GUARD = 0.1f

    // Land under a sky, without a subject.
    private const val LAND_DARK = 0.32f
    private const val LAND_TARGET = 0.4f
    private const val LAND_MAX_EV = 0.5f
    private const val LAND_MAX_SHADOWS = 0.5f
    private const val LAND_CLARITY = 0.25f
    private const val LAND_CLARITY_DETAILED = 0.12f
    private const val LAND_SATURATION = 0.2f
}
