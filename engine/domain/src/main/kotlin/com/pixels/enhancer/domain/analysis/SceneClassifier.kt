package com.pixels.enhancer.domain.analysis

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.processing.ops.SkinToneDetector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class SceneType(val label: String) {
    GENERAL("General"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    NATURE("Nature"),
    BEACH("Beach"),
    NIGHT("Night"),
    LOW_LIGHT("Low light"),
    INDOOR("Indoor"),
    FOOD("Food"),
    ARCHITECTURE("Architecture"),
    DOCUMENT("Document"),
}

data class SceneEstimate(val scene: SceneType, val confidence: Float, val scores: Map<SceneType, Float>) {
    companion object {
        val GENERAL = SceneEstimate(SceneType.GENERAL, 0f, emptyMap())
    }
}

/** Region fractions measured in one sampled pass; each is 0..1 of the region named. */
data class SceneFeatures(
    val skinInCenter: Float,
    /** Skin-coloured pixels at the left/right edges: a face is a central blob, sand or a wall is not. */
    val skinAtSides: Float,
    val skyInTop: Float,
    val sandInBottom: Float,
    /** Paper-like pixels in the bottom region: a document is paper everywhere, a landscape has ground. */
    val paperInBottom: Float,
    val foliage: Float,
    val paper: Float,
    val ink: Float,
    val warmInCenter: Float,
    val axisAlignedEdges: Float,
    val strongEdges: Float,
)

/**
 * Heuristic scene classifier. Advisory only: it picks naturalness limits for Auto Enhance, the user
 * can override it, and it never changes image content. Scores are simple, documented rules so a
 * wrong guess can be understood from the debug report.
 */
object SceneClassifier {
    /** Below this, no scene is convincing enough and General limits apply. */
    const val MIN_CONFIDENCE = 0.45f

    fun classify(image: PixelBuffer, analysis: ImageAnalysis): SceneEstimate {
        val features = measure(image)
        val scores = score(features, analysis)
        val best = scores.maxByOrNull { it.value }
        return if (best == null || best.value < MIN_CONFIDENCE) {
            SceneEstimate(SceneType.GENERAL, best?.value ?: 0f, scores)
        } else {
            SceneEstimate(best.key, best.value, scores)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    fun score(f: SceneFeatures, a: ImageAnalysis): Map<SceneType, Float> {
        val warmCast = a.neutralBalance.red > a.neutralBalance.blue * WARM_CAST_RATIO
        val faceLike = unit(f.skinInCenter / PORTRAIT_SKIN) * unit((f.skinInCenter - f.skinAtSides) / PORTRAIT_SKIN_CONTRAST)
        val pageLike = unit(f.paperInBottom / PAGE_BOTTOM_PAPER)
        return mapOf(
            SceneType.NIGHT to unit((NIGHT_LUMA - a.exposureScore) / NIGHT_LUMA_RANGE) * if (a.luminance.p99 > LIGHTS_P99) 1f else 0.8f,
            SceneType.LOW_LIGHT to unit((LOW_LIGHT_LUMA - a.exposureScore) / LOW_LIGHT_RANGE) * unit(a.noiseScore / LOW_LIGHT_NOISE),
            SceneType.DOCUMENT to unit(f.paper / DOCUMENT_PAPER) * unit(f.ink / DOCUMENT_INK) *
                unit((DOCUMENT_MAX_CHROMA - a.saturationScore) / DOCUMENT_CHROMA_RANGE),
            SceneType.PORTRAIT to faceLike,
            SceneType.BEACH to min(unit(f.skyInTop / BEACH_SKY), unit(f.sandInBottom / BEACH_SAND)),
            SceneType.LANDSCAPE to unit(f.skyInTop / LANDSCAPE_SKY) * LANDSCAPE_WEIGHT * (1f - faceLike) * (1f - pageLike),
            SceneType.NATURE to unit(f.foliage / NATURE_FOLIAGE) * (1f - unit(f.skyInTop / LANDSCAPE_SKY) * NATURE_SKY_PENALTY),
            SceneType.FOOD to unit(f.warmInCenter / FOOD_WARM) * (1f - unit(f.skyInTop / FOOD_MAX_SKY)) * (1f - faceLike),
            SceneType.ARCHITECTURE to unit((f.axisAlignedEdges - ARCHITECTURE_MIN_ALIGNED) / ARCHITECTURE_ALIGNED_RANGE) *
                unit(f.strongEdges / ARCHITECTURE_EDGES) * (1f - unit(f.foliage / NATURE_FOLIAGE)) * (1f - pageLike),
            SceneType.INDOOR to (if (warmCast) INDOOR_WARM_SCORE else 0f) * (1f - unit(f.skyInTop / FOOD_MAX_SKY)) *
                unit((a.exposureScore - INDOOR_MIN_LUMA) / INDOOR_LUMA_RANGE),
        )
    }

    fun measure(image: PixelBuffer): SceneFeatures {
        val counter = FeatureCounter()
        val step = SampleGrid.step(image.pixelCount)
        for (y in 0 until image.height step step) {
            for (x in 0 until image.width step step) {
                counter.add(image, x, y)
            }
        }
        return counter.features()
    }

    private class FeatureCounter {
        var center = 0; var skin = 0
        var sides = 0; var skinSides = 0
        var paperBottom = 0
        var top = 0; var sky = 0
        var bottom = 0; var sand = 0
        var all = 0; var foliage = 0; var paper = 0; var ink = 0; var warm = 0
        var edges = 0; var aligned = 0

        fun add(image: PixelBuffer, x: Int, y: Int) {
            val color = image.pixels[y * image.width + x]
            val u = x / image.width.toFloat()
            val v = y / image.height.toFloat()
            val luma = Luma.ofPixel(color)
            val chroma = SaturationEstimator.chromaOf(color)
            val hue = hueOf(color)
            all++
            val inCenter = u in CENTER_START..CENTER_END && v in CENTER_START..CENTER_END
            if (inCenter) {
                center++
                if (SkinToneDetector.isLikelySkin(color)) skin++
                if (chroma > WARM_MIN_CHROMA && hue in WARM_HUES) warm++
            }
            if (v < TOP_REGION) {
                top++
                val blueSky = hue in SKY_HUES && chroma > SKY_MIN_CHROMA && luma > SKY_MIN_LUMA
                val overcast = chroma < OVERCAST_MAX_CHROMA && luma > OVERCAST_MIN_LUMA
                if (blueSky || overcast) sky++
            }
            if (u < SIDE_REGION || u > 1f - SIDE_REGION) {
                sides++
                if (SkinToneDetector.isLikelySkin(color)) skinSides++
            }
            if (v > BOTTOM_REGION) {
                bottom++
                if (hue in SAND_HUES && chroma in SAND_CHROMA && luma > SAND_MIN_LUMA) sand++
                if (luma > PAPER_MIN_LUMA && chroma < PAPER_MAX_CHROMA) paperBottom++
            }
            if (hue in FOLIAGE_HUES && chroma > FOLIAGE_MIN_CHROMA) foliage++
            if (luma > PAPER_MIN_LUMA && chroma < PAPER_MAX_CHROMA) paper++
            if (luma < INK_MAX_LUMA && chroma < PAPER_MAX_CHROMA) ink++
            countEdge(image, x, y)
        }

        private fun countEdge(image: PixelBuffer, x: Int, y: Int) {
            if (x + 1 >= image.width || y + 1 >= image.height) return
            val here = Luma.ofPixel(image.pixels[y * image.width + x])
            val gx = Luma.ofPixel(image.pixels[y * image.width + x + 1]) - here
            val gy = Luma.ofPixel(image.pixels[(y + 1) * image.width + x]) - here
            val magnitude = abs(gx) + abs(gy)
            if (magnitude < STRONG_EDGE) return
            edges++
            if (min(abs(gx), abs(gy)) < max(abs(gx), abs(gy)) * AXIS_ALIGNED_RATIO) aligned++
        }

        fun features() = SceneFeatures(
            skinInCenter = fraction(skin, center),
            skinAtSides = fraction(skinSides, sides),
            skyInTop = fraction(sky, top),
            sandInBottom = fraction(sand, bottom),
            paperInBottom = fraction(paperBottom, bottom),
            foliage = fraction(foliage, all),
            paper = fraction(paper, all),
            ink = fraction(ink, all),
            warmInCenter = fraction(warm, center),
            axisAlignedEdges = fraction(aligned, edges),
            strongEdges = fraction(edges, all),
        )

        private fun fraction(part: Int, whole: Int) = if (whole == 0) 0f else part.toFloat() / whole
    }

    /** HSV hue in degrees (0 for greys). */
    fun hueOf(color: Int): Float {
        val r = Argb.red(color).toFloat()
        val g = Argb.green(color).toFloat()
        val b = Argb.blue(color).toFloat()
        val high = max(r, max(g, b))
        val chroma = high - min(r, min(g, b))
        if (chroma == 0f) return 0f
        val sector = when (high) {
            r -> ((g - b) / chroma).let { if (it < 0) it + 6f else it }
            g -> (b - r) / chroma + 2f
            else -> (r - g) / chroma + 4f
        }
        return sector * 60f
    }

    private fun unit(value: Float) = value.coerceIn(0f, 1f)

    // Regions (fractions of width/height).
    private const val CENTER_START = 0.25f
    private const val CENTER_END = 0.75f
    private const val TOP_REGION = 0.35f
    private const val BOTTOM_REGION = 0.6f
    private const val SIDE_REGION = 0.12f

    // Colour rules.
    private val SKY_HUES = 185f..250f
    private const val SKY_MIN_CHROMA = 0.08f
    private const val SKY_MIN_LUMA = 0.35f
    private const val OVERCAST_MAX_CHROMA = 0.06f
    private const val OVERCAST_MIN_LUMA = 0.7f
    private val SAND_HUES = 25f..55f
    private val SAND_CHROMA = 0.06f..0.35f
    private const val SAND_MIN_LUMA = 0.5f
    private val FOLIAGE_HUES = 65f..160f
    private const val FOLIAGE_MIN_CHROMA = 0.1f
    private const val PAPER_MIN_LUMA = 0.75f
    private const val PAPER_MAX_CHROMA = 0.1f
    private const val INK_MAX_LUMA = 0.35f
    private val WARM_HUES = 5f..50f
    private const val WARM_MIN_CHROMA = 0.25f
    private const val STRONG_EDGE = 0.12f
    private const val AXIS_ALIGNED_RATIO = 0.25f
    private const val WARM_CAST_RATIO = 1.15f

    // Score thresholds.
    private const val NIGHT_LUMA = 0.22f
    private const val NIGHT_LUMA_RANGE = 0.12f
    private const val LIGHTS_P99 = 0.6f
    private const val LOW_LIGHT_LUMA = 0.32f
    private const val LOW_LIGHT_RANGE = 0.12f
    private const val LOW_LIGHT_NOISE = 0.4f
    private const val DOCUMENT_PAPER = 0.5f
    private const val DOCUMENT_INK = 0.02f
    private const val DOCUMENT_MAX_CHROMA = 0.12f
    private const val DOCUMENT_CHROMA_RANGE = 0.06f
    private const val PAGE_BOTTOM_PAPER = 0.6f
    private const val PORTRAIT_SKIN = 0.25f
    private const val PORTRAIT_SKIN_CONTRAST = 0.2f
    private const val BEACH_SKY = 0.3f
    private const val BEACH_SAND = 0.3f
    private const val LANDSCAPE_SKY = 0.45f
    private const val LANDSCAPE_WEIGHT = 0.85f
    private const val NATURE_FOLIAGE = 0.45f
    private const val NATURE_SKY_PENALTY = 0.6f
    private const val FOOD_WARM = 0.45f
    private const val FOOD_MAX_SKY = 0.2f
    private const val ARCHITECTURE_MIN_ALIGNED = 0.55f
    private const val ARCHITECTURE_ALIGNED_RANGE = 0.3f
    private const val ARCHITECTURE_EDGES = 0.04f
    private const val INDOOR_WARM_SCORE = 0.6f
    private const val INDOOR_MIN_LUMA = 0.2f
    private const val INDOOR_LUMA_RANGE = 0.15f
}
