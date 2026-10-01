package com.pixels.enhancer.domain.processing.ops

import com.pixels.enhancer.domain.image.Srgb
import kotlin.math.pow

/**
 * Exposure gain in linear light with an anchored white point: `out = x·g / (1 + x·(g − 1))`.
 * Shadows and midtones get close to the full gain while values near white roll off smoothly
 * into 1, so a lift never creates new hard-clipped highlights.
 *
 * Shared by the exposure stage and the planner (which predicts post-exposure contrast).
 */
object ExposureCurve {

    fun applyLinear(linear: Float, ev: Float): Float {
        val gain = 2f.pow(ev)
        return linear * gain / (1f + linear * (gain - 1f))
    }

    /** Same curve for a gamma-encoded 0..1 value. */
    fun applyEncoded(encoded: Float, ev: Float): Float = Srgb.encode(applyLinear(Srgb.decode(encoded), ev))
}
