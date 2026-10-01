package com.pixels.enhancer.domain.processing.ops

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import kotlin.math.max
import kotlin.math.min

/** Luma/chroma pixel edits that keep hue stable and stay inside the RGB gamut. */
object ChromaOps {

    private const val CHANNEL_SCALE = 1f / Argb.CHANNEL_MAX

    /**
     * Moves a pixel to [newLuma] by adding the same offset to every channel. An additive shift
     * (rather than scaling RGB by newLuma/oldLuma) avoids multiplying chroma noise in lifted shadows.
     */
    fun withLuma(color: Int, oldLuma: Float, newLuma: Float): Int {
        val delta = newLuma - oldLuma
        return fitToGamut(
            Argb.alpha(color),
            Argb.red(color) * CHANNEL_SCALE + delta,
            Argb.green(color) * CHANNEL_SCALE + delta,
            Argb.blue(color) * CHANNEL_SCALE + delta,
            newLuma,
        )
    }

    /** Scales chroma (distance from grey at the same luma) by [factor]. */
    fun scaleChroma(color: Int, factor: Float): Int {
        val red = Argb.red(color) * CHANNEL_SCALE
        val green = Argb.green(color) * CHANNEL_SCALE
        val blue = Argb.blue(color) * CHANNEL_SCALE
        val luma = Luma.of(red, green, blue)
        return fitToGamut(
            Argb.alpha(color),
            luma + (red - luma) * factor,
            luma + (green - luma) * factor,
            luma + (blue - luma) * factor,
            luma,
        )
    }

    /**
     * If a channel left 0..1, pull all channels toward [luma] just enough to fit. Clamping each
     * channel independently would shift hue (e.g. skin turning yellow as red clips).
     */
    fun fitToGamut(alpha: Int, red: Float, green: Float, blue: Float, luma: Float): Int {
        val target = luma.coerceIn(0f, 1f)
        val highest = max(red, max(green, blue))
        val lowest = min(red, min(green, blue))
        var scale = 1f
        if (highest > 1f && highest - target > 0f) scale = min(scale, (1f - target) / (highest - target))
        if (lowest < 0f && target - lowest > 0f) scale = min(scale, target / (target - lowest))
        return Argb.pack(
            alpha,
            Argb.toChannel(target + (red - target) * scale),
            Argb.toChannel(target + (green - target) * scale),
            Argb.toChannel(target + (blue - target) * scale),
        )
    }
}
