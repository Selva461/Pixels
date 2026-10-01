package com.pixels.enhancer.domain.validation

import com.pixels.enhancer.domain.analysis.LuminanceStatistics
import com.pixels.enhancer.domain.analysis.SaturationEstimator
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import java.util.Locale
import kotlin.math.abs

/**
 * Last line of defence before saving: catches broken output (wrong size, alpha damage, blank
 * frames) and edits that went unnaturally far (new clipping, colour explosion).
 */
class NaturalOutputValidator(private val limits: ValidationLimits = ValidationLimits()) : OutputValidator {

    override fun validate(original: PixelBuffer, enhanced: PixelBuffer): ValidationResult {
        val dimensions = checkDimensions(original, enhanced)
        if (!dimensions.passed) return ValidationResult(listOf(dimensions))

        val before = LuminanceStatistics.compute(original)
        val after = LuminanceStatistics.compute(enhanced)
        val saturationBefore = SaturationEstimator.compute(original)
        val saturationAfter = SaturationEstimator.compute(enhanced)
        return ValidationResult(
            listOf(
                dimensions,
                checkAlpha(original, enhanced),
                checkNotBlank(before.mean, after.mean),
                checkIncrease("Highlight clipping", before.highlightClipping, after.highlightClipping, limits.maxHighlightClippingIncrease),
                checkIncrease("Shadow clipping", before.shadowClipping, after.shadowClipping, limits.maxShadowClippingIncrease),
                checkIncrease("Mean chroma", saturationBefore.meanChroma, saturationAfter.meanChroma, limits.maxMeanChromaIncrease),
                checkIncrease(
                    "Extreme chroma",
                    saturationBefore.extremeChromaFraction,
                    saturationAfter.extremeChromaFraction,
                    limits.maxExtremeChromaIncrease,
                ),
                checkBrightnessShift(before.mean, after.mean),
            ),
        )
    }

    private fun checkDimensions(original: PixelBuffer, enhanced: PixelBuffer): ValidationCheck {
        val passed = original.width == enhanced.width && original.height == enhanced.height
        return ValidationCheck("Dimensions", passed, "${original.width}x${original.height} -> ${enhanced.width}x${enhanced.height}")
    }

    private fun checkAlpha(original: PixelBuffer, enhanced: PixelBuffer): ValidationCheck {
        var changed = 0
        for (index in original.pixels.indices) {
            if (Argb.alpha(original.pixels[index]) != Argb.alpha(enhanced.pixels[index])) changed++
        }
        return ValidationCheck("Alpha preserved", changed == 0, "$changed pixels changed alpha")
    }

    private fun checkNotBlank(meanBefore: Float, meanAfter: Float): ValidationCheck {
        val becameBlack = meanAfter < limits.blankLumaThreshold && meanBefore >= limits.blankLumaThreshold
        val becameWhite = meanAfter > 1f - limits.blankLumaThreshold && meanBefore <= 1f - limits.blankLumaThreshold
        return ValidationCheck("Not blank", !becameBlack && !becameWhite, "mean luma ${fmt(meanBefore)} -> ${fmt(meanAfter)}")
    }

    private fun checkIncrease(name: String, before: Float, after: Float, maxIncrease: Float) =
        ValidationCheck(name, after - before <= maxIncrease, "${fmt(before)} -> ${fmt(after)} (max +${fmt(maxIncrease)})")

    private fun checkBrightnessShift(meanBefore: Float, meanAfter: Float) = ValidationCheck(
        "Brightness shift",
        abs(meanAfter - meanBefore) <= limits.maxMeanLumaShift,
        "${fmt(meanBefore)} -> ${fmt(meanAfter)} (max ±${fmt(limits.maxMeanLumaShift)})",
    )

    private fun fmt(value: Float) = String.format(Locale.ROOT, "%.3f", value)
}

data class ValidationLimits(
    val maxHighlightClippingIncrease: Float = 0.02f,
    val maxShadowClippingIncrease: Float = 0.02f,
    val maxMeanChromaIncrease: Float = 0.08f,
    val maxExtremeChromaIncrease: Float = 0.01f,
    val maxMeanLumaShift: Float = 0.25f,
    val blankLumaThreshold: Float = 0.01f,
)
