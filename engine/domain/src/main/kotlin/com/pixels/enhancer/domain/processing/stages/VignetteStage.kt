package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.Srgb
import com.pixels.enhancer.domain.image.smoothstep
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

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
        val plan = context.plan
        val midpoint = plan.vignetteMidpoint.takeIf { it.enabled }?.amount ?: 0f
        val feather = plan.vignetteFeather.takeIf { it.enabled }?.amount ?: 0f
        val roundness = plan.vignetteRoundness.takeIf { it.enabled }?.amount ?: 0f
        // Midpoint moves the start of the fall-off inward (+) or outward (−); feather widens or narrows it.
        val inner = (INNER_RADIUS_SQUARED * (1f - MIDPOINT_RANGE * midpoint)).coerceAtLeast(MIN_INNER)
        val outer = inner + ((OUTER_RADIUS_SQUARED - INNER_RADIUS_SQUARED) * (1f + FEATHER_RANGE * feather)).coerceAtLeast(MIN_SPREAD)
        val frame = context.frameOf(input)
        val centerX = (frame.fullWidth - 1) / 2f
        val centerY = (frame.fullHeight - 1) / 2f
        // Roundness > 0 blends the frame-shaped oval toward a circle; < 0 squares it off.
        val circle = sqrt(max(centerX, 1f) * max(centerY, 1f))
        val round = max(0f, roundness)
        val radiusX = max(centerX, 1f) + (circle - max(centerX, 1f)) * round
        val radiusY = max(centerY, 1f) + (circle - max(centerY, 1f)) * round
        val exponent = 2f + SQUARENESS * max(0f, -roundness)
        for (y in 0 until input.height) {
            val dy = (y + frame.offsetY - centerY) / radiusY
            for (x in 0 until input.width) {
                val dx = (x + frame.offsetX - centerX) / radiusX
                // Elliptical distance: 1 at the edge midpoints, ~1.41 in the corners.
                val distanceSquared = if (exponent == 2f) dx * dx + dy * dy else (abs(dx).pow(exponent) + abs(dy).pow(exponent)).pow(2f / exponent)
                val falloff = smoothstep(inner, outer, distanceSquared)
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
        const val MIDPOINT_RANGE = 0.8f
        const val FEATHER_RANGE = 0.6f
        const val MIN_INNER = 0.02f
        const val MIN_SPREAD = 0.2f
        const val SQUARENESS = 4f
    }
}
