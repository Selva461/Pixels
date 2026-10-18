package com.pixels.enhancer.domain.processing.ops

import com.pixels.enhancer.domain.image.BoxBlur
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Splits log luminance `s = log2(Y / 0.18)` into an edge-aware base and the detail on top of it:
 * `base = guided(s, s, r, ε)`, `detail = s − base`. Tone moves go on the base only and the detail is
 * added back afterwards, so compressing highlights or lifting a region never flattens fabric, print
 * or skin texture.
 *
 * The self-guided filter's coefficients are smooth, so they are fitted at a reduced size and
 * applied to the full-size signal ("fast guided filter"): `base = ā·s + b̄`. Edges are kept at
 * full resolution because ā multiplies the pixel's own value.
 */
object BaseDetail {
    /** Mid-grey: s = 0 at 18 % luminance. */
    const val MID_GREY = 0.18f

    /** Floor for luminance before the log, so black pixels give a finite value (about −11 stops). */
    const val MIN_LUMINANCE = 1e-4f

    /** Window radius at 3.7 MP; scaled with the square root of the pixel count. */
    const val REFERENCE_RADIUS = 28f
    private const val REFERENCE_MEGAPIXELS = 3.7f

    /** Variance in stops² that counts as an edge: steps of more than about half a stop stay in the base. */
    const val EPSILON = 0.25f

    /** Radius of the low-resolution fit; the subsampling factor is chosen to reach it. */
    private const val LOW_RADIUS = 7

    /** The coarse plane averages over this many base radii: whole objects, not their structure. */
    private const val COARSE_SCALE = 3

    /** Window radius for an image of [width] × [height]. */
    fun radiusFor(width: Int, height: Int): Int =
        max(1, (REFERENCE_RADIUS * sqrt(width.toFloat() * height / 1e6f / REFERENCE_MEGAPIXELS)).roundToInt())

    /**
     * Returns detail = s − base for the log-luminance plane [s] ([width] × [height]), with window
     * [radius] and regulariser [epsilon] (both in the units of [s]).
     */
    fun detail(s: FloatArray, width: Int, height: Int, radius: Int = radiusFor(width, height), epsilon: Float = EPSILON): FloatArray {
        require(s.size == width * height)
        val factor = max(1, (radius.toFloat() / LOW_RADIUS).roundToInt())
        val lowRadius = max(1, (radius.toFloat() / factor).roundToInt())
        val lw = (width + factor - 1) / factor
        val lh = (height + factor - 1) / factor
        val n = lw * lh

        // Block means of s.
        val mean = FloatArray(n)
        val count = FloatArray(n)
        for (y in 0 until height) {
            val row = (y / factor) * lw
            for (x in 0 until width) {
                val j = row + x / factor
                mean[j] += s[y * width + x]
                count[j] += 1f
            }
        }
        for (j in 0 until n) mean[j] /= count[j]

        val scratch = FloatArray(n)
        val square = FloatArray(n) { mean[it] * mean[it] }
        val m = mean.copyOf().also { BoxBlur.blur(it, it, lw, lh, lowRadius, scratch) }
        BoxBlur.blur(square, square, lw, lh, lowRadius, scratch)
        val a = square
        val b = mean
        for (j in 0 until n) {
            val variance = (square[j] - m[j] * m[j]).coerceAtLeast(0f)
            a[j] = variance / (variance + epsilon)
            b[j] = m[j] - a[j] * m[j]
        }
        BoxBlur.blur(a, a, lw, lh, lowRadius, scratch)
        BoxBlur.blur(b, b, lw, lh, lowRadius, scratch)

        // Bilinear lookup of ā, b̄ at full size; detail = s − (ā·s + b̄).
        val out = FloatArray(width * height)
        val x0 = IntArray(width)
        val x1 = IntArray(width)
        val tx = FloatArray(width)
        for (x in 0 until width) {
            val fx = ((x + 0.5f) / factor - 0.5f).coerceIn(0f, (lw - 1).toFloat())
            x0[x] = fx.toInt()
            x1[x] = min(x0[x] + 1, lw - 1)
            tx[x] = fx - x0[x]
        }
        for (y in 0 until height) {
            val fy = ((y + 0.5f) / factor - 0.5f).coerceIn(0f, (lh - 1).toFloat())
            val r0 = fy.toInt() * lw
            val r1 = min(fy.toInt() + 1, lh - 1) * lw
            val ty = fy - fy.toInt()
            for (x in 0 until width) {
                val t = tx[x]
                fun lerp(p: FloatArray): Float {
                    val top = p[r0 + x0[x]] + (p[r0 + x1[x]] - p[r0 + x0[x]]) * t
                    val bottom = p[r1 + x0[x]] + (p[r1 + x1[x]] - p[r1 + x0[x]]) * t
                    return top + (bottom - top) * ty
                }
                val i = y * width + x
                out[i] = s[i] - (lerp(a) * s[i] + lerp(b))
            }
        }
        return out
    }

    /**
     * A plain local average of [s] over about [COARSE_SCALE] base radii: the brightness of the
     * object a pixel sits in rather than of the pixel. A tone move keyed on it moves a whole
     * shirt or face by one amount, so the structure inside stays as it was.
     */
    fun coarse(s: FloatArray, width: Int, height: Int, radius: Int = radiusFor(width, height)): FloatArray {
        require(s.size == width * height)
        val factor = max(1, (radius.toFloat() / LOW_RADIUS).roundToInt())
        val lw = (width + factor - 1) / factor
        val lh = (height + factor - 1) / factor
        val low = FloatArray(lw * lh)
        val count = FloatArray(lw * lh)
        for (y in 0 until height) {
            val row = (y / factor) * lw
            for (x in 0 until width) {
                low[row + x / factor] += s[y * width + x]
                count[row + x / factor] += 1f
            }
        }
        for (j in low.indices) low[j] /= count[j]
        BoxBlur.blur(low, low, lw, lh, max(1, (radius * COARSE_SCALE.toFloat() / factor).roundToInt()), FloatArray(lw * lh))
        val out = FloatArray(width * height)
        upsample(low, lw, lh, factor, width, height) { i, v -> out[i] = v }
        return out
    }

    /** Bilinear lookup of the low-resolution plane [p] at every full-size pixel. */
    private inline fun upsample(p: FloatArray, lw: Int, lh: Int, factor: Int, width: Int, height: Int, sink: (Int, Float) -> Unit) {
        for (y in 0 until height) {
            val fy = ((y + 0.5f) / factor - 0.5f).coerceIn(0f, (lh - 1).toFloat())
            val r0 = fy.toInt() * lw
            val r1 = min(fy.toInt() + 1, lh - 1) * lw
            val ty = fy - fy.toInt()
            for (x in 0 until width) {
                val fx = ((x + 0.5f) / factor - 0.5f).coerceIn(0f, (lw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, lw - 1)
                val tx = fx - x0
                val top = p[r0 + x0] + (p[r0 + x1] - p[r0 + x0]) * tx
                val bottom = p[r1 + x0] + (p[r1 + x1] - p[r1 + x0]) * tx
                sink(y * width + x, top + (bottom - top) * ty)
            }
        }
    }
}
