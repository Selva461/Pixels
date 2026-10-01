package com.pixels.enhancer.domain.image

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Area-averaging downscaler used by the JVM harness and tests. The Android adapter uses the
 * platform decoder's subsampling instead, which avoids decoding the full image first.
 */
object PixelResampler {

    fun fitWithin(longEdge: Int, width: Int, height: Int): Pair<Int, Int> {
        val currentLongEdge = max(width, height)
        if (currentLongEdge <= longEdge) return width to height
        val scale = longEdge.toFloat() / currentLongEdge
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    fun downscaleToFit(source: PixelBuffer, maxLongEdge: Int): PixelBuffer {
        val (targetWidth, targetHeight) = fitWithin(maxLongEdge, source.width, source.height)
        if (targetWidth == source.width && targetHeight == source.height) return source
        return areaAverage(source, targetWidth, targetHeight)
    }

    private fun areaAverage(source: PixelBuffer, targetWidth: Int, targetHeight: Int): PixelBuffer {
        val output = IntArray(targetWidth * targetHeight)
        val xScale = source.width.toFloat() / targetWidth
        val yScale = source.height.toFloat() / targetHeight
        for (ty in 0 until targetHeight) {
            val y0 = (ty * yScale).toInt()
            val y1 = max(y0 + 1, ((ty + 1) * yScale).toInt()).coerceAtMost(source.height)
            for (tx in 0 until targetWidth) {
                val x0 = (tx * xScale).toInt()
                val x1 = max(x0 + 1, ((tx + 1) * xScale).toInt()).coerceAtMost(source.width)
                output[ty * targetWidth + tx] = averageBlock(source, x0, x1, y0, y1)
            }
        }
        return PixelBuffer(targetWidth, targetHeight, output)
    }

    private fun averageBlock(source: PixelBuffer, x0: Int, x1: Int, y0: Int, y1: Int): Int {
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (y in y0 until y1) {
            val row = y * source.width
            for (x in x0 until x1) {
                val color = source.pixels[row + x]
                alpha += Argb.alpha(color)
                red += Argb.red(color)
                green += Argb.green(color)
                blue += Argb.blue(color)
            }
        }
        val count = (x1 - x0) * (y1 - y0)
        return Argb.pack(alpha / count, red / count, green / count, blue / count)
    }
}
