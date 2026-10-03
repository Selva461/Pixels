package com.pixels.enhancer.domain.planning

import com.pixels.enhancer.domain.analysis.ImageAnalysis
import com.pixels.enhancer.domain.image.Srgb
import com.pixels.enhancer.domain.processing.ops.ExposureCurve
import com.pixels.enhancer.domain.processing.ops.ToneCurve
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The decision layer: detect what is wrong and plan only the minimum correction for it. Each
 * adjustment is decided by its own function and always carries a reason.
 */
class NaturalEnhancementPlanner : EnhancementPlanner {

    override fun createPlan(analysis: ImageAnalysis, userStrength: Float, preset: QualityPreset): EnhancementPlan =
        PlanScaler(preset.limits).scale(createBasePlan(analysis, preset), userStrength)

    /** The plan at [EnhancementStrength.NOMINAL], before user strength is applied. */
    fun createBasePlan(analysis: ImageAnalysis, preset: QualityPreset): EnhancementPlan {
        val limits = preset.limits
        val exposure = planExposure(analysis, limits)
        val whiteBalance = planWhiteBalance(analysis, limits)
        val noiseReduction = planNoiseReduction(analysis, limits, exposure)
        return EnhancementPlan(
            exposure = exposure,
            contrast = planContrast(analysis, limits, exposure),
            highlights = planHighlights(analysis, limits),
            shadows = planShadows(analysis, limits),
            whiteBalance = whiteBalance,
            saturation = planSaturation(analysis, limits, whiteBalance),
            noiseReduction = noiseReduction,
            detail = planDetail(analysis, limits, exposure),
            sharpening = planSharpening(analysis, limits, noiseReduction),
            strength = EnhancementStrength.NOMINAL,
            presetId = preset.id,
        )
    }

    private fun planExposure(analysis: ImageAnalysis, limits: NaturalLimits): Adjustment {
        val mean = analysis.exposureScore
        return when {
            mean < limits.exposureTargetLow -> {
                val lift = min(evToReachTarget(mean, limits), limits.maxExposureLiftEv)
                if (analysis.highlightClipping > limits.significantHighlightClipping) {
                    Adjustment.of(
                        lift * limits.clippedHighlightLiftFactor,
                        "Image is underexposed (mean luma ${fmt(mean)}) but highlights already clip " +
                            "(${pct(analysis.highlightClipping)}); lift reduced",
                    )
                } else {
                    Adjustment.of(lift, "Image is underexposed (mean luma ${fmt(mean)} < ${fmt(limits.exposureTargetLow)})")
                }
            }
            mean > limits.exposureTargetHigh -> Adjustment.of(
                max(evToReachTarget(mean, limits), -limits.maxExposureCutEv),
                "Image is overexposed (mean luma ${fmt(mean)} > ${fmt(limits.exposureTargetHigh)})",
            )
            else -> Adjustment.none("Exposure already within natural range (mean luma ${fmt(mean)})")
        }
    }

    /** EV needed to move the mean to the target in linear light, damped because mean luma also reflects scene content. */
    private fun evToReachTarget(mean: Float, limits: NaturalLimits): Float {
        val current = Srgb.decode(mean.coerceAtLeast(MIN_MEASURABLE_LUMA))
        val target = Srgb.decode(limits.exposureTarget)
        return (ln(target / current) / LN_2).toFloat() * limits.exposureCorrectionDamping
    }

    private fun planShadows(analysis: ImageAnalysis, limits: NaturalLimits): Adjustment {
        val blockedShadows = unitRatio(limits.shadowFloorTarget - analysis.luminance.p5, limits.shadowFloorTarget)
        val underexposure = unitRatio(limits.exposureTargetLow - analysis.exposureScore, UNDEREXPOSURE_RANGE)
        val need = (SHADOW_BLOCKED_WEIGHT * blockedShadows + SHADOW_UNDEREXPOSURE_WEIGHT * underexposure).coerceIn(0f, 1f)
        val amount = limits.maxShadowLift * need
        if (amount < MIN_TONE_AMOUNT) {
            return Adjustment.none("Shadows hold detail (5th percentile luma ${fmt(analysis.luminance.p5)})")
        }
        return Adjustment.of(
            amount,
            "Shadows are blocked or dark (5th percentile ${fmt(analysis.luminance.p5)}, mean ${fmt(analysis.exposureScore)})",
        )
    }

