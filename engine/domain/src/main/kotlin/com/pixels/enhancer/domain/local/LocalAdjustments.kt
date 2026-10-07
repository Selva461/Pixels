package com.pixels.enhancer.domain.local

import com.pixels.enhancer.domain.image.smoothstep
import kotlin.math.sqrt

/**
 * Where a local adjustment applies, in normalised coordinates (0..1) of the edited output — i.e.
 * the photo as the user sees it after crop and rotation. Masks only weight existing pixels; they
 * never create or replace content.
 */
sealed interface MaskShape {
    /** Weight at a normalised position, 0..1, before inversion. */
    fun weightAt(x: Float, y: Float, aspect: Float): Float

    /** No area; the mask is made of brush strokes only. */
    data object None : MaskShape {
        override fun weightAt(x: Float, y: Float, aspect: Float) = 0f
    }

    /** The whole photo; used with a luminance or colour range to select tones or colours everywhere. */
    data object Full : MaskShape {
        override fun weightAt(x: Float, y: Float, aspect: Float) = 1f
    }

    /** Full effect on the [startX],[startY] side, fading to none at [endX],[endY]. */
    data class Linear(val startX: Float, val startY: Float, val endX: Float, val endY: Float) : MaskShape {
        override fun weightAt(x: Float, y: Float, aspect: Float): Float {
            // Work in a square space so the gradient angle matches what the user drew.
            val dx = (endX - startX) * aspect
            val dy = endY - startY
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared < MIN_LENGTH_SQUARED) return 0f
            val t = (((x - startX) * aspect) * dx + (y - startY) * dy) / lengthSquared
            return 1f - smoothstep(0f, 1f, t)
        }
    }

    /**
     * Ellipse centred at [centerX],[centerY]; [radiusX]/[radiusY] are fractions of width/height.
     * [feather] is the fraction of the radius over which the effect fades out.
     */
    data class Radial(val centerX: Float, val centerY: Float, val radiusX: Float, val radiusY: Float, val feather: Float) : MaskShape {
        override fun weightAt(x: Float, y: Float, aspect: Float): Float {
            val nx = (x - centerX) / radiusX.coerceAtLeast(MIN_RADIUS)
            val ny = (y - centerY) / radiusY.coerceAtLeast(MIN_RADIUS)
            val distance = sqrt(nx * nx + ny * ny)
            val soft = feather.coerceIn(0f, 1f)
            return 1f - smoothstep(1f - soft, 1f + soft * EDGE_SOFTNESS, distance)
        }
    }

    private companion object {
        const val MIN_LENGTH_SQUARED = 1e-6f
        const val MIN_RADIUS = 0.01f
        const val EDGE_SOFTNESS = 0.25f
    }
}

/**
 * One brush stroke: a polyline of normalised points ([points] = x0, y0, x1, y1, …). [radius] is a
 * fraction of the image width, [feather] the soft fraction of it, [flow] the stroke's strength.
 * An [erase] stroke removes mask instead of adding it.
 */
data class BrushStroke(
    val points: List<Float>,
    val radius: Float = DEFAULT_RADIUS,
    val feather: Float = DEFAULT_FEATHER,
    val flow: Float = 1f,
    val erase: Boolean = false,
) {
    val pointCount: Int get() = points.size / 2

    fun clamped() = copy(
        points = points.take(MAX_POINTS * 2).let { if (it.size % 2 == 1) it.dropLast(1) else it }.map { it.coerceIn(-0.1f, 1.1f) },
        radius = radius.coerceIn(MIN_RADIUS, MAX_RADIUS),
        feather = feather.coerceIn(0f, 1f),
        flow = flow.coerceIn(0f, 1f),
    )

    companion object {
        const val DEFAULT_RADIUS = 0.04f
        const val DEFAULT_FEATHER = 0.5f
        const val MIN_RADIUS = 0.005f
        const val MAX_RADIUS = 0.25f
        const val MAX_POINTS = 2000
    }
}

/**
 * Narrows a mask to certain tones or colours of the photo, like Lightroom's range masks. Evaluated
 * on the pixel being adjusted, so the selection follows real edges in the picture.
 */
sealed interface RangeMask {
    fun weightFor(color: Int): Float

    /** Tones between [low] and [high] (luma 0..1), fading over [smoothness] on each side. */
    data class Luminance(val low: Float, val high: Float, val smoothness: Float = 0.1f) : RangeMask {
        override fun weightFor(color: Int): Float = RangeMath.luminanceWeight(color, low, high, smoothness)
    }

    /** Colours near the sampled [red],[green],[blue] (0..255), within [tolerance] 0..1. */
    data class Color(val red: Int, val green: Int, val blue: Int, val tolerance: Float = 0.3f) : RangeMask {
        override fun weightFor(color: Int): Float = RangeMath.colorWeight(color, red, green, blue, tolerance)
    }
}

