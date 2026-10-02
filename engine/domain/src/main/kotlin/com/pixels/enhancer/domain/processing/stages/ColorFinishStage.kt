package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.analysis.SaturationEstimator
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import com.pixels.enhancer.domain.processing.ops.ChromaOps
import com.pixels.enhancer.domain.processing.ops.SkinToneDetector

/**
 * Vibrance-style saturation: boosts favour muted colours and spare skin, so foliage does not go
 * neon and faces do not turn orange. Reductions are uniform. The manual global saturation is a
 * plain uniform scale on top (−1 = monochrome).
 */
class ColorFinishStage : ProcessingStage {
    override val id = StageIds.COLOR_FINISH
    override val displayName = "Color Finish"

    override fun isEnabled(context: ProcessingContext) = context.plan.saturation.enabled || context.plan.globalSaturation.enabled

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val amount = context.effectiveAmount(id, context.plan.saturation)
        val globalFactor = (1f + context.effectiveAmount(id, context.plan.globalSaturation)).coerceAtLeast(0f)
        val pixels = input.pixels
        for (index in pixels.indices) {
            val color = pixels[index]
            pixels[index] = ChromaOps.scaleChroma(color, chromaFactor(color, amount) * globalFactor)
        }
        return input
    }

    private fun chromaFactor(color: Int, amount: Float): Float {
        if (amount <= 0f) return 1f + amount
        val headroom = 1f - SaturationEstimator.chromaOf(color)
        val skinWeight = if (SkinToneDetector.isLikelySkin(color)) SKIN_PROTECTION else 1f
        return 1f + amount * headroom * skinWeight
    }

    private companion object {
        const val SKIN_PROTECTION = 0.5f
    }
}
