package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.Srgb
import com.pixels.enhancer.domain.image.smoothstep
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import kotlin.math.max

/**
 * Creative vignette (manual only). Negative darkens the corners, positive lightens them. Applied
 * as a gain in linear light, like real lens fall-off, so colours keep their hue.
 */
class VignetteStage : ProcessingStage {
    override val id = StageIds.VIGNETTE
    override val displayName = "Vignette"

    override fun isEnabled(context: ProcessingContext) = context.plan.vignette.enabled

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val amount = context.effectiveAmount(id, context.plan.vignette) * MAX_GAIN_CHANGE
        val centerX = (input.width - 1) / 2f
        val centerY = (input.height - 1) / 2f
        for (y in 0 until input.height) {
            val dy = (y - centerY) / max(centerY, 1f)
            for (x in 0 until input.width) {
                val dx = (x - centerX) / max(centerX, 1f)
                // Elliptical distance: 1 at the edge midpoints, ~1.41 in the corners.
                val distanceSquared = dx * dx + dy * dy
                val falloff = smoothstep(INNER_RADIUS_SQUARED, OUTER_RADIUS_SQUARED, distanceSquared)
                if (falloff == 0f) continue
                val index = y * input.width + x
                input.pixels[index] = applyGain(input.pixels[index], 1f + amount * falloff)
            }
        }
        return input
    }

    private fun applyGain(color: Int, gain: Float): Int {
        var red = Srgb.toLinear(Argb.red(color)) * gain
        var green = Srgb.toLinear(Argb.green(color)) * gain
        var blue = Srgb.toLinear(Argb.blue(color)) * gain
        val brightest = max(red, max(green, blue))
        if (brightest > 1f) {
            red /= brightest
            green /= brightest
            blue /= brightest
        }
        return Argb.pack(Argb.alpha(color), Srgb.toSrgb8(red), Srgb.toSrgb8(green), Srgb.toSrgb8(blue))
    }

    private companion object {
        /** Full-strength vignette changes corner brightness by 70 % in linear light. */
        const val MAX_GAIN_CHANGE = 0.7f
        const val INNER_RADIUS_SQUARED = 0.25f
        const val OUTER_RADIUS_SQUARED = 2f
    }
}
