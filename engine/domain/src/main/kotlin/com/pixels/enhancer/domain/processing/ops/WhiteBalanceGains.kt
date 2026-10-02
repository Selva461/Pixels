package com.pixels.enhancer.domain.processing.ops

import com.pixels.enhancer.domain.analysis.ChannelBalance
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.planning.NaturalLimits
import kotlin.math.pow

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

    /** Full-scale temperature moves red and blue by ±0.3 stop in opposite directions. */
    private const val TEMPERATURE_STOPS = 0.3f

    /** Full-scale tint moves green by ±0.2 stop. */
    private const val TINT_STOPS = 0.2f

    private fun partialGain(fullGain: Float, fraction: Float, limits: NaturalLimits): Float =
        fullGain.pow(fraction).coerceIn(limits.minChannelGain, limits.maxChannelGain)
}
