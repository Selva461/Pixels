package com.pixels.enhancer.domain.processing.ops

import com.pixels.enhancer.domain.image.Argb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Rule-based RGB skin classifier (Kovač et al., daylight rule). Deliberately simple: it only
 * softens sharpening and saturation on likely skin, it never drives a visible edit by itself.
 */
object SkinToneDetector {
    private const val MIN_RED = 95
    private const val MIN_GREEN = 40
    private const val MIN_BLUE = 20
    private const val MIN_SPREAD = 15
    private const val MIN_RED_GREEN_GAP = 15

    fun isLikelySkin(color: Int): Boolean {
        val red = Argb.red(color)
        val green = Argb.green(color)
        val blue = Argb.blue(color)
        return red > MIN_RED && green > MIN_GREEN && blue > MIN_BLUE &&
            max(red, max(green, blue)) - min(red, min(green, blue)) > MIN_SPREAD &&
            abs(red - green) > MIN_RED_GREEN_GAP && red > green && red > blue
    }
}
