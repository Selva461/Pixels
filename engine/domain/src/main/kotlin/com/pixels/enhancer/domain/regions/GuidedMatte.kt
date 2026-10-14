package com.pixels.enhancer.domain.regions

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.BoxBlur
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.smoothstep
import kotlin.math.min

/**
 * A mask refined by a colour-guided filter (He et al.): per pixel, mask ≈ aR·r + aG·g + aB·b + b0
 * where r, g, b are the photo's own (encoded, 0..1) colours. The coefficients are smooth, so they
 * can be computed at a small size and evaluated on the photo at any size ("fast guided filter"):
 * the mask edge then lands on the photo's edge at full resolution instead of being a stretched
 * ramp. Nothing is blurred outward — where the photo has an edge the mask has one too.
 */
class GuidedMatte private constructor(
    val width: Int,
    val height: Int,
    private val aR: FloatArray,
    private val aG: FloatArray,
    private val aB: FloatArray,
    private val b0: FloatArray,
) {
    /** The refined mask at the model's own size, clipped to 0..1. */
    fun evaluate(guide: PixelBuffer): FloatArray {
        require(guide.width == width && guide.height == height)
        return FloatArray(width * height) { i -> valueAt(i, guide.pixels[i]) }
    }

    /** Mask value at model pixel [i] for colour [color]. */
    private fun valueAt(i: Int, color: Int): Float = clip(
        aR[i] * Argb.red(color) / 255f + aG[i] * Argb.green(color) / 255f + aB[i] * Argb.blue(color) / 255f + b0[i],
    )

    /**
     * Calls [sink] with (pixel index, mask value) for every pixel of [guide], which may be any
     * size: coefficients are resized bilinearly and applied to the guide's own colours.
     */
    inline fun forEachPixel(guide: PixelBuffer, sink: (Int, Float) -> Unit) {
        val sampler = Sampler(guide.width, guide.height)
        for (y in 0 until guide.height) {
            sampler.row(y)
            for (x in 0 until guide.width) {
                val i = y * guide.width + x
                sink(i, sampler.valueAt(x, guide.pixels[i]))
            }
        }
    }

    /** Bilinear lookup of the coefficients for one output size; [row] then [valueAt] along it. */
    inner class Sampler(outWidth: Int, private val outHeight: Int) {
        private val x0 = IntArray(outWidth)
        private val x1 = IntArray(outWidth)
        private val tx = FloatArray(outWidth)
        private var r0 = 0
        private var r1 = 0
        private var ty = 0f

        init {
            val sx = width.toFloat() / outWidth
            for (x in 0 until outWidth) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (width - 1).toFloat())
                x0[x] = fx.toInt()
                x1[x] = min(x0[x] + 1, width - 1)
                tx[x] = fx - x0[x]
            }
        }

        fun row(y: Int) {
            val fy = ((y + 0.5f) * height.toFloat() / outHeight - 0.5f).coerceIn(0f, (height - 1).toFloat())
            val y0 = fy.toInt()
            r0 = y0 * width
            r1 = min(y0 + 1, height - 1) * width
            ty = fy - y0
        }

        fun valueAt(x: Int, color: Int): Float {
            val a = r0 + x0[x]
            val b = r0 + x1[x]
            val c = r1 + x0[x]
            val d = r1 + x1[x]
            val t = tx[x]
            fun lerp(p: FloatArray): Float {
                val top = p[a] + (p[b] - p[a]) * t
                val bottom = p[c] + (p[d] - p[c]) * t
                return top + (bottom - top) * ty
            }
            return clip(
                lerp(aR) * Argb.red(color) / 255f + lerp(aG) * Argb.green(color) / 255f + lerp(aB) * Argb.blue(color) / 255f + lerp(b0),
            )
        }
    }

    companion object {
        private fun clip(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v

        /**
         * A guided filter keeps each window's mean, so on its own it spreads a coarse mask's spill
         * along colour rather than removing it. Here a wide pass ([radius]) first moves the coarse
         * edge onto the colour edge, then each of [passes] narrow passes ([snapRadius]) re-sharpens
         * the result and refits it, so the fractional spill on the wrong side falls to zero.
         */
        fun snap(mask: FloatArray, guide: PixelBuffer, radius: Int, snapRadius: Int, passes: Int, epsilon: Float): GuidedMatte {
            var model = fit(mask, guide, radius, epsilon)
            repeat(passes) {
                val sharpened = model.evaluate(guide)
                for (i in sharpened.indices) sharpened[i] = smoothstep(SNAP_LOW, SNAP_HIGH, sharpened[i])
                model = fit(sharpened, guide, snapRadius, epsilon)
            }
            return model
        }

        private const val SNAP_LOW = 0.25f
        private const val SNAP_HIGH = 0.75f

        /**
         * Fits the colour guided filter of [mask] (0..1, same size as [guide]) with window [radius]
         * and regulariser [epsilon]: small epsilon follows colour edges closely.
         */
        fun fit(mask: FloatArray, guide: PixelBuffer, radius: Int, epsilon: Float): GuidedMatte {
            val w = guide.width
            val h = guide.height
            val n = w * h
            require(mask.size == n)
            val scratch = FloatArray(n)
            fun box(values: FloatArray): FloatArray = values.also { BoxBlur.blur(it, it, w, h, radius, scratch) }
            val r = FloatArray(n) { Argb.red(guide.pixels[it]) / 255f }
            val g = FloatArray(n) { Argb.green(guide.pixels[it]) / 255f }
            val b = FloatArray(n) { Argb.blue(guide.pixels[it]) / 255f }
            val mR = box(r.copyOf())
            val mG = box(g.copyOf())
            val mB = box(b.copyOf())
            val mP = box(mask.copyOf())
            val cR = box(FloatArray(n) { r[it] * mask[it] })
            val cG = box(FloatArray(n) { g[it] * mask[it] })
            val cB = box(FloatArray(n) { b[it] * mask[it] })
            val vRR = box(FloatArray(n) { r[it] * r[it] })
            val vRG = box(FloatArray(n) { r[it] * g[it] })
            val vRB = box(FloatArray(n) { r[it] * b[it] })
            val vGG = box(FloatArray(n) { g[it] * g[it] })
            val vGB = box(FloatArray(n) { g[it] * b[it] })
            val vBB = box(FloatArray(n) { b[it] * b[it] })
            // Reuse the guide planes for the coefficients.
            val aR = r
            val aG = g
            val aB = b
            val b0 = FloatArray(n)
            for (i in 0 until n) {
                // Covariance of the guide in the window (+ε on the diagonal) and of guide with mask.
                val xx = vRR[i] - mR[i] * mR[i] + epsilon
                val xy = vRG[i] - mR[i] * mG[i]
                val xz = vRB[i] - mR[i] * mB[i]
                val yy = vGG[i] - mG[i] * mG[i] + epsilon
                val yz = vGB[i] - mG[i] * mB[i]
                val zz = vBB[i] - mB[i] * mB[i] + epsilon
                val pr = cR[i] - mR[i] * mP[i]
                val pg = cG[i] - mG[i] * mP[i]
                val pb = cB[i] - mB[i] * mP[i]
                // Symmetric 3×3 solve by cofactors; positive definite thanks to ε.
                val c00 = yy * zz - yz * yz
                val c01 = xz * yz - xy * zz
                val c02 = xy * yz - xz * yy
                val c11 = xx * zz - xz * xz
                val c12 = xy * xz - xx * yz
                val c22 = xx * yy - xy * xy
                val det = xx * c00 + xy * c01 + xz * c02
                val inv = 1f / det
                aR[i] = (c00 * pr + c01 * pg + c02 * pb) * inv
                aG[i] = (c01 * pr + c11 * pg + c12 * pb) * inv
                aB[i] = (c02 * pr + c12 * pg + c22 * pb) * inv
                b0[i] = mP[i] - aR[i] * mR[i] - aG[i] * mG[i] - aB[i] * mB[i]
            }
            return GuidedMatte(w, h, box(aR), box(aG), box(aB), box(b0))
        }
    }
}
