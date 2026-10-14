package com.pixels.enhancer.domain.color

import kotlin.math.abs
import kotlin.math.pow

/**
 * An RGB encoding: primaries (as an RGB → CIE XYZ matrix, D50-adapted like an ICC profile) plus a
 * transfer function per channel. [iccProfile] is the profile the colours came from, embedded again
 * when an image in this space is saved; null means the platform's own profile for [name] is used.
 *
 * Pixels' 8-bit working image is stored in its source's own space, so wide-gamut photos keep their
 * colours until export (see [ColorConversion]).
 */
class RgbColorSpace(
    val name: String,
    /** Row-major 3×3, linear RGB → XYZ (D50). */
    toXyzD50: DoubleArray,
    val transfer: TransferFunction,
    val iccProfile: ByteArray? = null,
) {
    init {
        require(toXyzD50.size == MATRIX_SIZE) { "Expected a 3x3 matrix" }
    }

    val toXyz: DoubleArray = toXyzD50.copyOf()
    val fromXyz: DoubleArray = Matrix3.invert(toXyz)

    /** 8-bit code value → linear light. */
    private val decodeTable = FloatArray(LEVELS) { transfer.toLinear(it / MAX_CODE).toFloat() }

    fun decode(code: Int): Float = decodeTable[code]

    /** Linear light (clamped to 0..1) → encoded 0..1. */
    fun encode(linear: Double): Double = transfer.fromLinear(linear.coerceIn(0.0, 1.0))

    /** True when an 8-bit image in this space means the same colours in [other] (no conversion needed). */
    fun sameEncodingAs(other: RgbColorSpace): Boolean =
        this === other || (
            toXyz.indices.all { abs(toXyz[it] - other.toXyz[it]) < MATRIX_TOLERANCE } &&
                (0 until LEVELS).all { abs(decodeTable[it] - other.decodeTable[it]) < CURVE_TOLERANCE }
            )

    val isSrgb: Boolean get() = sameEncodingAs(SRGB)

    override fun toString(): String = "RgbColorSpace($name)"

    companion object {
        private const val MATRIX_SIZE = 9
        private const val LEVELS = 256
        private const val MAX_CODE = 255.0

        /** ICC profiles store s15Fixed16 colorants: matrices from different writers differ by ~1e-4. */
        private const val MATRIX_TOLERANCE = 2e-3
        private const val CURVE_TOLERANCE = 2e-4f

        /** sRGB colorants adapted to D50, as standard sRGB ICC profiles write them. */
        val SRGB = RgbColorSpace(
            "sRGB",
            doubleArrayOf(
                0.436065674, 0.385147095, 0.143066406,
                0.222488403, 0.716873169, 0.060607910,
                0.013916016, 0.097076416, 0.714096069,
            ),
            TransferFunction.SRGB,
        )

        /** Display P3 (DCI-P3 primaries, D65 white, sRGB curve) adapted to D50, as Android and Apple write it. */
        val DISPLAY_P3 = RgbColorSpace(
            "Display P3",
            doubleArrayOf(
                0.515121460, 0.291976929, 0.157104492,
                0.241195679, 0.692245483, 0.066574097,
                -0.001052856, 0.041885376, 0.784072876,
            ),
            TransferFunction.SRGB,
        )
    }
}

/** An ICC tone curve: encoded 0..1 ↔ linear light 0..1. */
sealed interface TransferFunction {
    fun toLinear(encoded: Double): Double
    fun fromLinear(linear: Double): Double

    /**
     * ICC parametric curve, function type 4 — the others are special cases:
     * `y = (a·x + b)^g + e` for `x ≥ d`, else `y = c·x + f`.
     */
    data class Parametric(
        val g: Double,
        val a: Double = 1.0,
        val b: Double = 0.0,
        val c: Double = 0.0,
        val d: Double = 0.0,
        val e: Double = 0.0,
        val f: Double = 0.0,
    ) : TransferFunction {
        private val linearAtD = c * d + f

        override fun toLinear(encoded: Double): Double =
            if (encoded >= d) (a * encoded + b).coerceAtLeast(0.0).pow(g) + e else c * encoded + f

        override fun fromLinear(linear: Double): Double = when {
            linear >= linearAtD || c == 0.0 -> ((linear - e).coerceAtLeast(0.0).pow(1.0 / g) - b) / a
            else -> (linear - f) / c
        }.coerceIn(0.0, 1.0)
    }

    /** Sampled curve (ICC `curv` with a table), linearly interpolated; must be non-decreasing. */
    class Table(private val values: DoubleArray) : TransferFunction {
        init {
            require(values.size >= 2) { "A tone table needs at least two entries" }
        }

        override fun toLinear(encoded: Double): Double {
            val position = encoded.coerceIn(0.0, 1.0) * (values.size - 1)
            val index = position.toInt().coerceAtMost(values.size - 2)
            val t = position - index
            return values[index] + (values[index + 1] - values[index]) * t
        }

        override fun fromLinear(linear: Double): Double {
            if (linear <= values.first()) return 0.0
            if (linear >= values.last()) return 1.0
            var low = 0
            var high = values.size - 1
            while (high - low > 1) {
                val mid = (low + high) / 2
                if (values[mid] <= linear) low = mid else high = mid
            }
            val span = values[high] - values[low]
            val t = if (span > 0) (linear - values[low]) / span else 0.0
            return (low + t) / (values.size - 1)
        }

        override fun equals(other: Any?): Boolean = other is Table && values.contentEquals(other.values)

        override fun hashCode(): Int = values.contentHashCode()
    }

    companion object {
        val SRGB = Parametric(g = 2.4, a = 1 / 1.055, b = 0.055 / 1.055, c = 1 / 12.92, d = 0.04045)
    }
}

internal object Matrix3 {
    fun invert(m: DoubleArray): DoubleArray {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        require(abs(det) > 1e-12) { "Singular colour matrix" }
        val s = 1 / det
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * s, (m[2] * m[7] - m[1] * m[8]) * s, (m[1] * m[5] - m[2] * m[4]) * s,
            (m[5] * m[6] - m[3] * m[8]) * s, (m[0] * m[8] - m[2] * m[6]) * s, (m[2] * m[3] - m[0] * m[5]) * s,
            (m[3] * m[7] - m[4] * m[6]) * s, (m[1] * m[6] - m[0] * m[7]) * s, (m[0] * m[4] - m[1] * m[3]) * s,
        )
    }

    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
        val row = i / 3
        val col = i % 3
        a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
    }
}
