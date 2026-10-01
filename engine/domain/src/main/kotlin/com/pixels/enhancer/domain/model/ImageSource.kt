package com.pixels.enhancer.domain.model

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
    val MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")

    /** Guards against decompression bombs; ~200 MP is far beyond any phone camera. */
    const val MAX_SOURCE_PIXELS = 200_000_000L

    fun isSupported(mimeType: String?): Boolean = mimeType?.lowercase() in MIME_TYPES
}

object OutputNaming {
    private const val SUFFIX = "_enhanced"
    private const val DEFAULT_BASE_NAME = "IMG"

    /** `IMG_1234.jpg` -> `IMG_1234_enhanced.jpg`; output is always JPEG for the MVP. */
    fun enhancedName(originalName: String?, extension: String = "jpg"): String {
        val base = originalName
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_BASE_NAME
        return "$base$SUFFIX.$extension"
    }
}
