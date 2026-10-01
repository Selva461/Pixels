package com.pixels.enhancer

import com.pixels.enhancer.domain.analysis.ChannelBalance
import com.pixels.enhancer.domain.analysis.ImageAnalysis
import com.pixels.enhancer.domain.analysis.LuminancePercentiles
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

object TestImages {

    fun solid(value: Int, width: Int = 64, height: Int = 64) = PixelBuffer.filled(width, height, Argb.opaque(value, value, value))

    fun solidColor(red: Int, green: Int, blue: Int, width: Int = 64, height: Int = 64) =
        PixelBuffer.filled(width, height, Argb.opaque(red, green, blue))

    /** Horizontal grey ramp from [from] to [to]. */
    fun ramp(from: Int, to: Int, width: Int = 256, height: Int = 64) = PixelBuffer(
        width,
        height,
        IntArray(width * height) { index ->
            val x = index % width
            val value = from + (to - from) * x / (width - 1)
            Argb.opaque(value, value, value)
        },
    )

    fun checkerboard(cell: Int = 8, dark: Int = 40, light: Int = 210, width: Int = 256, height: Int = 256) = PixelBuffer(
        width,
        height,
        IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val value = if ((x / cell + y / cell) % 2 == 0) dark else light
            Argb.opaque(value, value, value)
        },
    )

    /** Left half [left], right half [right] — a single vertical step edge. */
    fun stepEdge(left: Int, right: Int, width: Int = 64, height: Int = 32) = PixelBuffer(
        width,
        height,
        IntArray(width * height) { index ->
            val value = if (index % width < width / 2) left else right
            Argb.opaque(value, value, value)
        },
    )

    fun withGaussianNoise(image: PixelBuffer, sigma8Bit: Double, seed: Int = 3): PixelBuffer {
        val random = Random(seed)
        return PixelBuffer(
            image.width,
            image.height,
            IntArray(image.pixelCount) { index ->
                val color = image.pixels[index]
                val noise = gaussian(random) * sigma8Bit
                Argb.opaque(
                    (Argb.red(color) + noise).toInt(),
                    (Argb.green(color) + noise).toInt(),
                    (Argb.blue(color) + noise).toInt(),
                )
            },
        )
    }

    fun meanAbsoluteDifference(a: PixelBuffer, b: PixelBuffer): Double {
        var total = 0L
        for (index in a.pixels.indices) {
            total += kotlin.math.abs(Argb.red(a.pixels[index]) - Argb.red(b.pixels[index])) +
                kotlin.math.abs(Argb.green(a.pixels[index]) - Argb.green(b.pixels[index])) +
                kotlin.math.abs(Argb.blue(a.pixels[index]) - Argb.blue(b.pixels[index]))
        }
        return total / (a.pixelCount * 3.0)
    }

    private fun gaussian(random: Random): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * random.nextDouble())
    }
}

/** An analysis describing a well-exposed, clean, neutral photo; tests override one defect at a time. */
fun goodAnalysis(
    exposure: Float = 0.47f,
    contrast: Float = 0.72f,
    saturation: Float = 0.22f,
    sharpness: Float = 0.68f,
    noiseScore: Float = 0.08f,
    highlightClipping: Float = 0.002f,
    shadowClipping: Float = 0.002f,
    castScore: Float = 0.2f,
    balance: ChannelBalance = ChannelBalance(1f, 1f, 1f, 0.6f),
    percentiles: LuminancePercentiles = LuminancePercentiles(p1 = 0.04f, p5 = 0.10f, p50 = 0.46f, p95 = 0.82f, p99 = 0.93f),
) = ImageAnalysis(
    width = 1000,
    height = 750,
    exposureScore = exposure,
    contrastScore = contrast,
    saturationScore = saturation,
    sharpnessScore = sharpness,
    noiseScore = noiseScore,
    noiseSigma = noiseScore * 0.04f,
    highlightClipping = highlightClipping,
    shadowClipping = shadowClipping,
    colorCastScore = castScore,
    neutralBalance = balance,
    luminance = percentiles,
)
