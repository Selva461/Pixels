package com.pixels.enhancer.domain.processing.ops

import com.pixels.enhancer.domain.analysis.ChannelBalance
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.planning.NaturalLimits
import com.pixels.enhancer.domain.image.Srgb
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

data class ChannelGains(val red: Float, val green: Float, val blue: Float) {
    companion object {
        val IDENTITY = ChannelGains(1f, 1f, 1f)
    }
}

object WhiteBalanceGains {

    /**
     * Linear-light gains that remove [fraction] of the measured cast. Gains are clamped to the
     * preset limits and normalised so neutral brightness does not change.
     */
    fun compute(balance: ChannelBalance, fraction: Float, limits: NaturalLimits): ChannelGains {
        if (fraction <= 0f || balance.red <= 0f || balance.green <= 0f || balance.blue <= 0f) return ChannelGains.IDENTITY
        val grey = (balance.red + balance.green + balance.blue) / 3f
        val red = partialGain(grey / balance.red, fraction, limits)
        val green = partialGain(grey / balance.green, fraction, limits)
        val blue = partialGain(grey / balance.blue, fraction, limits)
        val brightness = Luma.of(red, green, blue)
        return ChannelGains(red / brightness, green / brightness, blue / brightness)
    }

    /**
     * Applies the user's creative temperature (+ warmer) and tint (+ magenta) on top of [gains],
     * keeping neutral brightness unchanged.
     */
    fun withCreativeShift(gains: ChannelGains, temperature: Float, tint: Float): ChannelGains {
        if (temperature == 0f && tint == 0f) return gains
        val red = gains.red * 2f.pow(TEMPERATURE_STOPS * temperature)
        val green = gains.green * 2f.pow(-TINT_STOPS * tint)
        val blue = gains.blue * 2f.pow(-TEMPERATURE_STOPS * temperature)
        val brightness = Luma.of(red, green, blue)
        return ChannelGains(red / brightness, green / brightness, blue / brightness)
    }

    /**
     * White-balance picker: the temperature and tint slider changes (−1..1 scale) that make the
     * sampled colour [red],[green],[blue] (8-bit sRGB, as currently shown) neutral grey. Gains are
     * log-additive, so the result is added to the current slider values. Null when the sample is
     * too dark or clipped to measure.
     */
    fun neutralizingShift(red: Int, green: Int, blue: Int): Pair<Float, Float>? {
        if (maxOf(red, green, blue) >= PICKER_CLIPPED || minOf(red, green, blue) <= PICKER_TOO_DARK) return null
        val r = Srgb.toLinear(red)
        val g = Srgb.toLinear(green)
        val b = Srgb.toLinear(blue)
        val temperature = -log2(r / b) / (2f * TEMPERATURE_STOPS)
        val tint = log2(g / sqrt(r * b)) / TINT_STOPS
        return temperature to tint
    }

    private const val PICKER_CLIPPED = 250
    private const val PICKER_TOO_DARK = 8

    /** Full-scale temperature moves red and blue by ±0.3 stop in opposite directions. */
    private const val TEMPERATURE_STOPS = 0.3f

    /** Full-scale tint moves green by ±0.2 stop. */
    private const val TINT_STOPS = 0.2f

    private fun partialGain(fullGain: Float, fraction: Float, limits: NaturalLimits): Float =
        fullGain.pow(fraction).coerceIn(limits.minChannelGain, limits.maxChannelGain)
}
