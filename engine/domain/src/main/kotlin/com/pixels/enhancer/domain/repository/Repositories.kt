package com.pixels.enhancer.domain.repository

import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.model.ImageSource
import com.pixels.enhancer.domain.planning.EnhancementStrength
import com.pixels.enhancer.domain.planning.QualityPreset

/**
 * Platform adapter for reading images. Implementations throw
 * [com.pixels.enhancer.core.error.EnhancerException] with a specific code on failure.
 */
interface ImageRepository {
    /** Reads MIME type, dimensions and orientation without decoding pixels. */
    suspend fun readSource(sourceId: String): ImageSource

    /** Decodes [source], applies its orientation, and downsizes so the long edge is at most [maxLongEdge]. */
    suspend fun loadWorkingImage(source: ImageSource, maxLongEdge: Int): PixelBuffer
}

interface ImageSaver {
    /** Encodes and stores a new file; never overwrites [SaveRequest.displayName]'s original. */
    suspend fun save(image: PixelBuffer, request: SaveRequest): SavedImage
}

data class SaveRequest(val displayName: String, val mimeType: String = MIME_JPEG, val quality: Int = DEFAULT_JPEG_QUALITY) {
    companion object {
        const val MIME_JPEG = "image/jpeg"
        const val DEFAULT_JPEG_QUALITY = 95
    }
}

data class SavedImage(val id: String, val displayName: String)

data class EnhancerSettings(
    val strength: Float = EnhancementStrength.DEFAULT,
    val presetId: String = QualityPreset.NATURAL.id,
)

interface SettingsRepository {
    fun load(): EnhancerSettings
    fun save(settings: EnhancerSettings)
}
