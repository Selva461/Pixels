package com.pixels.enhancer.domain.analysis

import com.pixels.enhancer.domain.analysis.AnalysisThresholds.CAST_DISTANCE_FOR_FULL_SCORE
import com.pixels.enhancer.domain.analysis.AnalysisThresholds.EXTREME_CHROMA
import com.pixels.enhancer.domain.analysis.AnalysisThresholds.HIGHLIGHT_CLIP_LUMA
import com.pixels.enhancer.domain.analysis.AnalysisThresholds.NEUTRAL_MAX_CHROMA
import com.pixels.enhancer.domain.analysis.AnalysisThresholds.NEUTRAL_MIN_LUMA
import com.pixels.enhancer.domain.analysis.AnalysisThresholds.NOISE_FLAT_REGION_FRACTION
import com.pixels.enhancer.domain.analysis.AnalysisThresholds.SHADOW_CLIP_LUMA
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.Srgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Chooses a sampling step so each statistic visits about [AnalysisThresholds.TARGET_SAMPLE_COUNT] pixels. */
internal object SampleGrid {
    fun step(pixelCount: Int, target: Int = AnalysisThresholds.TARGET_SAMPLE_COUNT): Int =
        max(1, sqrt(pixelCount.toDouble() / target).toInt())
}

data class LuminanceStats(
    val mean: Float,
    val percentiles: LuminancePercentiles,
    val highlightClipping: Float,
    val shadowClipping: Float,
)

object LuminanceStatistics {
    private const val BINS = 256

    fun compute(image: PixelBuffer): LuminanceStats {
        val histogram = IntArray(BINS)
        var sum = 0.0
        var samples = 0
        var highlightCount = 0
        var shadowCount = 0
        val step = SampleGrid.step(image.pixelCount)
        for (y in 0 until image.height step step) {
            val row = y * image.width
            for (x in 0 until image.width step step) {
                val luma = Luma.ofPixel(image.pixels[row + x])
                histogram[(luma * (BINS - 1) + 0.5f).toInt()]++
                sum += luma
                samples++
                if (luma >= HIGHLIGHT_CLIP_LUMA) highlightCount++
                if (luma <= SHADOW_CLIP_LUMA) shadowCount++
            }
        }
        return LuminanceStats(
            mean = (sum / samples).toFloat(),
            percentiles = LuminancePercentiles(
                p1 = percentile(histogram, samples, 0.01f),
                p5 = percentile(histogram, samples, 0.05f),
                p50 = percentile(histogram, samples, 0.50f),
                p95 = percentile(histogram, samples, 0.95f),
                p99 = percentile(histogram, samples, 0.99f),
            ),
            highlightClipping = highlightCount.toFloat() / samples,
            shadowClipping = shadowCount.toFloat() / samples,
        )
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Float {
        val threshold = total * fraction
        var cumulative = 0
        for (bin in histogram.indices) {
            cumulative += histogram[bin]
            if (cumulative >= threshold) return bin / (BINS - 1).toFloat()
        }
        return 1f
    }
}

data class SaturationStats(val meanChroma: Float, val extremeChromaFraction: Float)

object SaturationEstimator {
    fun compute(image: PixelBuffer): SaturationStats {
        var chromaSum = 0.0
        var extremeCount = 0
        var samples = 0
        val step = SampleGrid.step(image.pixelCount)
        for (y in 0 until image.height step step) {
            val row = y * image.width
            for (x in 0 until image.width step step) {
                val chroma = chromaOf(image.pixels[row + x])
                chromaSum += chroma
                if (chroma > EXTREME_CHROMA) extremeCount++
                samples++
            }
        }
        return SaturationStats((chromaSum / samples).toFloat(), extremeCount.toFloat() / samples)
    }

    fun chromaOf(color: Int): Float {
        val red = Argb.red(color)
        val green = Argb.green(color)
        val blue = Argb.blue(color)
        return (max(red, max(green, blue)) - min(red, min(green, blue))) / Argb.CHANNEL_MAX.toFloat()
    }
}

data class CastEstimate(val balance: ChannelBalance, val score: Float)

/**
 * Grey-edge colour constancy (van de Weijer et al.): the average edge contrast in a scene is
 * close to achromatic, so per-channel edge energy reveals the illuminant. Edges are taken only
 * from low-chroma, unclipped pixels — a green lawn or a blown sky would otherwise read as a cast.
 * On test scenes this separated a real tungsten cast far better than grey-world averaging.
 */
object ColorCastEstimator {

