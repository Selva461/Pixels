package com.pixels.enhancer.domain.geometry

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Applies [Geometry] to pixels. Every step returns a new buffer; an identity geometry returns the
 * input unchanged, so callers must treat the result as read-only.
 */
object GeometryOps {

    fun apply(image: PixelBuffer, geometry: Geometry): PixelBuffer {
        var result = rotateQuarterTurns(image, geometry.quarterTurns)
        if (geometry.flipHorizontal) result = flipHorizontal(result)
        if (geometry.straightenDegrees != 0f) result = straighten(result, geometry.straightenDegrees)
        if (!geometry.crop.isFull) result = crop(result, geometry.crop)
        return result
    }

    /** Output size for [width]×[height] without allocating pixels. */
    fun outputSize(width: Int, height: Int, geometry: Geometry): Pair<Int, Int> {
        var w = if (geometry.swapsAxes) height else width
        var h = if (geometry.swapsAxes) width else height
        if (geometry.straightenDegrees != 0f) {
            val scale = straightenScale(w, h, geometry.straightenDegrees)
            w = max(1, (w * scale).toInt())
            h = max(1, (h * scale).toInt())
        }
        val crop = cropBounds(w, h, geometry.crop)
        return (crop[2] - crop[0]) to (crop[3] - crop[1])
    }

    fun rotateQuarterTurns(image: PixelBuffer, quarterTurns: Int): PixelBuffer {
        val turns = Math.floorMod(quarterTurns, 4)
        if (turns == 0) return image
        val w = image.width
        val h = image.height
        val outWidth = if (turns % 2 == 1) h else w
        val outHeight = if (turns % 2 == 1) w else h
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val target = when (turns) {
                    1 -> x * outWidth + (h - 1 - y)
                    2 -> (h - 1 - y) * outWidth + (w - 1 - x)
                    else -> (w - 1 - x) * outWidth + y
                }
                out[target] = image.pixels[y * w + x]
            }
        }
        return PixelBuffer(outWidth, outHeight, out)
    }

    fun flipHorizontal(image: PixelBuffer): PixelBuffer {
        val w = image.width
        val out = IntArray(image.pixelCount)
        for (y in 0 until image.height) {
            val row = y * w
            for (x in 0 until w) out[row + x] = image.pixels[row + w - 1 - x]
        }
        return PixelBuffer(w, image.height, out)
    }

    /**
     * Largest scale s such that a w·s × h·s rectangle, rotated by the tilt, still fits inside the
     * w × h image — i.e. the auto-crop that keeps the original aspect ratio with no empty corners.
     */
    fun straightenScale(width: Int, height: Int, degrees: Float): Float {
        val radians = Math.toRadians(abs(degrees).toDouble())
        val c = cos(radians)
        val s = sin(radians)
        return min(width / (width * c + height * s), height / (width * s + height * c)).toFloat()
    }

    /** Rotates by [degrees] (positive = clockwise) about the centre with bilinear sampling, then auto-crops. */
    fun straighten(image: PixelBuffer, degrees: Float): PixelBuffer {
        val scale = straightenScale(image.width, image.height, degrees)
        val outWidth = max(1, (image.width * scale).toInt())
        val outHeight = max(1, (image.height * scale).toInt())
        val radians = Math.toRadians(degrees.toDouble())
        val cosA = cos(radians).toFloat()
        val sinA = sin(radians).toFloat()
        val sourceCenterX = (image.width - 1) / 2f
        val sourceCenterY = (image.height - 1) / 2f
        val outCenterX = (outWidth - 1) / 2f
        val outCenterY = (outHeight - 1) / 2f
        val out = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            val dy = y - outCenterY
            for (x in 0 until outWidth) {
                val dx = x - outCenterX
                // Inverse mapping: rotate the output position back into the source.
                val sourceX = cosA * dx + sinA * dy + sourceCenterX
                val sourceY = -sinA * dx + cosA * dy + sourceCenterY
                out[y * outWidth + x] = sampleBilinear(image, sourceX, sourceY)
            }
        }
        return PixelBuffer(outWidth, outHeight, out)
    }

    fun crop(image: PixelBuffer, rect: CropRect): PixelBuffer {
        val bounds = cropBounds(image.width, image.height, rect)
        val left = bounds[0]
        val top = bounds[1]
        val outWidth = bounds[2] - left
        val outHeight = bounds[3] - top
        if (outWidth == image.width && outHeight == image.height) return image
        val out = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            System.arraycopy(image.pixels, (top + y) * image.width + left, out, y * outWidth, outWidth)
        }
        return PixelBuffer(outWidth, outHeight, out)
    }

    /** Pixel bounds [left, top, right, bottom) for a normalised crop; always at least 1×1. */
    private fun cropBounds(width: Int, height: Int, rect: CropRect): IntArray {
        val left = (rect.left * width).roundToInt().coerceIn(0, width - 1)
        val top = (rect.top * height).roundToInt().coerceIn(0, height - 1)
        val right = (rect.right * width).roundToInt().coerceIn(left + 1, width)
        val bottom = (rect.bottom * height).roundToInt().coerceIn(top + 1, height)
        return intArrayOf(left, top, right, bottom)
    }

    private fun sampleBilinear(image: PixelBuffer, x: Float, y: Float): Int {
        val maxX = image.width - 1
        val maxY = image.height - 1
        val cx = x.coerceIn(0f, maxX.toFloat())
        val cy = y.coerceIn(0f, maxY.toFloat())
        val x0 = floor(cx).toInt()
        val y0 = floor(cy).toInt()
        val x1 = min(x0 + 1, maxX)
        val y1 = min(y0 + 1, maxY)
        val fx = cx - x0
        val fy = cy - y0
        val topLeft = image.pixels[y0 * image.width + x0]
        val topRight = image.pixels[y0 * image.width + x1]
        val bottomLeft = image.pixels[y1 * image.width + x0]
        val bottomRight = image.pixels[y1 * image.width + x1]
        fun mix(shift: Int): Int {
            val a = (topLeft shr shift) and 0xFF
            val b = (topRight shr shift) and 0xFF
            val c = (bottomLeft shr shift) and 0xFF
            val d = (bottomRight shr shift) and 0xFF
            val top = a + (b - a) * fx
            val bottom = c + (d - c) * fx
            return (top + (bottom - top) * fy + 0.5f).toInt()
        }
        return Argb.pack(mix(24), mix(16), mix(8), mix(0))
    }
}
