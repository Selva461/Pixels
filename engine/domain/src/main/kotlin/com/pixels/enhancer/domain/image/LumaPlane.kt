package com.pixels.enhancer.domain.image

import kotlin.math.roundToInt

/**
 * Luma-only editing helpers. Detail and sharpening operate on luma and write the change back as
 * an equal offset on R, G and B — this leaves chroma untouched, so sharpening never creates
 * colour fringes.
 */
object LumaPlane {

    fun extract(buffer: PixelBuffer, into: FloatArray = FloatArray(buffer.pixelCount)): FloatArray {
        val pixels = buffer.pixels
        for (index in pixels.indices) into[index] = Luma.ofPixel(pixels[index])
        return into
    }

    /** Adds `(edited - original)` luma to every channel of each pixel. */
    fun applyDelta(buffer: PixelBuffer, original: FloatArray, edited: FloatArray) {
        val pixels = buffer.pixels
        for (index in pixels.indices) {
            val delta = ((edited[index] - original[index]) * Argb.CHANNEL_MAX).roundToInt()
            if (delta == 0) continue
            val color = pixels[index]
            pixels[index] = Argb.pack(
                Argb.alpha(color),
                Argb.red(color) + delta,
                Argb.green(color) + delta,
                Argb.blue(color) + delta,
            )
        }
    }
}

/** O(N) separable box blur with edge replication. Cost is independent of the radius. */
object BoxBlur {

    /** Blurs [source] into [destination] (which may be the same array) using [scratch] as temp space. */
    fun blur(
        source: FloatArray,
        destination: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        scratch: FloatArray,
    ) {
        require(radius >= 0)
        if (radius == 0) {
            if (source !== destination) source.copyInto(destination)
            return
        }
        horizontalPass(source, scratch, width, height, radius)
        verticalPass(scratch, destination, width, height, radius)
    }

    private fun horizontalPass(source: FloatArray, destination: FloatArray, width: Int, height: Int, radius: Int) {
        val windowScale = 1f / (2 * radius + 1)
        val lastX = width - 1
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (k in -radius..radius) sum += source[row + k.coerceIn(0, lastX)]
            for (x in 0 until width) {
                destination[row + x] = sum * windowScale
                sum += source[row + (x + radius + 1).coerceAtMost(lastX)] - source[row + (x - radius).coerceAtLeast(0)]
            }
        }
    }

    private fun verticalPass(source: FloatArray, destination: FloatArray, width: Int, height: Int, radius: Int) {
        val windowScale = 1f / (2 * radius + 1)
        val lastY = height - 1
        for (x in 0 until width) {
            var sum = 0f
            for (k in -radius..radius) sum += source[k.coerceIn(0, lastY) * width + x]
            for (y in 0 until height) {
                destination[y * width + x] = sum * windowScale
                sum += source[(y + radius + 1).coerceAtMost(lastY) * width + x] -
                    source[(y - radius).coerceAtLeast(0) * width + x]
            }
        }
    }
}
