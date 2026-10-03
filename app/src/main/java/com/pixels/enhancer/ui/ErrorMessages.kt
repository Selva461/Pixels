package com.pixels.enhancer.ui

import androidx.annotation.StringRes
import com.pixels.enhancer.R
import com.pixels.enhancer.core.error.ErrorCode

/** Stable error codes to user-facing text. Codes never reach the user verbatim. */
object ErrorMessages {
    @StringRes
    fun forCode(code: ErrorCode): Int = when (code) {
        ErrorCode.IMAGE_NOT_FOUND -> R.string.error_image_not_found
        ErrorCode.IMAGE_UNSUPPORTED -> R.string.error_image_unsupported
        ErrorCode.IMAGE_DECODE_FAILED -> R.string.error_image_decode_failed
        ErrorCode.IMAGE_TOO_LARGE -> R.string.error_image_too_large
        ErrorCode.OUT_OF_MEMORY -> R.string.error_out_of_memory
        ErrorCode.ANALYSIS_FAILED -> R.string.error_analysis_failed
        ErrorCode.PROCESSING_FAILED -> R.string.error_processing_failed
        ErrorCode.VALIDATION_FAILED -> R.string.error_validation_failed
        ErrorCode.OUTPUT_ENCODE_FAILED -> R.string.error_output_encode_failed
        ErrorCode.SAVE_FAILED -> R.string.error_save_failed
        ErrorCode.INSUFFICIENT_STORAGE -> R.string.error_insufficient_storage
        ErrorCode.PERMISSION_DENIED -> R.string.error_permission_denied
        ErrorCode.UNKNOWN -> R.string.error_unknown
    }
}
