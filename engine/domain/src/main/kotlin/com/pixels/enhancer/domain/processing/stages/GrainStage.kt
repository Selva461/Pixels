package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import kotlin.math.roundToInt

/**
 * Creative film grain (manual only): monochrome noise, strongest in midtones like real film.
 * The noise pattern is seeded from the image size, so the same edit always renders the same grain.
 */
class GrainStage : ProcessingStage {
    override val id = StageIds.GRAIN
    override val displayName = "Grain"

    override fun isEnabled(context: ProcessingContext) = context.plan.grain.enabled

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val sigma = context.effectiveAmount(id, context.plan.grain) * MAX_GRAIN_SIGMA * Argb.CHANNEL_MAX
        var state = (input.width.toLong() * SEED_MULTIPLIER + input.height) or 1L
        val pixels = input.pixels
        for (index in pixels.indices) {
            val color = pixels[index]
            val luma = Luma.ofPixel(color)
            // Sum of four uniforms ≈ Gaussian; cheap and deterministic.
            var sum = 0f
            repeat(UNIFORMS_PER_SAMPLE) {
                state = state xor (state shl XORSHIFT_A)
                state = state xor (state ushr XORSHIFT_B)
                state = state xor (state shl XORSHIFT_C)
                sum += (state ushr UNIFORM_SHIFT).toFloat() / UNIFORM_RANGE
            }
            val gaussian = (sum - UNIFORMS_PER_SAMPLE / 2f) * UNIT_VARIANCE_SCALE
            val delta = (gaussian * sigma * MIDTONE_WEIGHT_SCALE * luma * (1f - luma)).roundToInt()
            if (delta == 0) continue
            pixels[index] = Argb.pack(Argb.alpha(color), Argb.red(color) + delta, Argb.green(color) + delta, Argb.blue(color) + delta)
        }
        return input
    }

    private companion object {
        /** Full-strength grain: luma sigma of 6 % in midtones. */
        const val MAX_GRAIN_SIGMA = 0.06f
        const val MIDTONE_WEIGHT_SCALE = 4f
        const val UNIFORMS_PER_SAMPLE = 4

        /** Variance of a sum of 4 uniforms is 4/12; scale back to unit variance. */
        const val UNIT_VARIANCE_SCALE = 1.7320508f
        /** Golden-ratio constant (0x9E3779B97F4A7C15) — spreads nearby sizes to unrelated seeds. */
        const val SEED_MULTIPLIER = -7046029254386353131L
        const val XORSHIFT_A = 13
        const val XORSHIFT_B = 7
        const val XORSHIFT_C = 17
        const val UNIFORM_SHIFT = 40
        const val UNIFORM_RANGE = 16777216f
    }
}