    private fun planHighlights(analysis: ImageAnalysis, limits: NaturalLimits): Adjustment {
        val clipping = unitRatio(analysis.highlightClipping, limits.highlightClippingForFullRecovery)
        val nearWhite = unitRatio(analysis.luminance.p99 - limits.highlightCeilingTarget, 1f - limits.highlightCeilingTarget)
        val overexposure = unitRatio(analysis.exposureScore - limits.exposureTargetHigh, 1f - limits.exposureTargetHigh)
        val need = (max(clipping, HIGHLIGHT_NEAR_WHITE_WEIGHT * nearWhite) + HIGHLIGHT_OVEREXPOSURE_WEIGHT * overexposure).coerceIn(0f, 1f)
        val amount = -limits.maxHighlightRecovery * need
        if (-amount < MIN_TONE_AMOUNT) {
            return Adjustment.none("Highlights are not clipping (${pct(analysis.highlightClipping)} near white)")
        }
        return Adjustment.of(
            amount,
            "Highlights are bright or clipping (${pct(analysis.highlightClipping)} near white, 99th percentile ${fmt(analysis.luminance.p99)})",
        )
    }

    private fun planContrast(analysis: ImageAnalysis, limits: NaturalLimits, exposure: Adjustment): Adjustment {
        // Judge contrast as it will be after the exposure change, which already stretches a dark image.
        val spread = predictedSpread(analysis, exposure)
        val clipping = analysis.highlightClipping + analysis.shadowClipping
        return when {
            spread < limits.flatContrastThreshold -> {
                val wanted = limits.maxContrastBoost * unitRatio(limits.flatContrastThreshold - spread, FLAT_CONTRAST_RANGE)
                val safe = clippingSafeContrast(wanted, analysis, exposure)
                val note = if (safe < wanted) "; reduced to protect shadows/highlights from clipping" else ""
                Adjustment.of(safe, "Image looks flat (tonal spread ${fmt(spread)} < ${fmt(limits.flatContrastThreshold)})$note")
            }
            spread > limits.harshContrastThreshold && clipping > HARSH_CONTRAST_MIN_CLIPPING -> Adjustment.of(
                -limits.maxContrastReduction * unitRatio(spread - limits.harshContrastThreshold, 1f - limits.harshContrastThreshold),
                "Contrast is harsh (tonal spread ${fmt(spread)}, ${pct(clipping)} clipped)",
            )
            else -> Adjustment.none("Contrast within natural range (tonal spread ${fmt(spread)})")
        }
    }

    /**
     * Halves a contrast boost until the S-curve no longer pushes the 5th-percentile tone into black
     * or the 95th into white. A dark, flat photo (e.g. night) otherwise gets its shadows crushed.
     */
    private fun clippingSafeContrast(wanted: Float, analysis: ImageAnalysis, exposure: Adjustment): Float {
        val ev = if (exposure.enabled) exposure.amount else 0f
        val p5 = ExposureCurve.applyEncoded(analysis.luminance.p5, ev)
        val p95 = ExposureCurve.applyEncoded(analysis.luminance.p95, ev)
        val shadowFloor = min(p5, CLIP_GUARD_SHADOW) - CLIP_GUARD_TOLERANCE
        val highlightCeiling = max(p95, CLIP_GUARD_HIGHLIGHT) + CLIP_GUARD_TOLERANCE
        var amount = wanted
        repeat(CLIP_GUARD_ATTEMPTS) {
            val curve = ToneCurve.build(contrast = amount, highlights = 0f, shadows = 0f)
            if (curve.map(p5) >= shadowFloor && curve.map(p95) <= highlightCeiling) return amount
            amount /= 2f
        }
        return 0f
    }

    private fun predictedSpread(analysis: ImageAnalysis, exposure: Adjustment): Float {
        if (!exposure.enabled) return analysis.contrastScore
        val ev = exposure.amount
        return ExposureCurve.applyEncoded(analysis.luminance.p95, ev) - ExposureCurve.applyEncoded(analysis.luminance.p5, ev)
    }

