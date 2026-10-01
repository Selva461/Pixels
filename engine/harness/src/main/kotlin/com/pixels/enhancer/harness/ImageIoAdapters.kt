package com.pixels.enhancer.harness

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
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Desktop stand-in for the Android decoder. Note: ImageIO ignores EXIF orientation, so rotated
 * phone JPEGs appear sideways here — the Android adapter handles orientation.
 */
class FileImageRepository : ImageRepository {

    override suspend fun readSource(sourceId: String): ImageSource {
        val file = File(sourceId)
        if (!file.exists()) throw EnhancerException(ErrorCode.IMAGE_NOT_FOUND, "Missing file")
        val image = read(file)
        return ImageSource(sourceId, file.name, mimeTypeOf(file), image.width, image.height, rotationDegrees = 0, mirrored = false)
    }

    override suspend fun loadWorkingImage(source: ImageSource, maxLongEdge: Int): PixelBuffer =
        PixelResampler.downscaleToFit(ImageIoConversions.toPixelBuffer(read(File(source.id))), maxLongEdge)

    private fun read(file: File): BufferedImage =
        ImageIO.read(file) ?: throw EnhancerException(ErrorCode.IMAGE_DECODE_FAILED, "ImageIO could not decode ${file.name}")

    private fun mimeTypeOf(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }
}

class DirectoryImageSaver(private val directory: File) : ImageSaver {
    override suspend fun save(image: PixelBuffer, request: SaveRequest): SavedImage {
        val name = request.displayName.substringBeforeLast('.') + ".png"
        val file = File(directory, name)
        ImageIO.write(ImageIoConversions.toBufferedImage(image), "png", file)
        return SavedImage(file.path, name)
    }
}

object ImageIoConversions {
    fun toPixelBuffer(image: BufferedImage): PixelBuffer {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return PixelBuffer(image.width, image.height, pixels)
    }

    fun toBufferedImage(buffer: PixelBuffer): BufferedImage =
        BufferedImage(buffer.width, buffer.height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, buffer.width, buffer.height, buffer.pixels, 0, buffer.width)
        }

    /** Before | After, side by side. */
    fun sideBySide(before: PixelBuffer, after: PixelBuffer): BufferedImage {
        val gap = 8
        val canvas = BufferedImage(before.width + gap + after.width, maxOf(before.height, after.height), BufferedImage.TYPE_INT_RGB)
        canvas.setRGB(0, 0, before.width, before.height, before.pixels, 0, before.width)
        canvas.setRGB(before.width + gap, 0, after.width, after.height, after.pixels, 0, after.width)
        return canvas
    }
}

object ConsoleLogger : EnhancerLogger {
    var verbose = false

    override fun event(name: String, fields: Map<String, Any?>) {
        if (verbose) println(LogFormat.format(name, fields))
    }

    override fun error(name: String, fields: Map<String, Any?>, throwable: Throwable?) {
        System.err.println(LogFormat.format(name, fields) + (throwable?.let { " cause=${it.message}" } ?: ""))
    }
}
