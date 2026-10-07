package com.pixels.enhancer.domain.export

enum class ExportFormat(val mimeType: String, val extension: String, val label: String, val lossy: Boolean) {
    JPEG("image/jpeg", "jpg", "JPEG", lossy = true),
    PNG("image/png", "png", "PNG", lossy = false),

    /** Smaller files than JPEG at the same quality; every current browser and gallery opens it. */
    WEBP("image/webp", "webp", "WebP", lossy = true),
}

/** Output size by long edge; [FULL] keeps the source resolution (capped by [ExportOptions.MAX_EXPORT_PIXELS]). */
enum class ExportSize(val label: String, val longEdge: Int?) {
    FULL("Full", null),
    LARGE("Large · 2560 px", 2560),
    MEDIUM("Medium · 1600 px", 1600),
    SMALL("Small · 1080 px", 1080),
}

enum class MetadataPolicy(val label: String) {
    /** Camera, date and location are copied from the original. */
    KEEP_ALL("Keep all"),

    /** Camera and date are kept; GPS location is removed. The privacy-safe default. */
    REMOVE_LOCATION("Remove location"),

    /** No metadata beyond what the encoder writes. */
    REMOVE_ALL("Remove all"),
}

data class ExportOptions(
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: Int = DEFAULT_QUALITY,
    val size: ExportSize = ExportSize.FULL,
    val metadata: MetadataPolicy = MetadataPolicy.REMOVE_LOCATION,
    val border: Border = Border.NONE,
    val watermark: Watermark = Watermark.NONE,
) {
    init {
        require(quality in MIN_QUALITY..MAX_QUALITY) { "JPEG quality must be $MIN_QUALITY..$MAX_QUALITY, was $quality" }
    }

    companion object {
        const val MIN_QUALITY = 50
        const val MAX_QUALITY = 100
        const val DEFAULT_QUALITY = 95

        /**
         * Full-resolution exports above this are decoded smaller. ~24 MP keeps peak memory near
         * 400 MB (source, output, rotation copy and encode bitmap at 4 bytes per pixel).
         */
        const val MAX_EXPORT_PIXELS = 24_000_000L
    }
}
