package com.pixels.enhancer.domain.local

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.smoothstep
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Turns a [LocalAdjustment]'s shape and brush strokes into a per-pixel weight map. */
object MaskRaster {

    /** Weights 0..1 for a [width]×[height] image: shape, plus/minus brush strokes, then inversion. Range is applied by the renderer. */
    fun weights(adjustment: LocalAdjustment, width: Int, height: Int): FloatArray {
        val mask = FloatArray(width * height)
        val aspect = width.toFloat() / height
        when (val shape = adjustment.shape) {
            MaskShape.None -> Unit
            MaskShape.Full -> mask.fill(1f)
            else -> for (y in 0 until height) {
                val ny = (y + 0.5f) / height
                val row = y * width
                for (x in 0 until width) mask[row + x] = shape.weightAt((x + 0.5f) / width, ny, aspect).coerceIn(0f, 1f)
            }
        }
        if (adjustment.brush.isNotEmpty()) {
            val stroke = FloatArray(width * height)
            adjustment.brush.forEach { brushStroke ->
                stroke.fill(0f)
                val touched = paint(brushStroke, stroke, width, height) ?: return@forEach
                for (y in touched[1] until touched[3]) {
                    for (x in touched[0] until touched[2]) {
                        val i = y * width + x
                        val s = stroke[i]
                        if (s <= 0f) continue
                        mask[i] = if (brushStroke.erase) mask[i] * (1f - s) else max(mask[i], s)
                    }
                }
            }
        }
        if (adjustment.invert) for (i in mask.indices) mask[i] = 1f - mask[i]
        return mask
    }

    /**
     * Stamps soft discs along the stroke into [into] (max-combined, so overlapping stamps don't
     * build up). Returns the touched pixel box [left, top, right, bottom) or null if off-image.
     */
    private fun paint(stroke: BrushStroke, into: FloatArray, width: Int, height: Int): IntArray? {
        if (stroke.pointCount == 0) return null
        val radiusPx = max(1f, stroke.radius * width)
        val hardRadius = radiusPx * (1f - stroke.feather.coerceIn(0f, 1f))
        val spacing = max(1f, radiusPx * STAMP_SPACING)
        var left = width
        var top = height
        var right = 0
        var bottom = 0
        fun stamp(cx: Float, cy: Float) {
            val x0 = max(0, (cx - radiusPx).toInt())
            val x1 = min(width, ceil(cx + radiusPx).toInt() + 1)
            val y0 = max(0, (cy - radiusPx).toInt())
            val y1 = min(height, ceil(cy + radiusPx).toInt() + 1)
            if (x0 >= x1 || y0 >= y1) return
            left = min(left, x0); top = min(top, y0); right = max(right, x1); bottom = max(bottom, y1)
            for (y in y0 until y1) {
                val dy = y + 0.5f - cy
                for (x in x0 until x1) {
                    val dx = x + 0.5f - cx
                    val distance = sqrt(dx * dx + dy * dy)
                    if (distance > radiusPx) continue
                    val weight = stroke.flow * (1f - smoothstep(hardRadius, radiusPx, distance))
                    val i = y * width + x
                    if (weight > into[i]) into[i] = weight
                }
            }
        }
        var previousX = stroke.points[0] * width
        var previousY = stroke.points[1] * height
        stamp(previousX, previousY)
        for (p in 1 until stroke.pointCount) {
            val x = stroke.points[p * 2] * width
            val y = stroke.points[p * 2 + 1] * height
            val dx = x - previousX
            val dy = y - previousY
            val length = sqrt(dx * dx + dy * dy)
            val steps = max(1, ceil(length / spacing).toInt())
            for (s in 1..steps) {
                val t = s.toFloat() / steps
                stamp(previousX + dx * t, previousY + dy * t)
            }
            previousX = x
            previousY = y
        }
        return if (left < right && top < bottom) intArrayOf(left, top, right, bottom) else null
    }

    private const val STAMP_SPACING = 0.25f
}

/** Tone and colour selection for [RangeMask]s. */
object RangeMath {

    fun luminanceWeight(color: Int, low: Float, high: Float, smoothness: Float): Float {
        val luma = Luma.ofPixel(color)
        val soft = smoothness.coerceIn(MIN_SOFT, 1f)
        val lower = if (low <= 0f) 1f else smoothstep(low - soft, low, luma)
        val upper = if (high >= 1f) 1f else 1f - smoothstep(high, high + soft, luma)
        return lower * upper
    }

    /**
     * Similarity of [color] to the sample in a luma/chroma space: chroma differences count fully,
     * brightness differences half, so a sampled blue sky also selects its lighter and darker parts.
     */
    fun colorWeight(color: Int, red: Int, green: Int, blue: Int, tolerance: Float): Float {
        val (l1, cb1, cr1) = ycc(Argb.red(color), Argb.green(color), Argb.blue(color))
        val (l2, cb2, cr2) = ycc(red, green, blue)
        val dc = sqrt((cb1 - cb2) * (cb1 - cb2) + (cr1 - cr2) * (cr1 - cr2))
        val distance = dc + LUMA_WEIGHT * kotlin.math.abs(l1 - l2)
        val reach = BASE_REACH + tolerance.coerceIn(0f, 1f) * TOLERANCE_REACH
        return 1f - smoothstep(reach * INNER_FRACTION, reach, distance)
    }

    private fun ycc(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val y = Luma.RED_WEIGHT * rf + Luma.GREEN_WEIGHT * gf + Luma.BLUE_WEIGHT * bf
        return Triple(y, (bf - y) * CB_SCALE, (rf - y) * CR_SCALE)
    }

    private const val MIN_SOFT = 0.01f
    private const val LUMA_WEIGHT = 0.5f
    private const val BASE_REACH = 0.04f
    private const val TOLERANCE_REACH = 0.3f
    private const val INNER_FRACTION = 0.4f
    private const val CB_SCALE = 0.5389f
    private const val CR_SCALE = 0.6350f
}
