package com.pixels.enhancer.domain.validation

import com.pixels.enhancer.domain.image.PixelBuffer

interface OutputValidator {
    /** Compares the enhanced image against the original it was produced from. */
    fun validate(original: PixelBuffer, enhanced: PixelBuffer, mode: ValidationMode = ValidationMode.NATURAL): ValidationResult
}

enum class ValidationMode {
    /** Automatic enhancement: broken output and unnatural changes (new clipping, colour explosion) both fail. */
    NATURAL,

    /**
     * The user asked for a creative look with manual controls; big brightness or colour changes are
     * intended, so only broken output (wrong size, alpha damage, blank frame) fails.
     */
    STRUCTURAL,
}

data class ValidationCheck(val name: String, val passed: Boolean, val detail: String)

data class ValidationResult(val checks: List<ValidationCheck>) {
    val passed: Boolean get() = checks.all { it.passed }
    val failures: List<ValidationCheck> get() = checks.filterNot { it.passed }
}
