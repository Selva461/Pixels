package com.pixels.enhancer.domain.regions

import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.geometry.GeometryOps
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.PixelResampler
import kotlin.math.exp

/**
 * Where people are, from an on-device segmentation model: a probability (0..1) per cell over the
 * whole photo. It only tells Smart edit *where* the subject is; the edit itself is still made of
 * ordinary sliders on the photo's own pixels, and nothing is generated.
 */
class SubjectHint(val width: Int, val height: Int, val weights: FloatArray) {
    init {
        require(width > 0 && height > 0 && weights.size == width * height)
    }

    /** Share of the frame the model is fairly sure is a person. */
    val coverage: Float = weights.count { it > CONFIDENT }.toFloat() / weights.size

    /** True when the model found a person worth editing (not a stray speck). */
    val hasSubject: Boolean get() = coverage >= MIN_COVERAGE

    /**
     * The hint in the frame the user sees: resized to the photo's shape ([sourceWidth] ×
     * [sourceHeight]), then cropped, rotated and warped by [geometry] like the photo.
     */
    fun transformed(sourceWidth: Int, sourceHeight: Int, geometry: Geometry): SubjectHint {
        val (w, h) = PixelResampler.fitWithin(TRANSFORM_LONG_EDGE, sourceWidth, sourceHeight)
        val resized = RegionDetector.resize(weights, width, height, w, h)
        val grey = PixelBuffer(w, h, IntArray(w * h) { i -> (resized[i].coerceIn(0f, 1f) * 255f).toInt().let { v -> Argb.opaque(v, v, v) } })
        val shaped = GeometryOps.apply(grey, geometry)
        return SubjectHint(shaped.width, shaped.height, FloatArray(shaped.pixelCount) { i -> Argb.green(shaped.pixels[i]) / 255f })
    }

    fun fingerprint(): Long {
        var hash = width * 31L + height
        val step = maxOf(1, weights.size / FINGERPRINT_SAMPLES)
        var i = 0
        while (i < weights.size) {
            hash = hash * 1_000_003L + (weights[i] * 255f).toInt()
            i += step
        }
        return hash
    }

    companion object {
        /** DeepLab v3 input: a square RGB image of this side, squashed from the photo. */
        const val PASCAL_INPUT_SIZE = 257

        /** Classes of the PASCAL VOC label set used by DeepLab v3. */
        const val PASCAL_CLASSES = 21

        /** The model input for [image]: [PASCAL_INPUT_SIZE]² RGB values in −1..1, row by row. */
        fun pascalInput(image: PixelBuffer): FloatArray {
            val size = PASCAL_INPUT_SIZE
            // Average down first so the squash below samples a smooth image, not single pixels.
            val small = PixelResampler.downscaleToFit(image, size * 2)
            val out = FloatArray(size * size * 3)
            for (y in 0 until size) {
                val sy = ((y + 0.5f) * small.height / size).toInt().coerceIn(0, small.height - 1)
                for (x in 0 until size) {
                    val sx = ((x + 0.5f) * small.width / size).toInt().coerceIn(0, small.width - 1)
                    val c = small.pixels[sy * small.width + sx]
                    val o = (y * size + x) * 3
                    out[o] = Argb.red(c) / HALF_CHANNEL - 1f
                    out[o + 1] = Argb.green(c) / HALF_CHANNEL - 1f
                    out[o + 2] = Argb.blue(c) / HALF_CHANNEL - 1f
                }
            }
            return out
        }

        private const val HALF_CHANNEL = 127.5f
        private const val PASCAL_BICYCLE = 2
        private const val PASCAL_MOTORBIKE = 14
        private const val PASCAL_PERSON = 15

        /**
         * People from DeepLab v3 (PASCAL VOC) logits laid out [y][x][class]. Only "person" counts:
         * the model's other labels are unreliable on everyday photos (reeds as a horse, clouds as a
         * bus). A bicycle or motorbike joins in when someone is riding it.
         */
        fun fromPascalLogits(logits: FloatArray, width: Int, height: Int): SubjectHint {
            require(logits.size == width * height * PASCAL_CLASSES)
            val person = FloatArray(width * height)
            val ride = FloatArray(width * height)
            for (i in person.indices) {
                val base = i * PASCAL_CLASSES
                var top = logits[base]
                for (c in 1 until PASCAL_CLASSES) top = maxOf(top, logits[base + c])
                var sum = 0.0
                for (c in 0 until PASCAL_CLASSES) sum += exp((logits[base + c] - top).toDouble())
                fun p(c: Int) = (exp((logits[base + c] - top).toDouble()) / sum).toFloat()
                person[i] = p(PASCAL_PERSON)
                ride[i] = p(PASCAL_BICYCLE) + p(PASCAL_MOTORBIKE)
            }
            val someone = person.any { it > CONFIDENT }
            return SubjectHint(width, height, FloatArray(person.size) { i -> (person[i] + if (someone) ride[i] else 0f).coerceIn(0f, 1f) })
        }

        const val CONFIDENT = 0.5f
        const val MIN_COVERAGE = 0.002f
        private const val TRANSFORM_LONG_EDGE = 512
        private const val FINGERPRINT_SAMPLES = 2048
    }
}

/** Platform people segmentation (an on-device model); none by default, then rules alone find the subject. */
fun interface SubjectSegmenter {
    suspend fun segment(image: PixelBuffer): SubjectHint?

    companion object {
        val NONE = SubjectSegmenter { null }
    }
}
