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
    }
}

enum class AdjustmentKind(val label: String) {
    EXPOSURE("Exposure"),
    CONTRAST("Contrast"),
    HIGHLIGHTS("Highlights"),
    SHADOWS("Shadows"),
    WHITE_BALANCE("White Balance"),
    SATURATION("Saturation"),
    NOISE_REDUCTION("Denoise"),
    DETAIL("Detail"),
    SHARPENING("Sharpen"),
}

/**
 * Amount units:
 * - exposure: EV stops (white point stays anchored)
 * - contrast / highlights / shadows: peak tone-curve displacement (0..1 luma)
 * - whiteBalance: fraction of the measured cast to remove
 * - saturation: chroma scale offset (−0.1 = 10 % less chroma)
 * - noiseReduction / detail / sharpening: stage strength
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
    val algorithmVersion: String = ENHANCEMENT_ALGORITHM_VERSION,
) {
    operator fun get(kind: AdjustmentKind): Adjustment = when (kind) {
        AdjustmentKind.EXPOSURE -> exposure
        AdjustmentKind.CONTRAST -> contrast
        AdjustmentKind.HIGHLIGHTS -> highlights
        AdjustmentKind.SHADOWS -> shadows
        AdjustmentKind.WHITE_BALANCE -> whiteBalance
        AdjustmentKind.SATURATION -> saturation
        AdjustmentKind.NOISE_REDUCTION -> noiseReduction
        AdjustmentKind.DETAIL -> detail
        AdjustmentKind.SHARPENING -> sharpening
    }

    fun entries(): List<Pair<AdjustmentKind, Adjustment>> = AdjustmentKind.entries.map { it to get(it) }

    fun mapAdjustments(transform: (AdjustmentKind, Adjustment) -> Adjustment) = copy(
        exposure = transform(AdjustmentKind.EXPOSURE, exposure),
        contrast = transform(AdjustmentKind.CONTRAST, contrast),
        highlights = transform(AdjustmentKind.HIGHLIGHTS, highlights),
        shadows = transform(AdjustmentKind.SHADOWS, shadows),
        whiteBalance = transform(AdjustmentKind.WHITE_BALANCE, whiteBalance),
        saturation = transform(AdjustmentKind.SATURATION, saturation),
        noiseReduction = transform(AdjustmentKind.NOISE_REDUCTION, noiseReduction),
        detail = transform(AdjustmentKind.DETAIL, detail),
        sharpening = transform(AdjustmentKind.SHARPENING, sharpening),
    )

    val hasAnyCorrection: Boolean get() = AdjustmentKind.entries.any { get(it).enabled }
}
