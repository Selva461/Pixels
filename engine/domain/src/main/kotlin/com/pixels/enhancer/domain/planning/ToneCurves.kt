package com.pixels.enhancer.domain.planning

data class CurvePoint(val x: Float, val y: Float)

/**
 * One tone curve as control points in 0..1. Always valid: built through [of], which sorts, clamps,
 * keeps the end points at x = 0 and x = 1, enforces a minimum horizontal gap and a point limit, so
 * the editor can never produce an unstable or unrenderable curve.
 */
class CurvePoints private constructor(val points: List<CurvePoint>) {

    val isIdentity: Boolean get() = points.all { kotlin.math.abs(it.x - it.y) < IDENTITY_TOLERANCE }

    /** Monotone cubic (Fritsch–Carlson) interpolation: smooth, and never overshoots between points. */
    fun valueAt(x: Float): Float {
        val clampedX = x.coerceIn(0f, 1f)
        val index = (1 until points.size).firstOrNull { points[it].x >= clampedX } ?: (points.size - 1)
        val left = points[index - 1]
        val right = points[index]
        val width = right.x - left.x
        val t = ((clampedX - left.x) / width).coerceIn(0f, 1f)
        val t2 = t * t
        val t3 = t2 * t
        val value = (2 * t3 - 3 * t2 + 1) * left.y + (t3 - 2 * t2 + t) * width * tangents[index - 1] +
            (-2 * t3 + 3 * t2) * right.y + (t3 - t2) * width * tangents[index]
        return value.coerceIn(0f, 1f)
    }

    /** 256-entry 8-bit lookup table. */
    fun lut(): IntArray = IntArray(LUT_SIZE) { code -> (valueAt(code / (LUT_SIZE - 1f)) * (LUT_SIZE - 1) + 0.5f).toInt() }

    private val tangents: FloatArray by lazy { fritschCarlsonTangents() }

    private fun fritschCarlsonTangents(): FloatArray {
        val n = points.size
        val slopes = FloatArray(n - 1) { (points[it + 1].y - points[it].y) / (points[it + 1].x - points[it].x) }
        val tangents = FloatArray(n)
        tangents[0] = slopes[0]
        tangents[n - 1] = slopes[n - 2]
        for (i in 1 until n - 1) {
            tangents[i] = if (slopes[i - 1] * slopes[i] <= 0f) 0f else (slopes[i - 1] + slopes[i]) / 2f
        }
        // Limit tangents so each segment stays monotone where its data is.
        for (i in 0 until n - 1) {
            if (slopes[i] == 0f) {
                tangents[i] = 0f
                tangents[i + 1] = 0f
                continue
            }
            val a = tangents[i] / slopes[i]
            val b = tangents[i + 1] / slopes[i]
            val magnitude = a * a + b * b
            if (magnitude > MONOTONE_LIMIT) {
                val scale = 3f / kotlin.math.sqrt(magnitude)
                tangents[i] = scale * a * slopes[i]
                tangents[i + 1] = scale * b * slopes[i]
            }
        }
        return tangents
    }

    fun with(points: List<CurvePoint>) = of(points)

    override fun equals(other: Any?) = other is CurvePoints && points == other.points

    override fun hashCode() = points.hashCode()

    override fun toString() = "CurvePoints($points)"

    companion object {
        const val MAX_POINTS = 10
        const val MIN_GAP = 0.04f
        private const val LUT_SIZE = 256
        private const val IDENTITY_TOLERANCE = 1e-4f

        /** Above this, Fritsch–Carlson rescales tangents (α² + β² ≤ 9). */
        private const val MONOTONE_LIMIT = 9f

        val IDENTITY = CurvePoints(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)))

        fun of(input: List<CurvePoint>): CurvePoints {
            val clamped = input.map { CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }.sortedBy { it.x }
            val start = clamped.firstOrNull { it.x <= MIN_GAP / 2 }?.let { CurvePoint(0f, it.y) } ?: CurvePoint(0f, 0f)
            val end = clamped.lastOrNull { it.x >= 1f - MIN_GAP / 2 }?.let { CurvePoint(1f, it.y) } ?: CurvePoint(1f, 1f)
            val middle = mutableListOf<CurvePoint>()
            for (point in clamped) {
                if (point.x <= MIN_GAP / 2 || point.x >= 1f - MIN_GAP / 2) continue
                val previousX = middle.lastOrNull()?.x ?: 0f
                if (point.x - previousX < MIN_GAP || 1f - point.x < MIN_GAP) continue
                middle += point
            }
            return CurvePoints(listOf(start) + middle.take(MAX_POINTS - 2) + end)
        }
    }
}

enum class CurveChannel(val label: String) { MASTER("RGB"), RED("Red"), GREEN("Green"), BLUE("Blue") }

/** The master curve applies to all channels first, then each channel's own curve. */
data class ToneCurves(val curves: Map<CurveChannel, CurvePoints> = emptyMap()) {
    operator fun get(channel: CurveChannel): CurvePoints = curves[channel] ?: CurvePoints.IDENTITY

    fun with(channel: CurveChannel, points: CurvePoints) =
        ToneCurves(if (points.isIdentity) curves - channel else curves + (channel to points))

    val isIdentity: Boolean get() = curves.values.all { it.isIdentity }

    companion object {
        val NONE = ToneCurves()
    }
}
