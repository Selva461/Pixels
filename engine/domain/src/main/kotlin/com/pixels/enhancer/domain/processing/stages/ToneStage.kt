package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import com.pixels.enhancer.domain.processing.ops.ChromaOps
import com.pixels.enhancer.domain.processing.ops.ToneCurve

/** Highlight/shadow recovery and global contrast, applied as one luma curve. */
class ToneStage : ProcessingStage {
    override val id = StageIds.TONE
    override val displayName = "Tone"

    override fun isEnabled(context: ProcessingContext): Boolean {
        val plan = context.plan
        return plan.contrast.enabled || plan.highlights.enabled || plan.shadows.enabled
    }

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val plan = context.plan
        val curve = ToneCurve.build(
            contrast = context.effectiveAmount(id, plan.contrast),
            highlights = context.effectiveAmount(id, plan.highlights),
            shadows = context.effectiveAmount(id, plan.shadows),
        )
        val pixels = input.pixels
        for (index in pixels.indices) {
            val color = pixels[index]
            val luma = Luma.ofPixel(color)
            pixels[index] = ChromaOps.withLuma(color, luma, curve.map(luma))
        }
        return input
    }
}
