package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.BoxBlur
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.processing.ImageFrame
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import com.pixels.enhancer.domain.processing.ops.GuidedFilter
import com.pixels.enhancer.domain.processing.ops.YccPlanes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.pow

/**
 * Luma: edge-preserving guided filter, applied partially so fine texture (skin, foliage)
 * survives. Chroma: plain blur — colour noise is the ugliest kind and the eye tolerates soft chroma.
 */
class NoiseReductionStage : ProcessingStage {
    override val id = StageIds.NOISE_REDUCTION
    override val displayName = "Denoise"

    override fun isEnabled(context: ProcessingContext) = context.plan.noiseReduction.enabled

    /** Guided filter = box then box again (2 × radius); chroma = two box passes. */
    override fun margin(context: ProcessingContext, frame: ImageFrame) = 2 * maxOf(LUMA_RADIUS, CHROMA_RADIUS)

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val amount = context.effectiveAmount(id, context.plan.noiseReduction).coerceIn(0f, 1f)
        val size = input.pixelCount
        val luma = FloatArray(size)
        val blueDifference = FloatArray(size)
        val redDifference = FloatArray(size)
        YccPlanes.split(input, luma, blueDifference, redDifference)

        val scratch = GuidedFilter.Scratch(size)
        val sigma = expectedNoiseSigma(context)
        val epsilon = (EPSILON_SIGMA_MULTIPLIER * sigma).pow(2).coerceAtLeast(MIN_EPSILON)
        GuidedFilter.smoothInPlace(luma, input.width, input.height, LUMA_RADIUS, epsilon, amount * LUMA_MIX, scratch)
        currentCoroutineContext().ensureActive()

        smoothChroma(blueDifference, input, amount, scratch)
        smoothChroma(redDifference, input, amount, scratch)
        currentCoroutineContext().ensureActive()

        YccPlanes.merge(luma, blueDifference, redDifference, input)
        return input
    }

    private fun smoothChroma(plane: FloatArray, image: PixelBuffer, amount: Float, scratch: GuidedFilter.Scratch) {
        val blurred = scratch.mean
        // Two box passes approximate a Gaussian and avoid the blocky look of a single box.
        BoxBlur.blur(plane, blurred, image.width, image.height, CHROMA_RADIUS, scratch.temp)
        BoxBlur.blur(blurred, blurred, image.width, image.height, CHROMA_RADIUS, scratch.temp)
        for (index in plane.indices) plane[index] += amount * (blurred[index] - plane[index])
    }

    companion object {
        const val LUMA_RADIUS = 2
        const val CHROMA_RADIUS = 2

        /** Never fully replace luma with the filtered version: some grain reads as natural texture. */
        const val LUMA_MIX = 0.75f

        /** Variance below (k·σ)² is treated as noise. */
        const val EPSILON_SIGMA_MULTIPLIER = 2f
        const val MIN_EPSILON = 1e-6f

        /** Noise was measured before exposure; an exposure lift amplifies it by the gain. */
        fun expectedNoiseSigma(context: ProcessingContext): Float {
            val exposureEv = context.plan.exposure.takeIf { it.enabled }?.amount ?: 0f
            return context.analysis.noiseSigma * 2f.pow(max(0f, exposureEv))
        }
    }
}
