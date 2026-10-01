package com.pixels.enhancer.data.decoder

import android.graphics.Bitmap
import com.pixels.enhancer.domain.image.PixelBuffer

/** The only place Bitmap and the engine's PixelBuffer meet; both use packed ARGB_8888. */
object BitmapConversions {

    fun toPixelBuffer(bitmap: Bitmap): PixelBuffer {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val width = argb.width
        val height = argb.height
        val pixels = IntArray(width * height)
        argb.getPixels(pixels, 0, width, 0, 0, width, height)
        if (argb !== bitmap) argb.recycle()
        return PixelBuffer(width, height, pixels)
    }

    fun toBitmap(buffer: PixelBuffer): Bitmap =
        Bitmap.createBitmap(buffer.pixels, buffer.width, buffer.height, Bitmap.Config.ARGB_8888)
}
