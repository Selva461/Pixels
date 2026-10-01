package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.Srgb
import com.pixels.enhancer.domain.image.smoothstep
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import com.pixels.enhancer.domain.processing.ops.WhiteBalanceGains
import kotlin.math.max

class WhiteBalanceStage : ProcessingStage {
    override val id = StageIds.WHITE_BALANCE
    override val displayName = "White Balance"

    override fun isEnabled(context: ProcessingContext) = context.plan.whiteBalance.enabled

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val fraction = context.effectiveAmount(id, context.plan.whiteBalance)
        val gains = WhiteBalanceGains.compute(context.analysis.neutralBalance, fraction, context.qualityPreset.limits)
        val pixels = input.pixels
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = Srgb.toLinear(Argb.red(color))
            val green = Srgb.toLinear(Argb.green(color))
            val blue = Srgb.toLinear(Argb.blue(color))
            // Clipped pixels have lost their true colour; re-tinting them would turn blown
            // highlights coloured, so they keep their original values.
            val protection = smoothstep(CLIPPED_PROTECTION_START, 1f, max(red, max(green, blue)))
            var newRed = red * blend(gains.red, protection)
            var newGreen = green * blend(gains.green, protection)
            var newBlue = blue * blend(gains.blue, protection)
            // Bright neutrals pushed past white are scaled down as a whole so they stay neutral.
            val brightest = max(newRed, max(newGreen, newBlue))
            if (brightest > 1f) {
                newRed /= brightest
                newGreen /= brightest
                newBlue /= brightest
            }
            pixels[index] = Argb.pack(Argb.alpha(color), Srgb.toSrgb8(newRed), Srgb.toSrgb8(newGreen), Srgb.toSrgb8(newBlue))
        }
        return input
    }

    private fun blend(gain: Float, protection: Float) = gain + (1f - gain) * protection

    private companion object {
        const val CLIPPED_PROTECTION_START = 0.95f
    }
}
