package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.BoxBlur
import com.pixels.enhancer.domain.image.LumaPlane
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Mild local contrast ("clarity") via a large-radius unsharp mask on luma. */
class DetailStage : ProcessingStage {
    override val id = StageIds.DETAIL
    override val displayName = "Detail"

    override fun isEnabled(context: ProcessingContext) = context.plan.detail.enabled

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val amount = context.effectiveAmount(id, context.plan.detail) * DETAIL_GAIN
        val luma = LumaPlane.extract(input)
        val base = FloatArray(input.pixelCount)
        val scratch = FloatArray(input.pixelCount)
        val radius = radiusFor(input)
        BoxBlur.blur(luma, base, input.width, input.height, radius, scratch)
        BoxBlur.blur(base, base, input.width, input.height, radius, scratch)

        val edited = scratch
        for (index in luma.indices) {
            val value = luma[index]
            // Strongest in midtones; fading towards black and white keeps it from looking like HDR.
            val midtoneWeight = MIDTONE_WEIGHT_SCALE * value * (1f - value)
            val boost = (amount * midtoneWeight * (value - base[index])).coerceIn(-MAX_LUMA_CHANGE, MAX_LUMA_CHANGE)
            edited[index] = value + boost
        }
        LumaPlane.applyDelta(input, luma, edited)
        return input
    }

    companion object {
        const val DETAIL_GAIN = 2f

        /** Hard cap on the per-pixel change — the main guard against halos. */
        const val MAX_LUMA_CHANGE = 0.05f
        private const val MIDTONE_WEIGHT_SCALE = 4f
        private const val RADIUS_FRACTION_OF_SHORT_EDGE = 0.008f
        private const val MIN_RADIUS = 4

        fun radiusFor(image: PixelBuffer): Int =
            max(MIN_RADIUS, (min(image.width, image.height) * RADIUS_FRACTION_OF_SHORT_EDGE).roundToInt())
    }
}