/**
 * One masked adjustment. Amounts are −1..1 slider values: exposure ±2 EV, contrast ±0.15 tone
 * displacement, highlights/shadows like the global sliders, saturation ×0..2, temperature/tint as
 * the global White Balance, clarity and sharpness as local-contrast boosts.
 *
 * The mask is [shape] plus [brush] strokes (added or erased), optionally [invert]ed, then narrowed
 * by [range].
 */
data class LocalAdjustment(
    val id: Int,
    val shape: MaskShape,
    val invert: Boolean = false,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val tint: Float = 0f,
    val clarity: Float = 0f,
    val sharpness: Float = 0f,
    val brush: List<BrushStroke> = emptyList(),
    val range: RangeMask? = null,
    val name: String = "",
) {
    val isNeutral: Boolean
        get() = exposure == 0f && contrast == 0f && saturation == 0f && temperature == 0f && highlights == 0f &&
            shadows == 0f && tint == 0f && clarity == 0f && sharpness == 0f

    val needsNeighbourhood: Boolean get() = clarity != 0f || sharpness != 0f

    /** Shape weight only (no brush, inversion or range): used for quick previews of geometric masks. */
    fun weightAt(x: Float, y: Float, aspect: Float): Float {
        val weight = shape.weightAt(x, y, aspect).coerceIn(0f, 1f)
        return if (invert) 1f - weight else weight
    }

    fun clamped() = copy(
        exposure = exposure.coerceIn(-1f, 1f),
        contrast = contrast.coerceIn(-1f, 1f),
        saturation = saturation.coerceIn(-1f, 1f),
        temperature = temperature.coerceIn(-1f, 1f),
        highlights = highlights.coerceIn(-1f, 1f),
        shadows = shadows.coerceIn(-1f, 1f),
        tint = tint.coerceIn(-1f, 1f),
        clarity = clarity.coerceIn(-1f, 1f),
        sharpness = sharpness.coerceIn(-1f, 1f),
        brush = brush.take(MAX_STROKES).map { it.clamped() },
    )

    fun withStroke(stroke: BrushStroke) = copy(brush = (brush + stroke.clamped()).takeLast(MAX_STROKES))

    companion object {
        const val MAX_EXPOSURE_EV = 2f
        const val MAX_CONTRAST = 0.15f
        const val MAX_TONE = 0.2f
        const val MAX_STROKES = 200

        fun radialAt(id: Int, centerX: Float, centerY: Float, aspect: Float) =
            LocalAdjustment(id, MaskShape.Radial(centerX, centerY, RADIAL_DEFAULT_RADIUS, RADIAL_DEFAULT_RADIUS * aspect, RADIAL_DEFAULT_FEATHER))

        /** Default linear mask: the top third of the frame, like a graduated filter for skies. */
        fun linearTop(id: Int) = LocalAdjustment(id, MaskShape.Linear(0.5f, 0.1f, 0.5f, 0.45f))

        /** Empty brush mask: nothing is selected until the user paints. */
        fun brush(id: Int) = LocalAdjustment(id, MaskShape.None)

        /** Whole-photo mask narrowed to a tone range (default: the brightest quarter, e.g. skies). */
        fun luminanceRange(id: Int, low: Float = 0.7f, high: Float = 1f) = LocalAdjustment(id, MaskShape.Full, range = RangeMask.Luminance(low, high))

        /** Whole-photo mask narrowed to colours like the sampled one. */
        fun colorRange(id: Int, red: Int, green: Int, blue: Int) = LocalAdjustment(id, MaskShape.Full, range = RangeMask.Color(red, green, blue))

        private const val RADIAL_DEFAULT_RADIUS = 0.25f
        private const val RADIAL_DEFAULT_FEATHER = 0.5f
    }
}

data class LocalAdjustments(val items: List<LocalAdjustment> = emptyList()) {
    val isNeutral: Boolean get() = items.all { it.isNeutral }

    fun with(item: LocalAdjustment) = LocalAdjustments(items.map { if (it.id == item.id) item.clamped() else it }.let { if (it.any { a -> a.id == item.id }) it else it + item.clamped() })

    fun without(id: Int) = LocalAdjustments(items.filterNot { it.id == id })

    fun nextId(): Int = (items.maxOfOrNull { it.id } ?: 0) + 1

    fun byId(id: Int): LocalAdjustment? = items.firstOrNull { it.id == id }

    /** A copy of mask [id] placed right after it, with a new id; unchanged when full or missing. */
    fun duplicate(id: Int): LocalAdjustments {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0 || items.size >= MAX_ITEMS) return this
        val original = items[index]
        val copy = original.copy(id = nextId(), name = (original.name.ifEmpty { "Mask ${index + 1}" } + " copy").take(MAX_NAME_LENGTH))
        return LocalAdjustments(items.toMutableList().apply { add(index + 1, copy) })
    }

    fun renamed(id: Int, name: String): LocalAdjustments =
        LocalAdjustments(items.map { if (it.id == id) it.copy(name = name.filterNot(Char::isISOControl).trim().take(MAX_NAME_LENGTH)) else it })

    companion object {
        val NONE = LocalAdjustments()
        const val MAX_ITEMS = 12
        const val MAX_NAME_LENGTH = 40
    }
}
