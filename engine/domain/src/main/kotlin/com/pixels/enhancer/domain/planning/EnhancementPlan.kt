package com.pixels.enhancer.domain.planning

import com.pixels.enhancer.core.constants.ENHANCEMENT_ALGORITHM_VERSION

/**
 * One planned correction. [reason] is mandatory so every automatic decision — including the
 * decision to do nothing — is visible in logs and the debug screen.
 */
data class Adjustment(
    val amount: Float,
    val enabled: Boolean,
    val reason: String,
) {
    companion object {
        fun none(reason: String) = Adjustment(0f, enabled = false, reason = reason)
        fun of(amount: Float, reason: String) = Adjustment(amount, enabled = amount != 0f, reason = reason)

        /** Creative adjustments the planner never sets on its own. */
        val MANUAL_ONLY = none("Not set (manual control only)")
    }
}

enum class AdjustmentKind(val label: String) {
    EXPOSURE("Exposure"),
    CONTRAST("Contrast"),
    HIGHLIGHTS("Highlights"),
    SHADOWS("Shadows"),
    WHITES("Whites"),
    BLACKS("Blacks"),
    MIDTONES("Midtones"),
    BRIGHTNESS("Brightness"),
    GAMMA("Gamma"),
    DEHAZE("Dehaze"),
    WHITE_BALANCE("White Balance"),
    TEMPERATURE("Temperature"),
    TINT("Tint"),
    SATURATION("Vibrance"),
    GLOBAL_SATURATION("Saturation"),
    NOISE_REDUCTION("Denoise"),
    DETAIL("Clarity"),
    TEXTURE("Texture"),
    SHARPENING("Sharpen"),
    SHARPEN_MASKING("Sharpen masking"),
    COLOR_NOISE_REDUCTION("Color denoise"),
    VIGNETTE("Vignette"),
    GRAIN("Grain"),
}

/**
 * Amount units:
 * - exposure: EV stops (white point stays anchored)
 * - contrast / highlights / shadows: peak tone-curve displacement (0..1 luma)
 * - whiteBalance: fraction of the measured cast to remove
 * - temperature / tint: −1..1 creative shift (warm/cool, magenta/green)
 * - saturation: vibrance-style chroma offset (−0.1 = 10 % less chroma, boosts favour muted colours)
 * - globalSaturation: uniform chroma offset (−1 = monochrome)
 * - noiseReduction / detail / sharpening: stage strength
 * - whites / blacks / midtones: peak tone-curve displacement in that tonal region
 * - dehaze: −1..1 fraction of the measured haze veil removed (negative adds haze)
 * - vignette: −1..1 (negative darkens corners); grain: 0..1
 */
