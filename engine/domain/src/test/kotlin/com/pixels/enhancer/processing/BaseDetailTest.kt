package com.pixels.enhancer.processing

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.domain.local.LocalAdjustmentRenderer
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.local.MaskShape
import com.pixels.enhancer.domain.processing.ops.BaseDetail
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Brief Task 4: tone moves on an edge-aware base, detail put back with gain ≥ 1. */
class BaseDetailTest {

    @Test
    fun `a big step stays in the base and fine texture goes to the detail`() {
        val width = 200
        val height = 60
        // Left half 2 stops darker than the right; a ±0.1 stop ripple on top.
        val s = FloatArray(width * height) { i ->
            val x = i % width
            (if (x < width / 2) -1f else 1f) + 0.1f * sin(x * 1.3f)
        }
        val detail = BaseDetail.detail(s, width, height, radius = 12)
        val row = 30 * width
        // Far from the edge the detail is the ripple; at the edge the 2-stop step is not detail.
        val ripple = (20 until 60).map { abs(detail[row + it] - 0.1f * sin(it * 1.3f)) }.average()
        assertTrue(ripple < 0.03, "ripple should be detail, error $ripple")
        val atEdge = (95 until 105).maxOf { abs(detail[row + it]) }
        assertTrue(atEdge < 0.5f, "the step should stay in the base, detail $atEdge")
    }

    @Test
    fun `lifting a textured patch keeps its texture`() {
        val patch = textured(base = 70, amplitude = 12)
        val lifted = LocalAdjustmentRenderer.apply(patch, LocalAdjustments(listOf(LocalAdjustment(1, MaskShape.Full, exposure = 0.3f, shadows = 0.5f))))
        assertTrue(meanLuma(lifted) > meanLuma(patch) + 0.08f)
        // Detail is a ratio in linear light: the log-luminance spread is unchanged (within 8-bit rounding).
        assertEquals(logSpread(patch), logSpread(lifted), 0.03)
    }

    @Test
    fun `highlights recovery does not flatten a bright patch`() {
        val patch = textured(base = 225, amplitude = 10)
        val out = LocalAdjustmentRenderer.apply(patch, LocalAdjustments(listOf(LocalAdjustment(1, MaskShape.Full, highlights = -1f))))
        assertTrue(meanLuma(out) < meanLuma(patch) - 0.02f)
        assertTrue(logSpread(out) >= logSpread(patch) * 0.97, "${logSpread(patch)} -> ${logSpread(out)}")
    }

    @Test
    fun `a lift on a warm white object does not clip its folds`() {
        // Red near the top, like a cream shirt in sun: a full lift would clip red and flatten it.
        val shirt = PixelBuffer(384, 384, IntArray(384 * 384) { i -> val d = (10 * sin(i % 384 * 0.7f)).toInt(); Argb.opaque(238 + d / 2, 212 + d, 182 + d) })
        val out = LocalAdjustmentRenderer.apply(shirt, LocalAdjustments(listOf(LocalAdjustment(1, MaskShape.Full, exposure = 0.5f))))
        assertTrue(logSpread(out) >= logSpread(shirt) * 0.95, "${logSpread(shirt)} -> ${logSpread(out)}")
    }

    @Test
    fun `a mask that does not brighten leaves bright tones where they were`() {
        val patch = textured(base = 240, amplitude = 8)
        val out = LocalAdjustmentRenderer.apply(patch, LocalAdjustments(listOf(LocalAdjustment(1, MaskShape.Full, temperature = 0.01f))))
        assertEquals(meanLuma(patch), meanLuma(out), 0.005f)
    }

    @Test
    fun `without clarity a flat noisy area keeps its noise level`() {
        val sky = TestImages.withGaussianNoise(TestImages.solidColor(150, 185, 230, 384, 384), 2.0)
        val out = LocalAdjustmentRenderer.apply(sky, LocalAdjustments(listOf(LocalAdjustment(1, MaskShape.Full, exposure = -0.2f, contrast = 0.4f, highlights = -0.5f))))
        assertTrue(logSpread(out) <= logSpread(sky) * 1.05, "${logSpread(sky)} -> ${logSpread(out)}")
    }

    @Test
    fun `clarity adds detail and never removes it`() {
        val patch = textured(base = 120, amplitude = 10)
        val out = LocalAdjustmentRenderer.apply(patch, LocalAdjustments(listOf(LocalAdjustment(1, MaskShape.Full, clarity = 1f))))
        assertTrue(logSpread(out) > logSpread(patch) * 1.3, "${logSpread(patch)} -> ${logSpread(out)}")
    }

    @Test
    fun `pixels outside every mask are left exactly as they were`() {
        val patch = textured(base = 120, amplitude = 10)
        val half = LocalAdjustment(1, MaskShape.Linear(0f, 0.5f, 0f, 0.4f), exposure = 0.5f, clarity = 0.5f)
        val out = LocalAdjustmentRenderer.apply(patch, LocalAdjustments(listOf(half)))
        for (i in 0 until patch.width * 30) assertEquals(patch.pixels[i], out.pixels[i])
        assertTrue(meanLuma(out) > meanLuma(patch))
    }

    private fun textured(base: Int, amplitude: Int, size: Int = 384) = PixelBuffer(
        size,
        size,
        IntArray(size * size) { i ->
            val v = base + (amplitude * sin(i % size * 0.9f) * sin(i / size * 0.7f)).toInt()
            Argb.opaque(v, v, v)
        },
    )

    private fun meanLuma(image: PixelBuffer) = image.pixels.map { Luma.ofPixel(it) }.average().toFloat()

    /** Standard deviation of log2 luminance over the patch's interior. */
    private fun logSpread(image: PixelBuffer): Double {
        val values = ArrayList<Double>()
        for (y in 8 until image.height - 8) {
            for (x in 8 until image.width - 8) {
                val c = image.pixels[y * image.width + x]
                val lum = 0.2126 * lin(Argb.red(c)) + 0.7152 * lin(Argb.green(c)) + 0.0722 * lin(Argb.blue(c))
                values += kotlin.math.ln(lum) / kotlin.math.ln(2.0)
            }
        }
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun lin(code: Int): Double = com.pixels.enhancer.domain.image.Srgb.toLinear(code).toDouble()
}
