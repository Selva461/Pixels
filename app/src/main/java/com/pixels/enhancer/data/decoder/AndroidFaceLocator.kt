package com.pixels.enhancer.data.decoder

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import com.pixels.enhancer.domain.analysis.FaceLocator
import com.pixels.enhancer.domain.analysis.FaceRegion
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.PixelResampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds upright faces with Android's built-in on-device detector (no download, no network). Only
 * the face position is used, to brighten or darken that area; faces are never reshaped.
 */
class AndroidFaceLocator : FaceLocator {

    override suspend fun locate(image: PixelBuffer): List<FaceRegion> = withContext(Dispatchers.Default) {
        val small = PixelResampler.downscaleToFit(image, DETECT_LONG_EDGE)
        // FaceDetector requires an even width and an RGB_565 bitmap.
        val width = small.width and 1.inv()
        if (width < MIN_SIZE || small.height < MIN_SIZE) return@withContext emptyList()
        val argb = Bitmap.createBitmap(small.pixels, 0, small.width, width, small.height, Bitmap.Config.ARGB_8888)
        val rgb565 = argb.copy(Bitmap.Config.RGB_565, false)
        argb.recycle()
        try {
            val found = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val count = FaceDetector(width, small.height, MAX_FACES).findFaces(rgb565, found)
            val midPoint = PointF()
            found.take(count).filterNotNull().filter { it.confidence() >= MIN_CONFIDENCE }.map { face ->
                face.getMidPoint(midPoint)
                val eyes = face.eyesDistance()
                FaceRegion(
                    centerX = midPoint.x / width,
                    // The detector reports the point between the eyes; the face centre is a little lower.
                    centerY = (midPoint.y + eyes * EYES_TO_CENTER) / small.height,
                    radius = eyes * EYES_TO_RADIUS / width,
                    confidence = face.confidence(),
                )
            }
        } finally {
            rgb565.recycle()
        }
    }

    private companion object {
        const val DETECT_LONG_EDGE = 640
        const val MAX_FACES = 8
        const val MIN_SIZE = 32
        const val MIN_CONFIDENCE = 0.3f
        const val EYES_TO_CENTER = 0.35f
        const val EYES_TO_RADIUS = 1.3f
    }
}
