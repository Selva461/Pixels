package com.pixels.enhancer.domain.geometry

import com.pixels.enhancer.domain.image.LumaPlane
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.PixelResampler
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Automatic levelling and upright correction from the photo's own straight edges (no AI): strong
 * edges within a few degrees of vertical or horizontal are assumed to be meant straight, as in
 * buildings, horizons and door frames.
 */
object AutoGeometry {

    /**
     * Straighten angle (degrees, the [Geometry.straightened] convention) that levels the photo, or
     * 0 when there are too few near-level edges to tell.
     */
    fun levelDegrees(image: PixelBuffer): Float {
        val small = PixelResampler.downscaleToFit(image, ANALYSIS_EDGE)
        val deviations = ArrayList<Float>()
        val weights = ArrayList<Float>()
        forEachEdge(small, crop = 0f) { deviation, weight, _ ->
            if (abs(deviation) < MAX_LEVEL_DEGREES) {
                deviations += deviation
                weights += weight
            }
        }
        if (weights.sum() < MIN_EDGE_WEIGHT) return 0f
        // Histogram with bins centred on whole multiples of BIN_DEGREES, smoothed; its peak is the
        // tilt most straight edges share, refined by the weighted mean of edges near the peak.
        val half = BINS / 2
        val histogram = FloatArray(BINS + 1)
        deviations.forEachIndexed { i, d -> histogram[((d / BIN_DEGREES).roundToInt() + half).coerceIn(0, BINS)] += weights[i] }
        val smoothed = FloatArray(BINS + 1) { i -> (-SMOOTH..SMOOTH).sumOf { k -> histogram[(i + k).coerceIn(0, BINS)].toDouble() }.toFloat() }
        val peak = (smoothed.indices.maxBy { smoothed[it] } - half) * BIN_DEGREES
        var sum = 0f
        var weightSum = 0f
        deviations.forEachIndexed { i, d ->
            if (abs(d - peak) <= PEAK_WINDOW) {
                sum += d * weights[i]
                weightSum += weights[i]
            }
        }
        val tilt = if (weightSum > 0f) sum / weightSum else peak
        // A tilt of the edges is undone by rotating the other way.
        return if (abs(tilt) < MIN_CORRECTION) 0f else -tilt
    }

    /**
     * Vertical and horizontal perspective (slider values) that make near-vertical edges vertical
     * and near-horizontal edges horizontal, searched on a small copy of the photo. Keeps any
     * [rotate] already chosen.
     */
    fun upright(image: PixelBuffer, lens: LensCorrection = LensCorrection.NONE, rotate: Float = 0f): Perspective {
        val small = PixelResampler.downscaleToFit(image, SEARCH_EDGE)
        var best = Perspective(rotate = rotate)
        best = best.copy(vertical = search { v -> verticalError(OpticsWarp.apply(small, lens, best.copy(vertical = v))) })
        best = best.copy(horizontal = search { h -> horizontalError(OpticsWarp.apply(small, lens, best.copy(horizontal = h))) })
        return best
    }

    /** Coarse-to-fine 1-D search over −1..1; returns 0 unless it clearly beats no correction. */
    private fun search(error: (Float) -> Float): Float {
        val baseline = error(0f)
        var bestValue = 0f
        var bestError = baseline
        var low = -1f
        var high = 1f
        var step = COARSE_STEP
        repeat(REFINEMENTS) {
            var v = low
            while (v <= high + 1e-4f) {
                val e = error(v)
                if (e < bestError) {
                    bestError = e
                    bestValue = v
                }
                v += step
            }
            low = (bestValue - step).coerceAtLeast(-1f)
            high = (bestValue + step).coerceAtMost(1f)
            step /= REFINE_FACTOR
        }
        return if (bestError < baseline * REQUIRED_IMPROVEMENT) bestValue else 0f
    }

    private fun verticalError(image: PixelBuffer): Float = deviationError(image, vertical = true)

    private fun horizontalError(image: PixelBuffer): Float = deviationError(image, vertical = false)

    /** Weighted mean squared deviation (degrees²) of near-vertical or near-horizontal edges. */
    private fun deviationError(image: PixelBuffer, vertical: Boolean): Float {
        var sum = 0f
        var weightSum = 0f
        forEachEdge(image, crop = EDGE_CROP) { deviation, weight, isVertical ->
            if (isVertical == vertical && abs(deviation) < MAX_UPRIGHT_DEGREES) {
                sum += deviation * deviation * weight
                weightSum += weight
            }
        }
        return if (weightSum < MIN_EDGE_WEIGHT) Float.MAX_VALUE else sum / weightSum
    }

    /**
     * Calls [action] for every pixel on a strong edge with the edge's deviation (degrees) from the
     * nearest of vertical/horizontal, positive = rotated clockwise on screen.
     */
    private inline fun forEachEdge(image: PixelBuffer, crop: Float, action: (deviation: Float, weight: Float, isVertical: Boolean) -> Unit) {
        val luma = LumaPlane.extract(image)
        val w = image.width
        val h = image.height
        val x0 = maxOf(1, (w * crop).toInt())
        val y0 = maxOf(1, (h * crop).toInt())
        for (y in y0 until h - y0) {
            for (x in x0 until w - x0) {
                val i = y * w + x
                // Sobel gradient.
                val gx = (luma[i - w + 1] + 2 * luma[i + 1] + luma[i + w + 1]) - (luma[i - w - 1] + 2 * luma[i - 1] + luma[i + w - 1])
                val gy = (luma[i + w - 1] + 2 * luma[i + w] + luma[i + w + 1]) - (luma[i - w - 1] + 2 * luma[i - w] + luma[i - w + 1])
                val magnitude = sqrt(gx * gx + gy * gy)
                if (magnitude < MIN_GRADIENT) continue
                // Edge direction is perpendicular to the gradient; angle in degrees, 0 = horizontal edge.
                var angle = Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())).toFloat() - 90f
                angle = ((angle % 180f) + 180f) % 180f
                // angle 0/180 = horizontal edge, 90 = vertical edge (y grows downward).
                val isVertical = abs(angle - 90f) < 45f
                val deviation = if (isVertical) angle - 90f else if (angle > 90f) angle - 180f else angle
                action(deviation, magnitude, isVertical)
            }
        }
    }

    private const val ANALYSIS_EDGE = 640
    private const val SEARCH_EDGE = 256
    private const val MAX_LEVEL_DEGREES = 10f
    private const val MAX_UPRIGHT_DEGREES = 20f
    private const val BIN_DEGREES = 0.25f
    private const val BINS = 80
    private const val SMOOTH = 2
    private const val PEAK_WINDOW = 1f
    private const val MIN_CORRECTION = 0.2f
    private const val MIN_GRADIENT = 0.6f
    private const val MIN_EDGE_WEIGHT = 30f
    private const val EDGE_CROP = 0.04f
    private const val COARSE_STEP = 0.1f
    private const val REFINE_FACTOR = 4f
    private const val REFINEMENTS = 3
    private const val REQUIRED_IMPROVEMENT = 0.8f
}