    fun estimate(image: PixelBuffer): CastEstimate {
        if (image.width < 2 || image.height < 2) return CastEstimate(ChannelBalance.NEUTRAL, 0f)
        val step = SampleGrid.step(image.pixelCount)
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var qualifying = 0
        var visited = 0
        for (y in 0 until image.height - 1 step step) {
            for (x in 0 until image.width - 1 step step) {
                visited++
                val index = y * image.width + x
                val center = image.pixels[index]
                val right = image.pixels[index + 1]
                val below = image.pixels[index + image.width]
                if (!isReference(center, right, below)) continue
                red += edgeEnergy(Argb.red(center), Argb.red(right), Argb.red(below))
                green += edgeEnergy(Argb.green(center), Argb.green(right), Argb.green(below))
                blue += edgeEnergy(Argb.blue(center), Argb.blue(right), Argb.blue(below))
                qualifying++
            }
        }
        val fraction = if (visited == 0) 0f else qualifying.toFloat() / visited
        val total = red + green + blue
        if (total <= MIN_TOTAL_EDGE_ENERGY) return CastEstimate(ChannelBalance.NEUTRAL.copy(sampleFraction = fraction), 0f)
        val mean = total / 3.0
        val balance = ChannelBalance((red / mean).toFloat(), (green / mean).toFloat(), (blue / mean).toFloat(), fraction)
        return CastEstimate(balance, castScore(balance))
    }

    fun castScore(balance: ChannelBalance): Float {
        val total = balance.red + balance.green + balance.blue
        if (total <= 0f) return 0f
        val third = 1f / 3f
        val redShift = balance.red / total - third
        val greenShift = balance.green / total - third
        val blueShift = balance.blue / total - third
        val distance = sqrt(redShift * redShift + greenShift * greenShift + blueShift * blueShift)
        return (distance / CAST_DISTANCE_FOR_FULL_SCORE).coerceIn(0f, 1f)
    }

    private fun isReference(center: Int, right: Int, below: Int): Boolean {
        if (SaturationEstimator.chromaOf(center) > NEUTRAL_MAX_CHROMA) return false
        if (Luma.ofPixel(center) < NEUTRAL_MIN_LUMA) return false
        return maxChannel(center) < CLIPPED_CHANNEL && maxChannel(right) < CLIPPED_CHANNEL && maxChannel(below) < CLIPPED_CHANNEL
    }

    private fun edgeEnergy(center: Int, right: Int, below: Int): Double {
        val value = Srgb.toLinear(center)
        val dx = Srgb.toLinear(right) - value
        val dy = Srgb.toLinear(below) - value
        return sqrt((dx * dx + dy * dy).toDouble())
    }

    private fun maxChannel(color: Int) = max(Argb.red(color), max(Argb.green(color), Argb.blue(color)))

    /** 250 — a clipped channel has lost its true value, so its edges say nothing about the light. */
    private const val CLIPPED_CHANNEL = 250
    private const val MIN_TOTAL_EDGE_ENERGY = 1e-6
}

/**
 * Immerkær-style noise estimation: the 3×3 kernel below cancels flat areas and linear gradients,
 * so its response is mostly noise. Only the lowest-gradient pixels are used (texture is not
 * noise) and the median absolute response is used for robustness against residual edges.
 */
object NoiseEstimator {
    /** Kernel response std-dev is 6σ (sqrt of the sum of squared weights 1,−2,1,−2,4,…). */
    private const val KERNEL_GAIN = 6f

    /** median(|X|) = 0.6745σ for Gaussian X. */
    private const val MEDIAN_ABS_TO_SIGMA = 0.6745f

    fun estimateSigma(image: PixelBuffer): Float {
        if (image.width < 3 || image.height < 3) return 0f
        val rowStep = rowStep(image)
        val responses = FloatArrayBuilder()
        val gradients = FloatArrayBuilder()
        val above = FloatArray(image.width)
        val center = FloatArray(image.width)
        val below = FloatArray(image.width)
        var y = 1
        while (y < image.height - 1) {
            lumaRow(image, y - 1, above)
            lumaRow(image, y, center)
            lumaRow(image, y + 1, below)
            collectRow(above, center, below, responses, gradients)
            y += rowStep
        }
        return medianResponseOfFlatPixels(responses.toArray(), gradients.toArray()) / (KERNEL_GAIN * MEDIAN_ABS_TO_SIGMA)
    }

    private fun rowStep(image: PixelBuffer): Int =
        max(1, (image.width.toLong() * image.height / AnalysisThresholds.TARGET_SAMPLE_COUNT).toInt())

