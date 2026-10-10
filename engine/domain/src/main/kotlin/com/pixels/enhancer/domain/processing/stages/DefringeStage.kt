package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.LumaPlane
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.smoothstep
import com.pixels.enhancer.domain.processing.ImageFrame
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import com.pixels.enhancer.domain.processing.ops.ChromaOps
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Defringe (manual only): removes purple and green colour fringes that lenses leave along
 * high-contrast edges. A pixel loses colour only when it is purple (or green) AND sits next to a
 * strong brightness edge, so purple flowers or green leaves away from such edges keep their colour.
 */
class DefringeStage : ProcessingStage {
    override val id = StageIds.DEFRINGE
    override val displayName = "Defringe"

    override fun isEnabled(context: ProcessingContext) = context.plan.defringePurple.enabled || context.plan.defringeGreen.enabled

    override fun margin(context: ProcessingContext, frame: ImageFrame) = radiusFor(frame.fullWidth, frame.fullHeight)

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val purple = context.effectiveAmount(id, context.plan.defringePurple).coerceIn(0f, 1f)
        val green = context.effectiveAmount(id, context.plan.defringeGreen).coerceIn(0f, 1f)
        if (purple == 0f && green == 0f) return input
        val frame = context.frameOf(input)
        val radius = radiusFor(frame.fullWidth, frame.fullHeight)
        val range = localRange(LumaPlane.extract(input), input.width, input.height, radius)
        val pixels = input.pixels
        for (index in pixels.indices) {
            val edge = smoothstep(EDGE_LOW, EDGE_HIGH, range[index])
            if (edge == 0f) continue
            val color = pixels[index]
            val hue = hueOf(color) ?: continue
            val strength = (purple * bandWeight(hue, PURPLE_CENTER) + green * bandWeight(hue, GREEN_CENTER)).coerceAtMost(1f) * edge
            if (strength > 0f) pixels[index] = ChromaOps.scaleChroma(color, 1f - strength)
        }
        return input
    }

    /** Max − min luma in a (2r+1)² window, computed separably. */
    private fun localRange(luma: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val rowMax = FloatArray(luma.size)
        val rowMin = FloatArray(luma.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var hi = -1f
                var lo = 2f
                for (dx in max(0, x - radius)..min(width - 1, x + radius)) {
                    val v = luma[row + dx]
                    if (v > hi) hi = v
                    if (v < lo) lo = v
                }
                rowMax[row + x] = hi
                rowMin[row + x] = lo
            }
        }
        val out = FloatArray(luma.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var hi = -1f
                var lo = 2f
                for (dy in max(0, y - radius)..min(height - 1, y + radius)) {
                    val i = dy * width + x
                    if (rowMax[i] > hi) hi = rowMax[i]
                    if (rowMin[i] < lo) lo = rowMin[i]
                }
                out[y * width + x] = hi - lo
            }
        }
        return out
    }

    /** Hue in degrees, or null for colours too grey to have a meaningful hue. */
    private fun hueOf(color: Int): Float? {
        val r = Argb.red(color) / CHANNEL_MAX
        val g = Argb.green(color) / CHANNEL_MAX
        val b = Argb.blue(color) / CHANNEL_MAX
        val hi = max(r, max(g, b))
        val chroma = hi - min(r, min(g, b))
        if (chroma < MIN_CHROMA) return null
        val sector = when (hi) {
            r -> ((g - b) / chroma).let { if (it < 0f) it + SECTORS else it }
            g -> (b - r) / chroma + 2f
            else -> (r - g) / chroma + 4f
        }
        return sector * DEGREES_PER_SECTOR
    }

    /** 1 within [HALF_WIDTH] of [center] (on the hue circle), fading to 0 over [SOFT]. */
    private fun bandWeight(hue: Float, center: Float): Float {
        var distance = abs(hue - center)
        if (distance > HALF_CIRCLE) distance = FULL_CIRCLE - distance
        return 1f - smoothstep(HALF_WIDTH, HALF_WIDTH + SOFT, distance)
    }

    companion object {
        private const val CHANNEL_MAX = 255f
        private const val MIN_CHROMA = 0.06f
        private const val SECTORS = 6f
        private const val DEGREES_PER_SECTOR = 60f
        private const val FULL_CIRCLE = 360f
        private const val HALF_CIRCLE = 180f
        const val PURPLE_CENTER = 290f
        const val GREEN_CENTER = 115f
        private const val HALF_WIDTH = 32f
        private const val SOFT = 18f

        /** Edges with a local luma range above ~0.15 start to count; ~0.4 counts fully. */
        private const val EDGE_LOW = 0.15f
        private const val EDGE_HIGH = 0.4f
        private const val RADIUS_FRACTION_OF_SHORT_EDGE = 0.0015f

        fun radiusFor(width: Int, height: Int): Int = max(1, (min(width, height) * RADIUS_FRACTION_OF_SHORT_EDGE).roundToInt())
    }
}
