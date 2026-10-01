package com.pixels.enhancer.testing

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.BoxBlur
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.Srgb
import com.pixels.enhancer.domain.processing.ops.ChromaOps
import kotlin.random.Random

/** Controlled defects applied to a clean scene. Every function returns a new buffer. */
object Degradations {

    /** Linear-light gain, like a wrong exposure time. Gains > 1 clip. */
    fun exposure(image: PixelBuffer, linearGain: Float): PixelBuffer =
        channelGains(image, linearGain, linearGain, linearGain)

    /** Linear-light per-channel gains, like a wrong white balance (e.g. tungsten light). */
    fun channelGains(image: PixelBuffer, red: Float, green: Float, blue: Float): PixelBuffer = map(image) { color ->
        Argb.pack(
            Argb.alpha(color),
            Srgb.toSrgb8(Srgb.toLinear(Argb.red(color)) * red),
            Srgb.toSrgb8(Srgb.toLinear(Argb.green(color)) * green),
            Srgb.toSrgb8(Srgb.toLinear(Argb.blue(color)) * blue),
        )
    }

    /** Gaussian luma noise plus independent per-channel (chroma) noise, in 8-bit units. */
    fun noise(image: PixelBuffer, lumaSigma: Float, chromaSigma: Float, seed: Int = 11): PixelBuffer {
        val random = Random(seed)
        return map(image) { color ->
            val luma = random.nextGaussian() * lumaSigma
            Argb.pack(
                Argb.alpha(color),
                (Argb.red(color) + luma + random.nextGaussian() * chromaSigma).toInt(),
                (Argb.green(color) + luma + random.nextGaussian() * chromaSigma).toInt(),
                (Argb.blue(color) + luma + random.nextGaussian() * chromaSigma).toInt(),
            )
        }
    }

    /** Out-of-focus softness: repeated box blur on every channel. */
    fun blur(image: PixelBuffer, radius: Int, passes: Int = 2): PixelBuffer {
        val size = image.pixelCount
        val channels = Array(3) { FloatArray(size) }
        image.pixels.forEachIndexed { index, color ->
            channels[0][index] = Argb.red(color).toFloat()
            channels[1][index] = Argb.green(color).toFloat()
            channels[2][index] = Argb.blue(color).toFloat()
        }
        val scratch = FloatArray(size)
        channels.forEach { channel -> repeat(passes) { BoxBlur.blur(channel, channel, image.width, image.height, radius, scratch) } }
        return PixelBuffer(
            image.width,
            image.height,
            IntArray(size) { index ->
                Argb.pack(
                    Argb.alpha(image.pixels[index]),
                    (channels[0][index] + 0.5f).toInt(),
                    (channels[1][index] + 0.5f).toInt(),
                    (channels[2][index] + 0.5f).toInt(),
                )
            },
        )
    }

    fun saturation(image: PixelBuffer, factor: Float): PixelBuffer = map(image) { ChromaOps.scaleChroma(it, factor) }

    /** Lifts blacks and lowers whites towards a grey veil, like haze or a cheap lens flare. */
    fun haze(image: PixelBuffer, amount: Float, veil: Int = 150): PixelBuffer = map(image) { color ->
        Argb.pack(
            Argb.alpha(color),
            (Argb.red(color) + (veil - Argb.red(color)) * amount).toInt(),
            (Argb.green(color) + (veil - Argb.green(color)) * amount).toInt(),
            (Argb.blue(color) + (veil - Argb.blue(color)) * amount).toInt(),
        )
    }

    /** Steep per-channel contrast around mid-grey with clipping at both ends. */
    fun harshContrast(image: PixelBuffer, gain: Float): PixelBuffer = map(image) { color ->
        fun stretch(channel: Int) = ((channel - MID_GREY) * gain + MID_GREY).toInt()
        Argb.pack(Argb.alpha(color), stretch(Argb.red(color)), stretch(Argb.green(color)), stretch(Argb.blue(color)))
    }

    private const val MID_GREY = 128

    private inline fun map(image: PixelBuffer, transform: (Int) -> Int) =
        PixelBuffer(image.width, image.height, IntArray(image.pixelCount) { transform(image.pixels[it]) })
}

internal fun Random.nextGaussian(): Double {
    // Box–Muller; kotlin.random has no Gaussian.
    val u1 = nextDouble().coerceAtLeast(1e-12)
    val u2 = nextDouble()
    return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
}
