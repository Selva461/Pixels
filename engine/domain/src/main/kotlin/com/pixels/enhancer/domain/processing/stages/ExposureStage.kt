package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.Srgb
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import com.pixels.enhancer.domain.processing.ops.ExposureCurve

class ExposureStage : ProcessingStage {
    override val id = StageIds.EXPOSURE
    override val displayName = "Exposure"

    override fun isEnabled(context: ProcessingContext) = context.plan.exposure.enabled

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val lut = buildLut(context.effectiveAmount(id, context.plan.exposure))
        val pixels = input.pixels
        for (index in pixels.indices) {
            val color = pixels[index]
            pixels[index] = Argb.pack(Argb.alpha(color), lut[Argb.red(color)], lut[Argb.green(color)], lut[Argb.blue(color)])
        }
        return input
    }

    companion object {
        fun buildLut(ev: Float): IntArray = IntArray(Argb.CHANNEL_MAX + 1) { code ->
            Srgb.toSrgb8(ExposureCurve.applyLinear(Srgb.toLinear(code), ev))
        }
    }
}
