package com.pixels.enhancer.testing

import com.pixels.enhancer.core.error.EnhancerException
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.core.logging.EnhancerLogger
import com.pixels.enhancer.core.logging.LogFormat
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.PixelResampler
import com.pixels.enhancer.domain.model.ImageSource
import com.pixels.enhancer.domain.repository.ImageRepository
import com.pixels.enhancer.domain.repository.ImageSaver
import com.pixels.enhancer.domain.repository.SaveRequest
import com.pixels.enhancer.domain.repository.SavedImage

class InMemoryImageRepository(
    private val images: Map<String, PixelBuffer>,
    private val mimeType: String = "image/jpeg",
) : ImageRepository {

    override suspend fun readSource(sourceId: String): ImageSource {
        val image = images[sourceId] ?: throw EnhancerException(ErrorCode.IMAGE_NOT_FOUND, "No image $sourceId")
        return ImageSource(sourceId, "$sourceId.jpg", mimeType, image.width, image.height, rotationDegrees = 0, mirrored = false)
    }

    override suspend fun loadWorkingImage(source: ImageSource, maxLongEdge: Int): PixelBuffer =
        PixelResampler.downscaleToFit(images.getValue(source.id), maxLongEdge)
}

class RecordingImageSaver : ImageSaver {
    val saved = mutableListOf<Pair<SaveRequest, PixelBuffer>>()

    override suspend fun save(image: PixelBuffer, request: SaveRequest): SavedImage {
        saved += request to image
        return SavedImage("memory://${request.displayName}", request.displayName)
    }
}

class RecordingLogger : EnhancerLogger {
    val lines = mutableListOf<String>()

    override fun event(name: String, fields: Map<String, Any?>) {
        lines += LogFormat.format(name, fields)
    }

    override fun error(name: String, fields: Map<String, Any?>, throwable: Throwable?) {
        lines += LogFormat.format(name, fields)
    }

    fun names(): List<String> = lines.map { it.substringBefore(' ') }
}
