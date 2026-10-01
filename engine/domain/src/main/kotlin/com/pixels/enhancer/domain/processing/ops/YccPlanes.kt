package com.pixels.enhancer.domain.processing.ops

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer

/**
 * Exact, lossless-in-float split of RGB into luma and two colour-difference planes
 * (Cb = B − Y, Cr = R − Y), so luma and chroma noise can be treated differently.
 */
object YccPlanes {
    private const val CHANNEL_SCALE = 1f / Argb.CHANNEL_MAX

    fun split(image: PixelBuffer, luma: FloatArray, blueDifference: FloatArray, redDifference: FloatArray) {
        val pixels = image.pixels
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = Argb.red(color) * CHANNEL_SCALE
            val green = Argb.green(color) * CHANNEL_SCALE
            val blue = Argb.blue(color) * CHANNEL_SCALE
            val y = Luma.of(red, green, blue)
            luma[index] = y
            blueDifference[index] = blue - y
            redDifference[index] = red - y
        }
    }

    fun merge(luma: FloatArray, blueDifference: FloatArray, redDifference: FloatArray, image: PixelBuffer) {
        val pixels = image.pixels
        for (index in pixels.indices) {
            val y = luma[index]
            val red = y + redDifference[index]
            val blue = y + blueDifference[index]
            val green = (y - Luma.RED_WEIGHT * red - Luma.BLUE_WEIGHT * blue) / Luma.GREEN_WEIGHT
            pixels[index] = ChromaOps.fitToGamut(Argb.alpha(pixels[index]), red, green, blue, y)
        }
    }
}
