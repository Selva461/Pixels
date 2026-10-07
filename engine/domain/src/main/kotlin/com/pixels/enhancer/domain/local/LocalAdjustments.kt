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
 * One masked adjustment. Amounts are −1..1 slider values: exposure ±2 EV, contrast ±0.15 tone
 * displacement, saturation ×0..2, temperature as the global Temperature control.
 */
data class LocalAdjustment(
    val id: Int,
    val shape: MaskShape,
    val invert: Boolean = false,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
) {
    val isNeutral: Boolean get() = exposure == 0f && contrast == 0f && saturation == 0f && temperature == 0f

    fun weightAt(x: Float, y: Float, aspect: Float): Float {
        val weight = shape.weightAt(x, y, aspect).coerceIn(0f, 1f)
        return if (invert) 1f - weight else weight
    }

    fun clamped() = copy(
        exposure = exposure.coerceIn(-1f, 1f),
        contrast = contrast.coerceIn(-1f, 1f),
        saturation = saturation.coerceIn(-1f, 1f),
        temperature = temperature.coerceIn(-1f, 1f),
    )

    companion object {
        const val MAX_EXPOSURE_EV = 2f
        const val MAX_CONTRAST = 0.15f

        fun radialAt(id: Int, centerX: Float, centerY: Float, aspect: Float) =
            LocalAdjustment(id, MaskShape.Radial(centerX, centerY, RADIAL_DEFAULT_RADIUS, RADIAL_DEFAULT_RADIUS * aspect, RADIAL_DEFAULT_FEATHER))

        /** Default linear mask: the top third of the frame, like a graduated filter for skies. */
        fun linearTop(id: Int) = LocalAdjustment(id, MaskShape.Linear(0.5f, 0.1f, 0.5f, 0.45f))

        private const val RADIAL_DEFAULT_RADIUS = 0.25f
        private const val RADIAL_DEFAULT_FEATHER = 0.5f
    }
}

data class LocalAdjustments(val items: List<LocalAdjustment> = emptyList()) {
    val isNeutral: Boolean get() = items.all { it.isNeutral }

    fun with(item: LocalAdjustment) = LocalAdjustments(items.map { if (it.id == item.id) item.clamped() else it }.let { if (it.any { a -> a.id == item.id }) it else it + item.clamped() })

    fun without(id: Int) = LocalAdjustments(items.filterNot { it.id == id })

    fun nextId(): Int = (items.maxOfOrNull { it.id } ?: 0) + 1

    companion object {
        val NONE = LocalAdjustments()
        const val MAX_ITEMS = 8
    }
}
