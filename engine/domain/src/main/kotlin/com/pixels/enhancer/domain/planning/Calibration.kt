package com.pixels.enhancer.domain.planning

/**
 * Calibration: re-tunes the hue and saturation of the red, green and blue primaries — the
 * pro-editor tool for changing how every colour in the photo renders at once — plus a
 * green–magenta tint for the shadows. Every value is −1..1: hue ±[MAX_HUE_DEGREES], saturation
 * ×(1 ± [MAX_SATURATION_CHANGE]), shadows tint like the Tint slider (+ magenta). Neutral greys are
 * never tinted by the primaries.
 */
data class Calibration(
    val redHue: Float = 0f,
    val redSaturation: Float = 0f,
    val greenHue: Float = 0f,
    val greenSaturation: Float = 0f,
    val blueHue: Float = 0f,
    val blueSaturation: Float = 0f,
    val shadowsTint: Float = 0f,
) {
    val isNeutral: Boolean
        get() = redHue == 0f && redSaturation == 0f && greenHue == 0f && greenSaturation == 0f &&
            blueHue == 0f && blueSaturation == 0f && shadowsTint == 0f

    fun clamped() = Calibration(
        redHue.coerceIn(-1f, 1f),
        redSaturation.coerceIn(-1f, 1f),
        greenHue.coerceIn(-1f, 1f),
        greenSaturation.coerceIn(-1f, 1f),
        blueHue.coerceIn(-1f, 1f),
        blueSaturation.coerceIn(-1f, 1f),
        shadowsTint.coerceIn(-1f, 1f),
    )

    companion object {
        val NONE = Calibration()
        const val MAX_HUE_DEGREES = 30f
        const val MAX_SATURATION_CHANGE = 0.5f
    }
}
