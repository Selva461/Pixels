package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.planning.CurveChannel
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage

/** User tone curves (manual only), on gamma-encoded values like every common editor. */
class CurvesStage : ProcessingStage {
    override val id = StageIds.CURVES
    override val displayName = "Curves"

    override fun isEnabled(context: ProcessingContext) = !context.plan.toneCurves.isIdentity

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val curves = context.plan.toneCurves
        val master = curves[CurveChannel.MASTER].lut()
        // Compose master then channel into one table per channel.
        val red = curves[CurveChannel.RED].lut().let { channel -> IntArray(LUT_SIZE) { channel[master[it]] } }
        val green = curves[CurveChannel.GREEN].lut().let { channel -> IntArray(LUT_SIZE) { channel[master[it]] } }
        val blue = curves[CurveChannel.BLUE].lut().let { channel -> IntArray(LUT_SIZE) { channel[master[it]] } }
        val pixels = input.pixels
        for (index in pixels.indices) {
            val color = pixels[index]
            pixels[index] = Argb.pack(Argb.alpha(color), red[Argb.red(color)], green[Argb.green(color)], blue[Argb.blue(color)])
        }
        return input
    }

    private companion object {
        const val LUT_SIZE = 256
    }
}
