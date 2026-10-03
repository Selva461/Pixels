package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import kotlin.math.roundToInt

/**
 * Creative film grain (manual only): monochrome noise, strongest in midtones like real film.
 * Each pixel's noise comes from a hash of its position in the full image, so the same edit always
 * renders the same grain — including when the image is processed in tiles.
 */
class GrainStage : ProcessingStage {
    override val id = StageIds.GRAIN
    override val displayName = "Grain"

    override fun isEnabled(context: ProcessingContext) = context.plan.grain.enabled

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val sigma = context.effectiveAmount(id, context.plan.grain) * MAX_GRAIN_SIGMA * Argb.CHANNEL_MAX
        val frame = context.frameOf(input)
        for (y in 0 until input.height) {
            for (x in 0 until input.width) {
                val index = y * input.width + x
                val color = input.pixels[index]
                val luma = Luma.ofPixel(color)
                val gaussian = gaussianAt(x + frame.offsetX, y + frame.offsetY)
                val delta = (gaussian * sigma * MIDTONE_WEIGHT_SCALE * luma * (1f - luma)).roundToInt()
                if (delta == 0) continue
                input.pixels[index] = Argb.pack(Argb.alpha(color), Argb.red(color) + delta, Argb.green(color) + delta, Argb.blue(color) + delta)
            }
        }
        return input
    }

    /** Sum of four 16-bit uniforms from one 64-bit position hash ≈ unit Gaussian. */
    private fun gaussianAt(x: Int, y: Int): Float {
        var hash = x.toLong() * HASH_X + y.toLong() * HASH_Y
        hash = (hash xor (hash ushr MIX_SHIFT_A)) * MIX_MULTIPLIER_A
        hash = (hash xor (hash ushr MIX_SHIFT_B)) * MIX_MULTIPLIER_B
        hash = hash xor (hash ushr MIX_SHIFT_C)
        var sum = 0f
        for (part in 0 until UNIFORMS_PER_SAMPLE) sum += ((hash ushr (part * BITS_PER_UNIFORM)) and UNIFORM_MASK).toFloat() / UNIFORM_RANGE
        return (sum - UNIFORMS_PER_SAMPLE / 2f) * UNIT_VARIANCE_SCALE
    }

    private companion object {
        /** Full-strength grain: luma sigma of 6 % in midtones. */
        const val MAX_GRAIN_SIGMA = 0.06f
        const val MIDTONE_WEIGHT_SCALE = 4f
        const val UNIFORMS_PER_SAMPLE = 4
        const val BITS_PER_UNIFORM = 16
        const val UNIFORM_MASK = 0xFFFFL
        const val UNIFORM_RANGE = 65536f

        /** Variance of a sum of 4 uniforms is 4/12; scale back to unit variance. */
        const val UNIT_VARIANCE_SCALE = 1.7320508f

        // SplitMix64 finaliser constants and two large odd multipliers for combining coordinates.
        const val HASH_X = -7046029254386353131L
        const val HASH_Y = -4658895280553007687L
        const val MIX_MULTIPLIER_A = -4658895280553007687L
        const val MIX_MULTIPLIER_B = -7723592293110705685L
        const val MIX_SHIFT_A = 30
        const val MIX_SHIFT_B = 27
        const val MIX_SHIFT_C = 31
    }
}
