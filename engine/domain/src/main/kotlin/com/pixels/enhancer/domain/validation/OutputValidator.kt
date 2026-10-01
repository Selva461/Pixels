package com.pixels.enhancer.domain.validation

import com.pixels.enhancer.domain.image.PixelBuffer

interface OutputValidator {
    /** Compares the enhanced image against the original it was produced from. */
    fun validate(original: PixelBuffer, enhanced: PixelBuffer): ValidationResult
}

data class ValidationCheck(val name: String, val passed: Boolean, val detail: String)

data class ValidationResult(val checks: List<ValidationCheck>) {
    val passed: Boolean get() = checks.all { it.passed }
    val failures: List<ValidationCheck> get() = checks.filterNot { it.passed }
}
