package com.pixels.enhancer.domain.processing.ops

import com.pixels.enhancer.domain.image.BoxBlur

/**
 * Self-guided filter (He et al.). Smooths where local variance is close to [epsilon] (noise) and
 * leaves regions with variance well above it (edges, texture) almost untouched. O(N) in the
 * radius thanks to box filters.
 */
object GuidedFilter {

    /** Scratch arrays, each the size of the plane, reusable across calls to bound allocations. */
    class Scratch(size: Int) {
        val mean = FloatArray(size)
        val meanOfSquares = FloatArray(size)
        val temp = FloatArray(size)
    }

    /** Replaces [plane] with `plane + mix * (filtered − plane)`. */
    fun smoothInPlace(plane: FloatArray, width: Int, height: Int, radius: Int, epsilon: Float, mix: Float, scratch: Scratch) {
        val mean = scratch.mean
        val coefficientA = scratch.meanOfSquares
        BoxBlur.blur(plane, mean, width, height, radius, scratch.temp)
        for (index in plane.indices) coefficientA[index] = plane[index] * plane[index]
        BoxBlur.blur(coefficientA, coefficientA, width, height, radius, scratch.temp)

        val coefficientB = mean
        for (index in plane.indices) {
            val localMean = mean[index]
            val variance = (coefficientA[index] - localMean * localMean).coerceAtLeast(0f)
            val a = variance / (variance + epsilon)
            coefficientA[index] = a
            coefficientB[index] = localMean - a * localMean
        }
        BoxBlur.blur(coefficientA, coefficientA, width, height, radius, scratch.temp)
        BoxBlur.blur(coefficientB, coefficientB, width, height, radius, scratch.temp)

        for (index in plane.indices) {
            val original = plane[index]
            val filtered = coefficientA[index] * original + coefficientB[index]
            plane[index] = original + mix * (filtered - original)
        }
    }
}
