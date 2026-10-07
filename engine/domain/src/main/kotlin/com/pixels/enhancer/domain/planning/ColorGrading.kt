package com.pixels.enhancer.domain.planning

/** One colour-grading wheel: a tint [hue] in degrees (0 red, 120 green, 240 blue), its [saturation] 0..1, and a [luminance] shift −1..1. */
data class GradeWheel(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f) {
    val isNeutral: Boolean get() = saturation == 0f && luminance == 0f

    fun clamped() = GradeWheel(((hue % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE, saturation.coerceIn(0f, 1f), luminance.coerceIn(-1f, 1f))

    companion object {
        val NONE = GradeWheel()
        private const val FULL_CIRCLE = 360f
    }
}

enum class GradeRange(val label: String) { SHADOWS("Shadows"), MIDTONES("Midtones"), HIGHLIGHTS("Highlights"), GLOBAL("Global") }

/**
 * Split-toning style colour grading plus the black-and-white treatment. [blending] 0..1 widens the
 * overlap between ranges; [balance] −1..1 moves the split toward shadows (−) or highlights (+).
 * With [monochrome] the photo is converted to black and white after the colour mixer, so the
 * mixer's luminance sliders act as the black-and-white mix.
 */
data class ColorGrading(
    val shadows: GradeWheel = GradeWheel.NONE,
    val midtones: GradeWheel = GradeWheel.NONE,
    val highlights: GradeWheel = GradeWheel.NONE,
    val global: GradeWheel = GradeWheel.NONE,
    val blending: Float = DEFAULT_BLENDING,
    val balance: Float = 0f,
    val monochrome: Boolean = false,
) {
    val isNeutral: Boolean get() = !monochrome && shadows.isNeutral && midtones.isNeutral && highlights.isNeutral && global.isNeutral

    operator fun get(range: GradeRange): GradeWheel = when (range) {
        GradeRange.SHADOWS -> shadows
        GradeRange.MIDTONES -> midtones
        GradeRange.HIGHLIGHTS -> highlights
        GradeRange.GLOBAL -> global
    }

    fun with(range: GradeRange, wheel: GradeWheel): ColorGrading {
        val w = wheel.clamped()
        return when (range) {
            GradeRange.SHADOWS -> copy(shadows = w)
            GradeRange.MIDTONES -> copy(midtones = w)
            GradeRange.HIGHLIGHTS -> copy(highlights = w)
            GradeRange.GLOBAL -> copy(global = w)
        }
    }

    fun clamped() = copy(
        shadows = shadows.clamped(),
        midtones = midtones.clamped(),
        highlights = highlights.clamped(),
        global = global.clamped(),
        blending = blending.coerceIn(0f, 1f),
        balance = balance.coerceIn(-1f, 1f),
    )

    companion object {
        val NONE = ColorGrading()
        const val DEFAULT_BLENDING = 0.5f
    }
}
