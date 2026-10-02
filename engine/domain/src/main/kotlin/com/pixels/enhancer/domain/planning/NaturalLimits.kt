package com.pixels.enhancer.domain.planning

/** Inclusive range an adjustment amount may never leave, whatever the user strength. */
data class AmountRange(val min: Float, val max: Float) {
    fun clamp(value: Float) = value.coerceIn(min, max)
}

/**
 * Every threshold and cap the planner uses. Nominal caps (`max…`) bound the base plan; the
 * [hardRanges] bound the plan after user-strength scaling. Keeping both here means a preset can
 * change behaviour without touching planner code.
 */
data class NaturalLimits(
    // Exposure — mean luma band treated as "already fine".
    val exposureTargetLow: Float = 0.40f,
    val exposureTargetHigh: Float = 0.56f,
    val exposureTarget: Float = 0.46f,
    /** Only part of the measured gap is closed: mean luma also depends on scene content. */
    val exposureCorrectionDamping: Float = 0.6f,
    val maxExposureLiftEv: Float = 0.6f,
    val maxExposureCutEv: Float = 0.4f,
    /** Lifting exposure on an image that already clips would clip more; scale the lift down instead. */
    val clippedHighlightLiftFactor: Float = 0.4f,
    val significantHighlightClipping: Float = 0.02f,

    // Shadows / highlights
    val shadowFloorTarget: Float = 0.06f,
    val maxShadowLift: Float = 0.10f,
    val highlightCeilingTarget: Float = 0.96f,
    val highlightClippingForFullRecovery: Float = 0.06f,
    val maxHighlightRecovery: Float = 0.10f,

    // Contrast — p95-p5 luma spread
    val flatContrastThreshold: Float = 0.55f,
    val harshContrastThreshold: Float = 0.94f,
    val maxContrastBoost: Float = 0.10f,
    val maxContrastReduction: Float = 0.04f,

    // White balance
    val colorCastThreshold: Float = 0.5f,
    val colorCastForFullCorrection: Float = 0.9f,
    val maxWhiteBalanceCorrection: Float = 0.75f,
    val lowConfidenceNeutralFraction: Float = 0.05f,
    val lowConfidenceWhiteBalanceFactor: Float = 0.5f,
    /** Very saturated scenes bias the grey-edge estimate, so a detected cast is trusted less. */
    val vividSceneWhiteBalanceFactor: Float = 0.5f,
    val minChannelGain: Float = 0.80f,
    val maxChannelGain: Float = 1.25f,

    // Saturation — mean chroma
    val dullSaturationThreshold: Float = 0.10f,
    val vividSaturationThreshold: Float = 0.32f,
    val maxSaturationBoost: Float = 0.08f,
    val maxSaturationReduction: Float = 0.12f,

    // Noise
    val noiseThreshold: Float = 0.22f,
    val noiseForFullReduction: Float = 0.75f,
    val minNoiseReduction: Float = 0.25f,
    val maxNoiseReduction: Float = 0.8f,

    // Detail (local contrast)
    val maxDetail: Float = 0.12f,

    // Sharpening
    val softSharpnessThreshold: Float = 0.55f,
    val crispSharpnessThreshold: Float = 0.75f,
    val maxSharpening: Float = 0.35f,
    /** Small sharpening that only restores what denoising softened. */
    val denoiseCompensationSharpening: Float = 0.08f,
    /** Noisy images get gentler sharpening: sharpening amplifies the noise left after denoising. */
    val noisySharpeningFactor: Float = 0.5f,

    val hardRanges: Map<AdjustmentKind, AmountRange> = DEFAULT_HARD_RANGES,
) {
    fun hardRange(kind: AdjustmentKind): AmountRange = hardRanges.getValue(kind)

    companion object {
        val DEFAULT_HARD_RANGES = mapOf(
            AdjustmentKind.EXPOSURE to AmountRange(-0.6f, 0.9f),
            AdjustmentKind.CONTRAST to AmountRange(-0.06f, 0.14f),
            AdjustmentKind.HIGHLIGHTS to AmountRange(-0.15f, 0f),
            AdjustmentKind.SHADOWS to AmountRange(0f, 0.15f),
            AdjustmentKind.WHITE_BALANCE to AmountRange(0f, 1f),
            AdjustmentKind.TEMPERATURE to AmountRange(0f, 0f),
            AdjustmentKind.TINT to AmountRange(0f, 0f),
            AdjustmentKind.GLOBAL_SATURATION to AmountRange(0f, 0f),
            AdjustmentKind.VIGNETTE to AmountRange(0f, 0f),
            AdjustmentKind.GRAIN to AmountRange(0f, 0f),
            AdjustmentKind.SATURATION to AmountRange(-0.2f, 0.12f),
            AdjustmentKind.NOISE_REDUCTION to AmountRange(0f, 1f),
            AdjustmentKind.DETAIL to AmountRange(0f, 0.18f),
            AdjustmentKind.SHARPENING to AmountRange(0f, 0.5f),
        )
    }
}
