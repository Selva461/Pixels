package com.pixels.enhancer.domain.planning

/** The eight hue bands of the colour mixer; [centerDegrees] is the HSV hue each band is centred on. */
enum class HueBand(val label: String, val centerDegrees: Float) {
    RED("Red", 0f),
    ORANGE("Orange", 30f),
    YELLOW("Yellow", 60f),
    GREEN("Green", 120f),
    AQUA("Aqua", 180f),
    BLUE("Blue", 225f),
    PURPLE("Purple", 270f),
    MAGENTA("Magenta", 315f),
}

/** One band's shift, each −1..1: hue (±30°), saturation (×0..2) and luminance (±25 % of range, scaled by colourfulness). */
data class HslShift(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f) {
    val isNeutral: Boolean get() = hue == 0f && saturation == 0f && luminance == 0f

    fun clamped() = HslShift(hue.coerceIn(-1f, 1f), saturation.coerceIn(-1f, 1f), luminance.coerceIn(-1f, 1f))
}

data class ColorMixer(val shifts: Map<HueBand, HslShift> = emptyMap()) {
    operator fun get(band: HueBand): HslShift = shifts[band] ?: HslShift()

    fun with(band: HueBand, shift: HslShift): ColorMixer {
        val clamped = shift.clamped()
        return ColorMixer(if (clamped.isNeutral) shifts - band else shifts + (band to clamped))
    }

    val isNeutral: Boolean get() = shifts.values.all { it.isNeutral }

    companion object {
        val NONE = ColorMixer()
    }
}
