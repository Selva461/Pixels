package com.pixels.enhancer.domain.analysis

import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer
import kotlin.math.max
import kotlin.math.min

/** A detected face in normalised coordinates of the working image (centre and radius as fraction of width). */
data class FaceRegion(val centerX: Float, val centerY: Float, val radius: Float, val confidence: Float)

/** Platform face detection. Used only to locate regions for exposure; never to alter faces. */
fun interface FaceLocator {
    suspend fun locate(image: PixelBuffer): List<FaceRegion>

    companion object {
        val NONE = FaceLocator { emptyList() }
    }
}

object FaceMetrics {
    /** Mean luma of the faces' inner regions (ignoring hair/background at the edge), or null without faces. */
    fun meanLuma(image: PixelBuffer, faces: List<FaceRegion>): Float? {
        if (faces.isEmpty()) return null
        var sum = 0.0
        var count = 0
        faces.forEach { face ->
            val radiusPx = face.radius * image.width * INNER_FRACTION
            val cx = face.centerX * image.width
            val cy = face.centerY * image.height
            val step = max(1, (radiusPx / SAMPLES_ACROSS).toInt())
            var y = max(0, (cy - radiusPx).toInt())
            while (y < min(image.height, (cy + radiusPx).toInt())) {
                var x = max(0, (cx - radiusPx).toInt())
                while (x < min(image.width, (cx + radiusPx).toInt())) {
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy <= radiusPx * radiusPx) {
                        sum += Luma.ofPixel(image.pixels[y * image.width + x])
                        count++
                    }
                    x += step
                }
                y += step
            }
        }
        return if (count == 0) null else (sum / count).toFloat()
    }

    private const val INNER_FRACTION = 0.6f
    private const val SAMPLES_ACROSS = 20
}