    private fun planWhiteBalance(analysis: ImageAnalysis, limits: NaturalLimits): Adjustment {
        val cast = analysis.colorCastScore
        if (cast < limits.colorCastThreshold) {
            return Adjustment.none("No significant colour cast (score ${fmt(cast)})")
        }
        val amount = limits.maxWhiteBalanceCorrection *
            unitRatio(cast - limits.colorCastThreshold, limits.colorCastForFullCorrection - limits.colorCastThreshold)
        val neutralFraction = analysis.neutralBalance.sampleFraction
        return when {
            neutralFraction < limits.lowConfidenceNeutralFraction -> Adjustment.of(
                amount * limits.lowConfidenceWhiteBalanceFactor,
                "Colour cast detected (score ${fmt(cast)}) but few neutral reference pixels (${pct(neutralFraction)}); correction reduced",
            )
            analysis.neutralBalance.red > analysis.neutralBalance.blue && analysis.exposureScore < limits.warmDimSceneMaxLuma -> Adjustment.of(
                amount * limits.warmDimSceneWhiteBalanceFactor,
                "Warm cast in a dim scene (score ${fmt(cast)}) looks like intended warm light; correction reduced",
            )
            analysis.saturationScore > limits.vividSaturationThreshold -> Adjustment.of(
                amount * limits.vividSceneWhiteBalanceFactor,
                "Colour cast detected (score ${fmt(cast)}) in a very colourful scene; correction reduced",
            )
            else -> Adjustment.of(amount, "Colour cast detected (score ${fmt(cast)})")
        }
    }

    private fun planSaturation(analysis: ImageAnalysis, limits: NaturalLimits, whiteBalance: Adjustment): Adjustment {
        val saturation = analysis.saturationScore
        return when {
            saturation > limits.vividSaturationThreshold -> Adjustment.of(
                -limits.maxSaturationReduction * unitRatio(saturation - limits.vividSaturationThreshold, VIVID_SATURATION_RANGE),
                "Colours are oversaturated (mean chroma ${fmt(saturation)} > ${fmt(limits.vividSaturationThreshold)})",
            )
            whiteBalance.enabled -> Adjustment.none(
                "Colour cast is corrected first; saturation left unchanged (mean chroma ${fmt(saturation)})",
            )
            saturation < limits.dullSaturationThreshold -> Adjustment.of(
                limits.maxSaturationBoost * unitRatio(limits.dullSaturationThreshold - saturation, limits.dullSaturationThreshold),
                "Colours are dull (mean chroma ${fmt(saturation)} < ${fmt(limits.dullSaturationThreshold)})",
            )
            else -> Adjustment.none("Colour saturation already within target range (mean chroma ${fmt(saturation)})")
        }
    }

    private fun planNoiseReduction(analysis: ImageAnalysis, limits: NaturalLimits, exposure: Adjustment): Adjustment {
        // An exposure lift scales noise up by the same gain, so judge the noise as it will look after the lift.
        val exposureGain = 2f.pow(max(0f, exposure.amount))
        val visibleNoise = analysis.noiseScore * exposureGain
        if (visibleNoise < limits.noiseThreshold) {
            return Adjustment.none("Noise is low (score ${fmt(visibleNoise)})")
        }
        val severity = unitRatio(visibleNoise - limits.noiseThreshold, limits.noiseForFullReduction - limits.noiseThreshold)
        val amount = limits.minNoiseReduction + (limits.maxNoiseReduction - limits.minNoiseReduction) * severity
        return Adjustment.of(amount, "Visible noise detected (score ${fmt(visibleNoise)}, sigma ${fmt(analysis.noiseSigma)})")
    }

