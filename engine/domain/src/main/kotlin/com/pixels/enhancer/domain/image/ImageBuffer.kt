package com.pixels.enhancer.domain.image

/** Minimal image abstraction so future GPU / OpenCV buffers can coexist with [PixelBuffer]. */
interface ImageBuffer {
    val width: Int
    val height: Int
}

/**
 * Packed ARGB_8888 pixels, row-major — the same layout as Android's `Bitmap.getPixels`, so the
 * platform adapter converts with one bulk copy and the engine stays free of Android classes.
 *
 * Stages may mutate [pixels] in place; the pipeline makes exactly one working copy up front so
 * the original image is never modified.
 */
class PixelBuffer(
    override val width: Int,
    override val height: Int,
    val pixels: IntArray,
) : ImageBuffer {

    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive: ${width}x$height" }
        require(pixels.size == width * height) { "Pixel count ${pixels.size} does not match ${width}x$height" }
    }

    val pixelCount: Int get() = pixels.size

    fun copy(): PixelBuffer = PixelBuffer(width, height, pixels.copyOf())

    companion object {
        fun filled(width: Int, height: Int, argb: Int) = PixelBuffer(width, height, IntArray(width * height) { argb })
    }
}
