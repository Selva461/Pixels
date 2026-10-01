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

    private fun partialGain(fullGain: Float, fraction: Float, limits: NaturalLimits): Float =
        fullGain.pow(fraction).coerceIn(limits.minChannelGain, limits.maxChannelGain)
}
