package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.Srgb
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import com.pixels.enhancer.domain.processing.ops.ChromaOps
import kotlin.math.max

/**
 * Global dehaze (manual only). Haze adds a grey veil that lifts the darkest tones; the image's own
 * 1st-percentile luma estimates that veil. Positive amounts subtract it in linear light and
 * rescale (restoring blacks and colour); negative amounts add a soft veil. Nothing is invented —
 * detail lost to dense fog cannot come back.
 */
class DehazeStage : ProcessingStage {
    override val id = StageIds.DEHAZE
    override val displayName = "Dehaze"

    override fun isEnabled(context: ProcessingContext) = context.plan.dehaze.enabled

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val amount = context.effectiveAmount(id, context.plan.dehaze)
        val veil = Srgb.decode(context.analysis.luminance.p1) * VEIL_CONFIDENCE
        val lut = if (amount >= 0f) removeVeilLut(amount * veil) else addVeilLut(-amount * MAX_ADDED_VEIL)
        // Removing haze also recovers colour the veil had washed out (and adding it washes colour out).
        val chromaFactor = 1f + amount * CHROMA_COMPENSATION
        val pixels = input.pixels
        for (index in pixels.indices) {
            val color = pixels[index]
            val dehazed = Argb.pack(Argb.alpha(color), lut[Argb.red(color)], lut[Argb.green(color)], lut[Argb.blue(color)])
            pixels[index] = if (chromaFactor == 1f) dehazed else ChromaOps.scaleChroma(dehazed, chromaFactor)
        }
        return input
    }

    private fun removeVeilLut(veil: Float) = IntArray(Argb.CHANNEL_MAX + 1) { code ->
        val linear = Srgb.toLinear(code)
        Srgb.toSrgb8(max(0f, linear - veil) / (1f - veil))
    }

    private fun addVeilLut(veil: Float) = IntArray(Argb.CHANNEL_MAX + 1) { code ->
        val linear = Srgb.toLinear(code)
        Srgb.toSrgb8(linear * (1f - veil) + VEIL_GREY * veil)
    }

    private companion object {
        /** The 1st percentile is part veil, part genuinely dark subject; only remove most of it. */
        const val VEIL_CONFIDENCE = 0.9f

        /** Full negative dehaze blends 35 % of a light grey veil into the image. */
        const val MAX_ADDED_VEIL = 0.35f
        const val VEIL_GREY = 0.45f
        const val CHROMA_COMPENSATION = 0.25f
    }
}

