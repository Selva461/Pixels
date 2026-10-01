package com.pixels.enhancer.domain.image

import kotlin.math.pow
import kotlin.math.roundToInt

/** Packing helpers for ARGB_8888 ints. */
object Argb {
    const val CHANNEL_MAX = 255
    const val OPAQUE_ALPHA = 0xFF

    fun alpha(color: Int): Int = color ushr 24
    fun red(color: Int): Int = (color shr 16) and 0xFF
    fun green(color: Int): Int = (color shr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF

    fun pack(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (clampChannel(red) shl 16) or (clampChannel(green) shl 8) or clampChannel(blue)

    fun opaque(red: Int, green: Int, blue: Int): Int = pack(OPAQUE_ALPHA, red, green, blue)

    fun clampChannel(value: Int): Int = value.coerceIn(0, CHANNEL_MAX)

    /** Converts a 0..1 float to an 8-bit channel value with rounding and clamping. */
    fun toChannel(unit: Float): Int = clampChannel((unit * CHANNEL_MAX).roundToInt())
}

/** Rec.709 luma weights applied to gamma-encoded values — cheap and good enough for statistics. */
object Luma {
    const val RED_WEIGHT = 0.2126f
    const val GREEN_WEIGHT = 0.7152f
    const val BLUE_WEIGHT = 0.0722f

    /** Luma in 0..1 from 8-bit channels. */
    fun of8Bit(red: Int, green: Int, blue: Int): Float =
        (RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * blue) / Argb.CHANNEL_MAX

    fun ofPixel(color: Int): Float = of8Bit(Argb.red(color), Argb.green(color), Argb.blue(color))

    fun of(red: Float, green: Float, blue: Float): Float =
        RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * blue
}

/** sRGB transfer function with lookup tables; exposure and white balance must happen in linear light. */
object Srgb {
    private const val LINEAR_TO_SRGB_LUT_SIZE = 16384
    private const val LINEAR_THRESHOLD = 0.0031308f
    private const val ENCODED_THRESHOLD = 0.04045f
    private const val LINEAR_SLOPE = 12.92f
    private const val GAMMA = 2.4
    private const val OFFSET = 0.055
    private const val SCALE = 1.055

    /** Linear value (0..1) for each 8-bit sRGB code. */
    private val toLinearTable = FloatArray(Argb.CHANNEL_MAX + 1) { decode(it / Argb.CHANNEL_MAX.toFloat()) }

    private val toSrgb8Table = IntArray(LINEAR_TO_SRGB_LUT_SIZE + 1) {
        Argb.toChannel(encode(it / LINEAR_TO_SRGB_LUT_SIZE.toFloat()))
    }

    fun toLinear(channel8Bit: Int): Float = toLinearTable[channel8Bit]

    /** Linear 0..1 (clamped) to an 8-bit sRGB code. */
    fun toSrgb8(linear: Float): Int {
        val index = (linear.coerceIn(0f, 1f) * LINEAR_TO_SRGB_LUT_SIZE + 0.5f).toInt()
        return toSrgb8Table[index]
    }

    fun decode(encoded: Float): Float = if (encoded <= ENCODED_THRESHOLD) {
        encoded / LINEAR_SLOPE
    } else {
        ((encoded + OFFSET) / SCALE).pow(GAMMA).toFloat()
    }

    fun encode(linear: Float): Float = if (linear <= LINEAR_THRESHOLD) {
        linear * LINEAR_SLOPE
    } else {
        (SCALE * linear.toDouble().pow(1.0 / GAMMA) - OFFSET).toFloat()
    }
}

/** Hermite smoothstep; used wherever an effect must fade in without a visible edge. */
fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 <= edge0) return if (x < edge0) 0f else 1f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
