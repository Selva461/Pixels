package com.pixels.enhancer.data.decoder

import android.graphics.Bitmap
import android.graphics.ColorSpace
import com.pixels.enhancer.domain.color.RgbColorSpace
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import java.nio.ByteBuffer

/**
 * The only place Bitmap and the engine's PixelBuffer meet; both use packed ARGB_8888.
 *
 * Colour: `Bitmap.getPixels` converts to sRGB and clips anything wider, so a wide-gamut bitmap is
 * read and written through its raw memory instead, keeping the values in the bitmap's own space.
 */
object BitmapConversions {

    fun toPixelBuffer(bitmap: Bitmap): PixelBuffer {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val width = argb.width
        val height = argb.height
        val pixels = IntArray(width * height)
        if (PlatformColorSpaces.isSrgb(argb.colorSpace)) {
            argb.getPixels(pixels, 0, width, 0, 0, width, height)
        } else {
            readRaw(argb, pixels)
        }
        if (argb !== bitmap) argb.recycle()
        return PixelBuffer(width, height, pixels)
    }

    /** A bitmap tagged with [space], so the screen and encoders show its colours as they are. */
    fun toBitmap(buffer: PixelBuffer, space: RgbColorSpace = RgbColorSpace.SRGB): Bitmap {
        if (space.isSrgb) return Bitmap.createBitmap(buffer.pixels, buffer.width, buffer.height, Bitmap.Config.ARGB_8888)
        val bitmap = Bitmap.createBitmap(buffer.width, buffer.height, Bitmap.Config.ARGB_8888, true, PlatformColorSpaces.toAndroid(space))
        val bytes = ByteBuffer.allocate(buffer.pixelCount * BYTES_PER_PIXEL)
        for (color in buffer.pixels) {
            // Bitmap memory is premultiplied RGBA.
            val alpha = Argb.alpha(color)
            bytes.put(premultiply(Argb.red(color), alpha)).put(premultiply(Argb.green(color), alpha))
                .put(premultiply(Argb.blue(color), alpha)).put(alpha.toByte())
        }
        bytes.rewind()
        bitmap.copyPixelsFromBuffer(bytes)
        return bitmap
    }

    private fun readRaw(bitmap: Bitmap, out: IntArray) {
        val bytes = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(bytes)
        val rowBytes = bitmap.rowBytes
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val offset = y * rowBytes + x * BYTES_PER_PIXEL
                val alpha = bytes.get(offset + 3).toInt() and 0xFF
                out[y * bitmap.width + x] = Argb.pack(
                    alpha,
                    unpremultiply(bytes.get(offset).toInt() and 0xFF, alpha),
                    unpremultiply(bytes.get(offset + 1).toInt() and 0xFF, alpha),
                    unpremultiply(bytes.get(offset + 2).toInt() and 0xFF, alpha),
                )
            }
        }
    }

    private fun premultiply(channel: Int, alpha: Int): Byte =
        (if (alpha == Argb.OPAQUE_ALPHA) channel else (channel * alpha + HALF) / Argb.CHANNEL_MAX).toByte()

    private fun unpremultiply(channel: Int, alpha: Int): Int = when (alpha) {
        Argb.OPAQUE_ALPHA -> channel
        0 -> 0
        else -> (channel * Argb.CHANNEL_MAX + alpha / 2) / alpha
    }

    private const val BYTES_PER_PIXEL = 4
    private const val HALF = 127
}

/** Maps Android colour spaces to the engine's. Wide sources are worked on in Display P3. */
object PlatformColorSpaces {
    private val SRGB = ColorSpace.get(ColorSpace.Named.SRGB)
    private val DISPLAY_P3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)

    fun isSrgb(space: ColorSpace?): Boolean = space == null || space == SRGB

    /**
     * The working space for a photo encoded in [space]: Display P3 for any wider RGB space (the
     * decoder converts Adobe RGB and others into it), sRGB otherwise.
     */
    fun workingSpaceFor(space: ColorSpace?): RgbColorSpace =
        if (space is ColorSpace.Rgb && space.isWideGamut && space.model == ColorSpace.Model.RGB) RgbColorSpace.DISPLAY_P3 else RgbColorSpace.SRGB

    fun toAndroid(space: RgbColorSpace): ColorSpace = if (space.isSrgb) SRGB else DISPLAY_P3
}
