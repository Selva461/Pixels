package com.pixels.enhancer.data.storage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.pixels.enhancer.domain.export.ExportDecorator
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.export.Watermark
import com.pixels.enhancer.domain.export.WatermarkPosition
import com.pixels.enhancer.domain.image.PixelBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the export watermark with the platform's text renderer: white text with a soft dark
 * shadow, readable on light and dark photos.
 *
 * The text is drawn on a small transparent bitmap the size of the text, then blended into a copy
 * of the photo. The input is never modified — for an unedited photo it can be the open session's
 * cached image — and a full-resolution export needs only one extra photo-sized buffer.
 */
class AndroidWatermarkDecorator : ExportDecorator {

    override fun decorate(image: PixelBuffer, options: ExportOptions): PixelBuffer {
        val watermark = options.watermark.clamped()
        if (watermark.isNone) return image
        val text = watermark.text
        val paint = paintFor(watermark, image)
        val textWidth = paint.measureText(text)
        val ascent = -paint.ascent()
        val textHeight = ascent + paint.descent()
        val margin = paint.textSize * MARGIN
        val left = when (watermark.position) {
            WatermarkPosition.TOP_LEFT, WatermarkPosition.BOTTOM_LEFT -> margin
            WatermarkPosition.TOP_RIGHT, WatermarkPosition.BOTTOM_RIGHT -> image.width - margin - textWidth
            WatermarkPosition.CENTER -> (image.width - textWidth) / 2f
        }
        val top = when (watermark.position) {
            WatermarkPosition.TOP_LEFT, WatermarkPosition.TOP_RIGHT -> margin
            WatermarkPosition.BOTTOM_LEFT, WatermarkPosition.BOTTOM_RIGHT -> image.height - margin - textHeight
            WatermarkPosition.CENTER -> (image.height - textHeight) / 2f
        }
        // Room around the text for the blurred, offset shadow.
        val pad = ceil(paint.textSize * (2 * SHADOW_RADIUS + SHADOW_OFFSET)).toInt() + 2
        val originX = floor(left).toInt() - pad
        val originY = floor(top).toInt() - pad
        val stampWidth = ceil(textWidth).toInt() + 2 * pad + 1
        val stampHeight = ceil(textHeight).toInt() + 2 * pad + 1
        val stamp = IntArray(stampWidth * stampHeight)
        val bitmap = Bitmap.createBitmap(stampWidth, stampHeight, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawText(text, left - originX, top - originY + ascent, paint)
            // Unpremultiplied ARGB, ready for blending.
            bitmap.getPixels(stamp, 0, stampWidth, 0, 0, stampWidth, stampHeight)
        } finally {
            bitmap.recycle()
        }
        val out = image.pixels.copyOf()
        for (sy in 0 until stampHeight) {
            val y = originY + sy
            if (y < 0 || y >= image.height) continue
            for (sx in 0 until stampWidth) {
                val x = originX + sx
                if (x < 0 || x >= image.width) continue
                val source = stamp[sy * stampWidth + sx]
                val alpha = source ushr ALPHA_SHIFT
                if (alpha == 0) continue
                val index = y * image.width + x
                out[index] = blendOver(source, out[index], alpha)
            }
        }
        return PixelBuffer(image.width, image.height, out)
    }

    private fun paintFor(watermark: Watermark, image: PixelBuffer): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = (watermark.opacity * OPAQUE).toInt()
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = max(MIN_TEXT_PX, watermark.size * min(image.width, image.height))
        }
        // Long text shrinks to fit within the photo's width.
        val maxWidth = image.width * MAX_WIDTH_FRACTION
        val measured = paint.measureText(watermark.text)
        if (measured > maxWidth) paint.textSize = max(1f, paint.textSize * maxWidth / measured)
        paint.setShadowLayer(paint.textSize * SHADOW_RADIUS, 0f, paint.textSize * SHADOW_OFFSET, Color.argb(SHADOW_ALPHA, 0, 0, 0))
        return paint
    }

    private companion object {
        const val OPAQUE = 255
        const val ALPHA_SHIFT = 24
        const val MIN_TEXT_PX = 10f
        const val MAX_WIDTH_FRACTION = 0.9f
        const val MARGIN = 0.8f
        const val SHADOW_RADIUS = 0.12f
        const val SHADOW_OFFSET = 0.04f
        const val SHADOW_ALPHA = 140

        /** [source] over the opaque [destination], with [alpha] 1..255; the result stays opaque. */
        fun blendOver(source: Int, destination: Int, alpha: Int): Int {
            val inverse = OPAQUE - alpha
            fun channel(shift: Int) = (((source shr shift) and 0xFF) * alpha + ((destination shr shift) and 0xFF) * inverse + OPAQUE / 2) / OPAQUE
            return (OPAQUE shl ALPHA_SHIFT) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}
