package com.pixels.enhancer.core.error

/**
 * Stable error codes. The UI maps each code to user-facing text; logs and debug reports use the
 * code name, so never rename an existing entry.
 */
enum class ErrorCode {
    IMAGE_NOT_FOUND,
    IMAGE_UNSUPPORTED,
    IMAGE_DECODE_FAILED,
    IMAGE_TOO_LARGE,
    OUT_OF_MEMORY,
    ANALYSIS_FAILED,
    PROCESSING_FAILED,
    VALIDATION_FAILED,
    OUTPUT_ENCODE_FAILED,
    SAVE_FAILED,
    INSUFFICIENT_STORAGE,
    PERMISSION_DENIED,
    UNKNOWN,
}