data class EnhancementPlan(
    val exposure: Adjustment,
    val contrast: Adjustment,
    val highlights: Adjustment,
    val shadows: Adjustment,
    val whiteBalance: Adjustment,
    val saturation: Adjustment,
    val noiseReduction: Adjustment,
    val detail: Adjustment,
    val sharpening: Adjustment,
    val strength: Float,
    val presetId: String,
    val temperature: Adjustment = Adjustment.MANUAL_ONLY,
    val tint: Adjustment = Adjustment.MANUAL_ONLY,
    val globalSaturation: Adjustment = Adjustment.MANUAL_ONLY,
    val vignette: Adjustment = Adjustment.MANUAL_ONLY,
    val grain: Adjustment = Adjustment.MANUAL_ONLY,
    val whites: Adjustment = Adjustment.MANUAL_ONLY,
    val blacks: Adjustment = Adjustment.MANUAL_ONLY,
    val midtones: Adjustment = Adjustment.MANUAL_ONLY,
    val dehaze: Adjustment = Adjustment.MANUAL_ONLY,
    val brightness: Adjustment = Adjustment.MANUAL_ONLY,
    /** log2 of the gamma value: 0 = 1.0, ±1 = 2.0 / 0.5. */
    val gamma: Adjustment = Adjustment.MANUAL_ONLY,
    val texture: Adjustment = Adjustment.MANUAL_ONLY,
    val sharpenMasking: Adjustment = Adjustment.MANUAL_ONLY,
    val colorNoiseReduction: Adjustment = Adjustment.MANUAL_ONLY,
    /** Per-hue-band HSL shifts (manual only). */
    val colorMixer: ColorMixer = ColorMixer.NONE,
    val algorithmVersion: String = ENHANCEMENT_ALGORITHM_VERSION,
) {
    operator fun get(kind: AdjustmentKind): Adjustment = when (kind) {
        AdjustmentKind.EXPOSURE -> exposure
        AdjustmentKind.CONTRAST -> contrast
        AdjustmentKind.HIGHLIGHTS -> highlights
        AdjustmentKind.SHADOWS -> shadows
        AdjustmentKind.WHITES -> whites
        AdjustmentKind.BLACKS -> blacks
        AdjustmentKind.MIDTONES -> midtones
        AdjustmentKind.BRIGHTNESS -> brightness
        AdjustmentKind.GAMMA -> gamma
        AdjustmentKind.TEXTURE -> texture
        AdjustmentKind.SHARPEN_MASKING -> sharpenMasking
        AdjustmentKind.COLOR_NOISE_REDUCTION -> colorNoiseReduction
        AdjustmentKind.DEHAZE -> dehaze
        AdjustmentKind.WHITE_BALANCE -> whiteBalance
        AdjustmentKind.TEMPERATURE -> temperature
        AdjustmentKind.TINT -> tint
        AdjustmentKind.SATURATION -> saturation
        AdjustmentKind.GLOBAL_SATURATION -> globalSaturation
        AdjustmentKind.NOISE_REDUCTION -> noiseReduction
        AdjustmentKind.DETAIL -> detail
        AdjustmentKind.SHARPENING -> sharpening
        AdjustmentKind.VIGNETTE -> vignette
        AdjustmentKind.GRAIN -> grain
    }

    fun entries(): List<Pair<AdjustmentKind, Adjustment>> = AdjustmentKind.entries.map { it to get(it) }

    fun mapAdjustments(transform: (AdjustmentKind, Adjustment) -> Adjustment) = copy(
        exposure = transform(AdjustmentKind.EXPOSURE, exposure),
        contrast = transform(AdjustmentKind.CONTRAST, contrast),
        highlights = transform(AdjustmentKind.HIGHLIGHTS, highlights),
        shadows = transform(AdjustmentKind.SHADOWS, shadows),
        whites = transform(AdjustmentKind.WHITES, whites),
        blacks = transform(AdjustmentKind.BLACKS, blacks),
        midtones = transform(AdjustmentKind.MIDTONES, midtones),
        brightness = transform(AdjustmentKind.BRIGHTNESS, brightness),
        gamma = transform(AdjustmentKind.GAMMA, gamma),
        texture = transform(AdjustmentKind.TEXTURE, texture),
        sharpenMasking = transform(AdjustmentKind.SHARPEN_MASKING, sharpenMasking),
        colorNoiseReduction = transform(AdjustmentKind.COLOR_NOISE_REDUCTION, colorNoiseReduction),
        dehaze = transform(AdjustmentKind.DEHAZE, dehaze),
        whiteBalance = transform(AdjustmentKind.WHITE_BALANCE, whiteBalance),
        temperature = transform(AdjustmentKind.TEMPERATURE, temperature),
        tint = transform(AdjustmentKind.TINT, tint),
        saturation = transform(AdjustmentKind.SATURATION, saturation),
        globalSaturation = transform(AdjustmentKind.GLOBAL_SATURATION, globalSaturation),
        noiseReduction = transform(AdjustmentKind.NOISE_REDUCTION, noiseReduction),
        detail = transform(AdjustmentKind.DETAIL, detail),
        sharpening = transform(AdjustmentKind.SHARPENING, sharpening),
        vignette = transform(AdjustmentKind.VIGNETTE, vignette),
        grain = transform(AdjustmentKind.GRAIN, grain),
    )

    val hasAnyCorrection: Boolean get() = AdjustmentKind.entries.any { get(it).enabled } || !colorMixer.isNeutral
}
