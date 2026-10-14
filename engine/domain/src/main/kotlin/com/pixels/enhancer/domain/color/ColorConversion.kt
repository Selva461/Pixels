package com.pixels.enhancer.domain.color

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

/** Float linear-light RGB, interleaved, in [space]'s primaries; values may leave 0..1 (wider colours). */
class LinearImage(val width: Int, val height: Int, val space: RgbColorSpace, val rgb: FloatArray, val alpha: IntArray) {
    init {
        require(rgb.size == width * height * 3 && alpha.size == width * height) { "Plane sizes do not match ${width}x$height" }
    }
}

/**
 * Colour-managed conversion between RGB spaces, done in float:
 * decode through the source curve → linear → XYZ → target primaries → soft gamut compression in
 * Oklab (constant lightness and hue, never a hard clip) → target curve → TPDF dither → 8 bits.
 */
object ColorConversion {
    /** Gamut knee for an sRGB copy: chroma above 85 % of the sRGB boundary is compressed smoothly. */
    const val SRGB_KNEE = 0.85
    const val WIDE_KNEE = 0.9

    private const val SEARCH_STEPS = 16
    private const val MAX_CHROMA = 0.5
    private const val NEUTRAL_CHROMA = 1e-6
    private const val GAMUT_EPSILON = 1e-6
    private const val MAX_CODE = 255.0

    fun toLinear(image: PixelBuffer, space: RgbColorSpace): LinearImage {
        val rgb = FloatArray(image.pixelCount * 3)
        val alpha = IntArray(image.pixelCount)
        image.pixels.forEachIndexed { i, color ->
            rgb[i * 3] = space.decode(Argb.red(color))
            rgb[i * 3 + 1] = space.decode(Argb.green(color))
            rgb[i * 3 + 2] = space.decode(Argb.blue(color))
            alpha[i] = Argb.alpha(color)
        }
        return LinearImage(image.width, image.height, space, rgb, alpha)
    }

    /**
     * [image] (8-bit, encoded in [from]) re-encoded in [to]. When both mean the same encoding the
     * pixels are returned untouched: nothing is requantised, so nothing is dithered.
     */
    fun convert(image: PixelBuffer, from: RgbColorSpace, to: RgbColorSpace, knee: Double = SRGB_KNEE, seed: Int = 0): PixelBuffer =
        if (from.sameEncodingAs(to)) image else encode(toLinear(image, from), to, knee, seed)

    /** Float linear image → 8-bit [target], gamut-compressed with [knee] and dithered (seeded, so exports are repeatable). */
    fun encode(image: LinearImage, target: RgbColorSpace, knee: Double = SRGB_KNEE, seed: Int = 0): PixelBuffer {
        val toSrgb = Matrix3.multiply(RgbColorSpace.SRGB.fromXyz, image.space.toXyz)
        val srgbToTarget = Matrix3.multiply(target.fromXyz, RgbColorSpace.SRGB.toXyz)
        val compressor = GamutCompressor(srgbToTarget, knee)
        val random = Random(seed)
        val linear = DoubleArray(3)
        val pixels = IntArray(image.width * image.height) { i ->
            val r = image.rgb[i * 3].toDouble()
            val g = image.rgb[i * 3 + 1].toDouble()
            val b = image.rgb[i * 3 + 2].toDouble()
            compressor.map(
                toSrgb[0] * r + toSrgb[1] * g + toSrgb[2] * b,
                toSrgb[3] * r + toSrgb[4] * g + toSrgb[5] * b,
                toSrgb[6] * r + toSrgb[7] * g + toSrgb[8] * b,
                linear,
            )
            Argb.pack(
                image.alpha[i],
                quantize(target.encode(linear[0]), random),
                quantize(target.encode(linear[1]), random),
                quantize(target.encode(linear[2]), random),
            )
        }
        return PixelBuffer(image.width, image.height, pixels)
    }

    /** TPDF dither of ±1 LSB: the sum of two uniform variables, so gradients never band. */
    private fun quantize(encoded: Double, random: Random): Int {
        val noise = random.nextDouble() - random.nextDouble()
        return floor(encoded * MAX_CODE + noise + 0.5).toInt().coerceIn(0, Argb.CHANNEL_MAX)
    }

    /**
     * Soft gamut compression in Oklab: lightness and hue stay, chroma above [knee] × the target's
     * boundary (at that lightness and hue) rolls off towards the boundary instead of clipping.
     */
    private class GamutCompressor(private val srgbToTarget: DoubleArray, private val knee: Double) {
        private val lab = DoubleArray(3)
        private val probe = DoubleArray(3)

        fun map(r: Double, g: Double, b: Double, out: DoubleArray) {
            Oklab.fromLinearSrgb(r, g, b, lab)
            val lightness = lab[0].coerceIn(0.0, 1.0)
            val chroma = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
            var a = 0.0
            var bb = 0.0
            if (chroma > NEUTRAL_CHROMA) {
                val cosHue = lab[1] / chroma
                val sinHue = lab[2] / chroma
                val limit = chromaLimit(lightness, cosHue, sinHue)
                val ratio = if (limit > 0) chroma / limit else 0.0
                val compressed = if (ratio > knee) {
                    val over = ratio - knee
                    knee + (1 - knee) * over / (over + (1 - knee))
                } else {
                    ratio
                }
                val newChroma = if (limit > 0) compressed * limit else 0.0
                a = newChroma * cosHue
                bb = newChroma * sinHue
            }
            toTarget(lightness, a, bb, out)
            for (c in 0 until 3) out[c] = out[c].coerceIn(0.0, 1.0)
        }

        /** Largest chroma at (lightness, hue) that stays inside the target's 0..1 cube (bisection). */
        private fun chromaLimit(lightness: Double, cosHue: Double, sinHue: Double): Double {
            var low = 0.0
            var high = MAX_CHROMA
            repeat(SEARCH_STEPS) {
                val mid = (low + high) / 2
                toTarget(lightness, mid * cosHue, mid * sinHue, probe)
                val inside = probe.all { it >= -GAMUT_EPSILON && it <= 1 + GAMUT_EPSILON }
                if (inside) low = mid else high = mid
            }
            return low
        }

        private fun toTarget(lightness: Double, a: Double, b: Double, out: DoubleArray) {
            Oklab.toLinearSrgb(lightness, a, b, out)
            val r = out[0]
            val g = out[1]
            val bl = out[2]
            out[0] = srgbToTarget[0] * r + srgbToTarget[1] * g + srgbToTarget[2] * bl
            out[1] = srgbToTarget[3] * r + srgbToTarget[4] * g + srgbToTarget[5] * bl
            out[2] = srgbToTarget[6] * r + srgbToTarget[7] * g + srgbToTarget[8] * bl
        }
    }
}

/** Oklab (Ottosson 2020) from/to linear sRGB; works on out-of-gamut (negative) values too. */
object Oklab {
    fun fromLinearSrgb(r: Double, g: Double, b: Double, out: DoubleArray) {
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        out[0] = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        out[1] = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        out[2] = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    }

    fun toLinearSrgb(lightness: Double, a: Double, b: Double, out: DoubleArray) {
        val l = lightness + 0.3963377774 * a + 0.2158037573 * b
        val m = lightness - 0.1055613458 * a - 0.0638541728 * b
        val s = lightness - 0.0894841775 * a - 1.2914855480 * b
        val l3 = l * l * l
        val m3 = m * m * m
        val s3 = s * s * s
        out[0] = 4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3
        out[1] = -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3
        out[2] = -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076127010 * s3
    }
}
