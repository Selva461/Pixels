package com.pixels.enhancer.domain.model

import com.pixels.enhancer.domain.color.RgbColorSpace

/**
 * Metadata for a picked image. [id] is an opaque platform reference (a content URI on Android)
 * and is never logged.
 */
data class ImageSource(
    val id: String,
    val displayName: String?,
    val mimeType: String,
    /** Encoded (pre-rotation) dimensions. */
    val width: Int,
    val height: Int,
    /** Clockwise rotation required for display: 0, 90, 180 or 270. */
    val rotationDegrees: Int,
    val mirrored: Boolean,
    /**
     * The space the decoded pixels are encoded in — the photo's own profile when the platform can
     * keep it (e.g. Display P3), so wide-gamut colours are never clipped on the way in.
     */
    val colorSpace: RgbColorSpace = RgbColorSpace.SRGB,
) {
    private val swapsAxes: Boolean get() = rotationDegrees == QUARTER_TURN || rotationDegrees == THREE_QUARTER_TURN

    val orientedWidth: Int get() = if (swapsAxes) height else width
    val orientedHeight: Int get() = if (swapsAxes) width else height

    private companion object {
        const val QUARTER_TURN = 90
        const val THREE_QUARTER_TURN = 270
    }
}

object SupportedFormats {
    /** HEIC/HEIF decode on Android 9+; the app's minimum is Android 10, so they are always supported. */
    val MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")

    /** Guards against decompression bombs; ~200 MP is far beyond any phone camera. */
    const val MAX_SOURCE_PIXELS = 200_000_000L

    fun isSupported(mimeType: String?): Boolean = mimeType?.lowercase() in MIME_TYPES
}

object OutputNaming {
    private const val SUFFIX = "_enhanced"
    private const val DEFAULT_BASE_NAME = "IMG"

    /** Keeps names well under the 255-byte file-name limit even in multi-byte scripts. */
    const val MAX_BASE_LENGTH = 80

    /**
     * `IMG_1234.jpg` -> `IMG_1234_enhanced.jpg`. The base name comes from another app (the source's
     * display name), so it is sanitised: path parts dropped, only letters, digits, space and
     * `._-()` kept, no leading dots, length capped.
     */
    fun enhancedName(originalName: String?, extension: String = "jpg"): String = "${safeBaseName(originalName)}$SUFFIX.$extension"

    /** `IMG_1234_enhanced.jpg` -> `IMG_1234_enhanced_srgb.jpg`: the sRGB copy saved next to a wide-gamut export. */
    fun srgbCopyName(exportName: String): String =
        if (exportName.contains('.')) "${exportName.substringBeforeLast('.')}$SRGB_SUFFIX.${exportName.substringAfterLast('.')}" else exportName + SRGB_SUFFIX

    private const val SRGB_SUFFIX = "_srgb"

    fun safeBaseName(originalName: String?): String {
        val withoutPath = originalName?.substringAfterLast('/')?.substringAfterLast('\\')
        val base = withoutPath?.let { if (it.contains('.')) it.substringBeforeLast('.') else it }
        val cleaned = base
            ?.map { if (it.isLetterOrDigit() || it in SAFE_PUNCTUATION) it else '_' }
            ?.joinToString("")
            ?.replace(REPEATED_UNDERSCORES, "_")
            ?.trimStart('.', ' ')
            ?.trim()
            ?.take(MAX_BASE_LENGTH)
        return cleaned?.takeIf { name -> name.any { it.isLetterOrDigit() } } ?: DEFAULT_BASE_NAME
    }

    private val SAFE_PUNCTUATION = setOf(' ', '.', '_', '-', '(', ')')
    private val REPEATED_UNDERSCORES = Regex("_{2,}")
}