    private fun planDetail(analysis: ImageAnalysis, limits: NaturalLimits, exposure: Adjustment): Adjustment {
        val spread = predictedSpread(analysis, exposure)
        val haze = unitRatio(limits.flatContrastThreshold - spread, FLAT_CONTRAST_RANGE)
        val softness = unitRatio(limits.softSharpnessThreshold - analysis.sharpnessScore, limits.softSharpnessThreshold)
        // Local contrast also amplifies noise, so back off on noisy images.
        val amount = limits.maxDetail * max(haze, DETAIL_SOFTNESS_WEIGHT * softness) * (1f - analysis.noiseScore)
        if (amount < MIN_DETAIL_AMOUNT) {
            return Adjustment.none("Local detail adequate (tonal spread ${fmt(spread)}, sharpness ${fmt(analysis.sharpnessScore)})")
        }
        return Adjustment.of(amount, "Local detail is weak (tonal spread ${fmt(spread)}, sharpness ${fmt(analysis.sharpnessScore)})")
    }

    private fun planSharpening(analysis: ImageAnalysis, limits: NaturalLimits, noiseReduction: Adjustment): Adjustment {
        val sharpness = analysis.sharpnessScore
        val softness = unitRatio(limits.softSharpnessThreshold - sharpness, SOFTNESS_RANGE)
        var amount = limits.maxSharpening * softness
        val reasons = mutableListOf<String>()
        if (softness > 0f) reasons += "softness detected (sharpness ${fmt(sharpness)})"
        if (noiseReduction.enabled && amount < limits.denoiseCompensationSharpening) {
            amount = limits.denoiseCompensationSharpening
            reasons += "restoring detail softened by denoising"
        }
        if (sharpness >= limits.crispSharpnessThreshold) {
            amount = min(amount, limits.denoiseCompensationSharpening * limits.noisySharpeningFactor)
        }
        if (amount > 0f && analysis.noiseScore >= limits.noiseThreshold) {
            amount *= limits.noisySharpeningFactor
            reasons += "reduced because the image is noisy"
        }
        if (amount <= 0f) {
            return Adjustment.none("Image is already sharp enough (sharpness ${fmt(sharpness)})")
        }
        return Adjustment.of(amount, reasons.joinToString("; ").replaceFirstChar { it.uppercase() })
    }

    private fun unitRatio(value: Float, range: Float): Float = if (range <= 0f) 0f else (value / range).coerceIn(0f, 1f)

    private fun fmt(value: Float) = String.format(Locale.ROOT, "%.2f", value)

    private fun pct(fraction: Float) = String.format(Locale.ROOT, "%.1f%%", fraction * PERCENT)

    private companion object {
        const val LN_2 = 0.6931471805599453
        const val PERCENT = 100f
        const val MIN_MEASURABLE_LUMA = 0.02f

        /** Tone amounts below this are invisible; skip the stage rather than run a no-op curve. */
        const val MIN_TONE_AMOUNT = 0.01f
        const val MIN_DETAIL_AMOUNT = 0.01f

        const val SHADOW_BLOCKED_WEIGHT = 0.5f
        const val SHADOW_UNDEREXPOSURE_WEIGHT = 0.7f
        const val HIGHLIGHT_NEAR_WHITE_WEIGHT = 0.5f
        const val HIGHLIGHT_OVEREXPOSURE_WEIGHT = 0.5f
        const val DETAIL_SOFTNESS_WEIGHT = 0.5f

        /** Mean-luma deficit (below the exposure band) that earns the full underexposure share of shadow lift. */
        const val UNDEREXPOSURE_RANGE = 0.2f

        /** Tonal-spread deficit (below the flat threshold) that earns the full contrast boost. */
        const val FLAT_CONTRAST_RANGE = 0.25f

        /** Mean chroma excess (above the vivid threshold) that earns the full saturation reduction. */
        const val VIVID_SATURATION_RANGE = 0.15f

        /** Sharpness deficit (below the soft threshold) that earns the full sharpening amount. */
        const val SOFTNESS_RANGE = 0.35f

        /** Tones the contrast boost must not push the 5th/95th percentiles past (≈9/255 and ≈246/255). */
        const val CLIP_GUARD_SHADOW = 0.035f
        const val CLIP_GUARD_HIGHLIGHT = 0.965f
        const val CLIP_GUARD_TOLERANCE = 0.005f
        const val CLIP_GUARD_ATTEMPTS = 4

        /** Contrast is only reduced when it is actually destroying detail at both ends. */
        const val HARSH_CONTRAST_MIN_CLIPPING = 0.04f
    }
}
