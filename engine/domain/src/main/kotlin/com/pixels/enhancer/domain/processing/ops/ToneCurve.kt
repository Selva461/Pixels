package com.pixels.enhancer.domain.processing.ops

/**
 * Luma tone curve combining contrast, highlight recovery and shadow lift.
 *
 * Every weight function is zero at 0 and 1, so black and white points stay anchored: shadows
 * are lifted without turning blacks grey, and highlights are compressed without dulling white.
 */
class ToneCurve private constructor(private val table: FloatArray) {

    fun map(luma: Float): Float = table[(luma.coerceIn(0f, 1f) * LAST_INDEX + 0.5f).toInt()]

    companion object {
        private const val SIZE = 1024
        private const val LAST_INDEX = SIZE - 1

        /** x(1−x)⁴ peaks at x = 0.2 with value 0.08192; normalised so the amount is the peak lift. */
        private const val SHADOW_NORMALISATION = 1f / 0.08192f

        /** x⁴(1−x) peaks at x = 0.8 with value 0.08192. */
        private const val HIGHLIGHT_NORMALISATION = 1f / 0.08192f

        /** smoothstep(x) − x peaks at |0.0962|. */
        private const val CONTRAST_NORMALISATION = 1f / 0.0962f

        val IDENTITY = build(contrast = 0f, highlights = 0f, shadows = 0f)

        fun build(contrast: Float, highlights: Float, shadows: Float): ToneCurve {
            val table = FloatArray(SIZE)
            var previous = 0f
            for (index in 0 until SIZE) {
                val x = index / LAST_INDEX.toFloat()
                val raw = x + shadows * shadowWeight(x) + highlights * highlightWeight(x) + contrast * contrastWeight(x)
                // Running max keeps the curve monotonic so tonal order is never inverted.
                val value = raw.coerceIn(0f, 1f).coerceAtLeast(previous)
                table[index] = value
                previous = value
            }
            return ToneCurve(table)
        }

        fun shadowWeight(x: Float): Float {
            val inverse = 1f - x
            return x * inverse * inverse * inverse * inverse * SHADOW_NORMALISATION
        }

        fun highlightWeight(x: Float): Float = x * x * x * x * (1f - x) * HIGHLIGHT_NORMALISATION

        fun contrastWeight(x: Float): Float = (x * x * (3f - 2f * x) - x) * CONTRAST_NORMALISATION
    }
}
