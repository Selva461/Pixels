package com.pixels.enhancer.domain.export

import com.pixels.enhancer.domain.image.PixelBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A plain frame around the exported photo, [widthFraction] of the photo's long edge on every
 * side, in [color] (opaque ARGB). Sized exports keep their requested long edge: the photo is
 * scaled a little smaller so photo + frame fit.
 */
data class Border(val widthFraction: Float = 0f, val color: Int = WHITE) {
    val isNone: Boolean get() = widthFraction <= 0f

    fun clamped() = copy(widthFraction = widthFraction.coerceIn(0f, MAX_WIDTH), color = color or OPAQUE)

    companion object {
        val NONE = Border()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val THIN = 0.015f
        const val MEDIUM = 0.04f
        const val THICK = 0.08f
        const val MAX_WIDTH = 0.15f
        private const val OPAQUE = 0xFF000000.toInt()
    }
}

enum class WatermarkPosition { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER }

/**
 * Text drawn on the export (a name or copyright line). [size] is the text height as a fraction of
 * the photo's short edge; [opacity] 0..1. Drawing needs platform fonts, so the app supplies an
 * [ExportDecorator]; the engine only carries and sanitises the settings.
 */
data class Watermark(
    val text: String = "",
    val position: WatermarkPosition = WatermarkPosition.BOTTOM_RIGHT,
    val size: Float = DEFAULT_SIZE,
    val opacity: Float = DEFAULT_OPACITY,
) {
    val isNone: Boolean get() = sanitizedText().isEmpty()

    /** Control characters removed, whitespace trimmed, at most [MAX_LENGTH] characters. */
    fun sanitizedText(): String = text.filterNot { it.isISOControl() }.trim().take(MAX_LENGTH)

    fun clamped() = copy(text = sanitizedText(), size = size.coerceIn(MIN_SIZE, MAX_SIZE), opacity = opacity.coerceIn(MIN_OPACITY, 1f))

    companion object {
        val NONE = Watermark()
        const val MAX_LENGTH = 60
        const val DEFAULT_SIZE = 0.035f
        const val MIN_SIZE = 0.015f
        const val MAX_SIZE = 0.1f
        const val DEFAULT_OPACITY = 0.7f
        const val MIN_OPACITY = 0.1f
    }
}

/**
 * Platform hook that draws on the finished export (e.g. watermark text).
 *
 * Must not modify [decorate]'s input: for an unedited photo the export can be the open session's
 * cached image itself. Return a new buffer, or the input unchanged when there is nothing to draw.
 */
fun interface ExportDecorator {
    fun decorate(image: PixelBuffer, options: ExportOptions): PixelBuffer

    companion object {
        val NONE = ExportDecorator { image, _ -> image }
    }
}

object BorderOps {
    /** Border width in pixels for a photo whose long edge is [longEdge]. */
    fun widthFor(longEdge: Int, border: Border): Int =
        if (border.isNone) 0 else max(1, (longEdge * border.clamped().widthFraction).roundToInt())

    fun apply(image: PixelBuffer, border: Border): PixelBuffer {
        if (border.isNone) return image
        val clamped = border.clamped()
        val width = widthFor(max(image.width, image.height), clamped)
        val outWidth = image.width + 2 * width
        val outHeight = image.height + 2 * width
        val pixels = IntArray(outWidth * outHeight) { clamped.color }
        for (y in 0 until image.height) {
            System.arraycopy(image.pixels, y * image.width, pixels, (y + width) * outWidth + width, image.width)
        }
        return PixelBuffer(outWidth, outHeight, pixels)
    }

    /** Long edge the photo itself should have so photo + border = [finalLongEdge]. */
    fun contentLongEdge(finalLongEdge: Int, border: Border): Int =
        if (border.isNone) finalLongEdge else max(1, (finalLongEdge / (1f + 2f * border.clamped().widthFraction)).roundToInt())
}
