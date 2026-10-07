package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.Srgb
import com.pixels.enhancer.domain.image.smoothstep
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingStage
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Portrait face-region exposure: a soft, round exposure change centred on each detected face, so a
 * backlit or shadowed face becomes visible without brightening the whole photo. It only changes
 * brightness — never shape, skin texture or features.
 */
class FaceExposureStage : ProcessingStage {
    override val id = StageIds.FACE_EXPOSURE
    override val displayName = "Face Exposure"

    override fun isEnabled(context: ProcessingContext) = context.plan.faceExposure.enabled && context.analysis.faces.isNotEmpty()

    override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
        val gain = 2f.pow(context.effectiveAmount(id, context.plan.faceExposure))
        val frame = context.frameOf(input)
        val faces = context.analysis.faces
        for (y in 0 until input.height) {
            val fy = (y + frame.offsetY + 0.5f) / frame.fullHeight
            for (x in 0 until input.width) {
                val fx = (x + frame.offsetX + 0.5f) / frame.fullWidth
                var weight = 0f
                faces.forEach { face ->
                    val dx = (fx - face.centerX)
                    val dy = (fy - face.centerY) * frame.fullHeight / frame.fullWidth
                    val distance = sqrt(dx * dx + dy * dy) / (face.radius * REGION_SCALE)
                    weight = max(weight, 1f - smoothstep(INNER_EDGE, 1f, distance))
                }
                if (weight <= 0f) continue
                val index = y * input.width + x
                input.pixels[index] = applyGain(input.pixels[index], 1f + (gain - 1f) * weight)
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
        /** The lit region extends past the detected face to include hair and neck naturally. */
        const val REGION_SCALE = 1.8f

        /** Full effect inside 35 % of the region, fading out to its edge: no visible halo. */
        const val INNER_EDGE = 0.35f
    }
}