    private fun collectRow(
        above: FloatArray,
        center: FloatArray,
        below: FloatArray,
        responses: FloatArrayBuilder,
        gradients: FloatArrayBuilder,
    ) {
        for (x in 1 until center.size - 1) {
            val value = center[x]
            if (value <= SHADOW_CLIP_LUMA || value >= HIGHLIGHT_CLIP_LUMA) continue
            val response = above[x - 1] - 2 * above[x] + above[x + 1] -
                2 * center[x - 1] + 4 * value - 2 * center[x + 1] +
                below[x - 1] - 2 * below[x] + below[x + 1]
            val gradientX = (above[x + 1] + 2 * center[x + 1] + below[x + 1]) - (above[x - 1] + 2 * center[x - 1] + below[x - 1])
            val gradientY = (below[x - 1] + 2 * below[x] + below[x + 1]) - (above[x - 1] + 2 * above[x] + above[x + 1])
            responses.add(abs(response))
            gradients.add(abs(gradientX) + abs(gradientY))
        }
    }

    private fun medianResponseOfFlatPixels(responses: FloatArray, gradients: FloatArray): Float {
        if (responses.isEmpty()) return 0f
        val gradientLimit = gradients.copyOf().apply { sort() }[((gradients.size - 1) * NOISE_FLAT_REGION_FRACTION).toInt()]
        val flat = FloatArrayBuilder()
        for (index in responses.indices) {
            if (gradients[index] <= gradientLimit) flat.add(responses[index])
        }
        val flatResponses = flat.toArray().apply { sort() }
        return flatResponses[flatResponses.size / 2]
    }
}

/**
 * Crete et al. (2007) no-reference blur metric: re-blur the image and measure how much
 * neighbouring-pixel variation is lost. A sharp image loses a lot; an already blurry one barely
 * changes. Returns sharpness = 1 − blur.
 */
object SharpnessEstimator {
    /** Width of the re-blur averaging window used by the metric. */
    private const val BLUR_TAPS = 9
    private const val BEFORE = BLUR_TAPS / 2 + 1
    private const val AFTER = BLUR_TAPS / 2

    fun estimate(image: PixelBuffer): Float {
        if (image.width <= BLUR_TAPS || image.height <= BLUR_TAPS) return 1f
        val horizontal = horizontalBlur(image)
        val vertical = verticalBlur(image)
        return 1f - max(horizontal, vertical)
    }

    private fun horizontalBlur(image: PixelBuffer): Float {
        val row = FloatArray(image.width)
        val totals = BlurTotals()
        val step = rowStep(image)
        for (y in 0 until image.height step step) {
            lumaRow(image, y, row)
            for (x in BEFORE until image.width - AFTER) {
                totals.add(row[x] - row[x - 1], (row[x + AFTER] - row[x - BEFORE]) / BLUR_TAPS)
            }
        }
        return totals.blur()
    }

    private fun verticalBlur(image: PixelBuffer): Float {
        val totals = BlurTotals()
        val step = rowStep(image)
        val width = image.width
        var y = BEFORE
        while (y < image.height - AFTER) {
            for (x in 0 until width) {
                val current = Luma.ofPixel(image.pixels[y * width + x])
                val previous = Luma.ofPixel(image.pixels[(y - 1) * width + x])
                val ahead = Luma.ofPixel(image.pixels[(y + AFTER) * width + x])
                val behind = Luma.ofPixel(image.pixels[(y - BEFORE) * width + x])
                totals.add(current - previous, (ahead - behind) / BLUR_TAPS)
            }
            y += step
        }
        return totals.blur()
    }

    private fun rowStep(image: PixelBuffer): Int =
        max(1, (image.width.toLong() * image.height / AnalysisThresholds.TARGET_SAMPLE_COUNT).toInt())

    private class BlurTotals {
        private var originalVariation = 0.0
        private var lostVariation = 0.0

        fun add(originalDifference: Float, blurredDifference: Float) {
            val original = abs(originalDifference)
            originalVariation += original
            lostVariation += max(0f, original - abs(blurredDifference))
        }

        /** No variation at all (flat image) counts as "nothing to sharpen", i.e. blur 0. */
        fun blur(): Float = if (originalVariation <= 0.0) 0f else ((originalVariation - lostVariation) / originalVariation).toFloat()
    }
}

internal fun lumaRow(image: PixelBuffer, y: Int, into: FloatArray) {
    val row = y * image.width
    for (x in 0 until image.width) into[x] = Luma.ofPixel(image.pixels[row + x])
}

/** Growable float array — avoids boxing when collecting hundreds of thousands of samples. */
internal class FloatArrayBuilder(initialCapacity: Int = 1024) {
    private var data = FloatArray(initialCapacity)
    private var size = 0

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
