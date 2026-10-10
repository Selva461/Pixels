package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.BoxBlur
import com.pixels.enhancer.domain.image.LumaPlane
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.processing.ImageFrame
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Texture (manual only): contrast of fine, mid-frequency detail — a small-radius cousin of
 * Clarity. Negative values soften texture without blurring edges much. Change per pixel is capped
 * so it can never produce halos or invent detail.
 */
class TextureStage : ProcessingStage {
    override val id = StageIds.TEXTURE
    override val displayName = "Texture"

    override fun isEnabled(context: ProcessingContext) = context.plan.texture.enabled

    override fun margin(context: ProcessingContext, frame: ImageFrame) = 2 * radiusFor(frame.fullWidth, frame.fullHeight)

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val amount = context.effectiveAmount(id, context.plan.texture) * TEXTURE_GAIN
        val frame = context.frameOf(input)
        val radius = radiusFor(frame.fullWidth, frame.fullHeight)
        val luma = LumaPlane.extract(input)
        val base = FloatArray(input.pixelCount)
        val scratch = FloatArray(input.pixelCount)
        BoxBlur.blur(luma, base, input.width, input.height, radius, scratch)
        BoxBlur.blur(base, base, input.width, input.height, radius, scratch)
        val edited = scratch
        for (index in luma.indices) {
            val change = (amount * (luma[index] - base[index])).coerceIn(-MAX_LUMA_CHANGE, MAX_LUMA_CHANGE)
            edited[index] = luma[index] + change
        }
        LumaPlane.applyDelta(input, luma, edited)
        return input
    }

    companion object {
        const val TEXTURE_GAIN = 1.5f
        const val MAX_LUMA_CHANGE = 0.04f
        private const val RADIUS_FRACTION_OF_SHORT_EDGE = 0.0015f
        private const val MIN_RADIUS = 2

        fun radiusFor(width: Int, height: Int): Int =
            max(MIN_RADIUS, (min(width, height) * RADIUS_FRACTION_OF_SHORT_EDGE).roundToInt())
    }
}
