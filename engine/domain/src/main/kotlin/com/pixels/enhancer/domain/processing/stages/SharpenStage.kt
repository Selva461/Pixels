package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.BoxBlur
import com.pixels.enhancer.domain.image.LumaPlane
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.smoothstep
import com.pixels.enhancer.domain.processing.ImageFrame
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import com.pixels.enhancer.domain.processing.ops.SkinToneDetector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Small-radius unsharp mask on luma with three naturalness guards:
 * a noise-aware threshold (flat areas and grain are left alone), reduced strength on skin, and
 * overshoot clamping to the local min/max (no bright/dark halos along edges).
 */
class SharpenStage : ProcessingStage {
    override val id = StageIds.SHARPEN
    override val displayName = "Sharpen"

    override fun isEnabled(context: ProcessingContext) = context.plan.sharpening.enabled

    /** Box blur radius plus the 3×3 overshoot clamp. */
    override fun margin(context: ProcessingContext, frame: ImageFrame) = RADIUS + 1

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val amount = context.effectiveAmount(id, context.plan.sharpening) * SHARPEN_GAIN
        // Masking raises the threshold so only clear edges are sharpened (flat areas and fine texture untouched).
        val masking = context.effectiveAmount(id, context.plan.sharpenMasking).coerceIn(0f, 1f)
        val threshold = max(MIN_THRESHOLD, THRESHOLD_SIGMA_MULTIPLIER * residualNoiseSigma(context)) + masking * MAX_MASKING_THRESHOLD
        val width = input.width
        val height = input.height
        val luma = LumaPlane.extract(input)
        val blurred = FloatArray(input.pixelCount)
        val edited = FloatArray(input.pixelCount)
        BoxBlur.blur(luma, blurred, width, height, RADIUS, edited)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val value = luma[index]
                val highFrequency = value - blurred[index]
                val edgeWeight = smoothstep(threshold, threshold * 2f, abs(highFrequency))
                val skinWeight = if (SkinToneDetector.isLikelySkin(input.pixels[index])) SKIN_PROTECTION else 1f
                val sharpened = value + amount * edgeWeight * skinWeight * highFrequency
                edited[index] = clampToNeighbourhood(luma, width, height, x, y, sharpened)
            }
        }
        LumaPlane.applyDelta(input, luma, edited)
        return input
    }

    private fun clampToNeighbourhood(luma: FloatArray, width: Int, height: Int, x: Int, y: Int, value: Float): Float {
        var lowest = Float.MAX_VALUE
        var highest = -Float.MAX_VALUE
        for (ny in max(0, y - 1)..min(height - 1, y + 1)) {
            for (nx in max(0, x - 1)..min(width - 1, x + 1)) {
                val neighbour = luma[ny * width + nx]
                lowest = min(lowest, neighbour)
                highest = max(highest, neighbour)
            }
        }
        return value.coerceIn(lowest - OVERSHOOT, highest + OVERSHOOT)
    }

    private fun residualNoiseSigma(context: ProcessingContext): Float {
        val denoise = context.plan.noiseReduction.takeIf { it.enabled }?.amount ?: 0f
        return NoiseReductionStage.expectedNoiseSigma(context) * (1f - denoise * DENOISE_RESIDUAL_FACTOR)
    }

    companion object {
        const val RADIUS = 1
        const val SHARPEN_GAIN = 2.5f
        const val SKIN_PROTECTION = 0.5f

        /** Maximum overshoot beyond the local 3×3 range, in luma units. */
        const val OVERSHOOT = 0.02f
        const val MIN_THRESHOLD = 0.004f

        /** Full masking ignores luma differences below ≈13/255 — only strong edges remain. */
        const val MAX_MASKING_THRESHOLD = 0.05f
        const val THRESHOLD_SIGMA_MULTIPLIER = 2f

        /** Rough share of noise the denoiser removes at amount 1; used only to set the threshold. */
        private const val DENOISE_RESIDUAL_FACTOR = 0.5f
    }
}
