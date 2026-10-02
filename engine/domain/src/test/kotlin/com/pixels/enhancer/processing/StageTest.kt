package com.pixels.enhancer.processing

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.domain.analysis.ChannelBalance
import com.pixels.enhancer.domain.analysis.NoiseEstimator
import com.pixels.enhancer.domain.analysis.SaturationEstimator
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.planning.Adjustment
import com.pixels.enhancer.domain.planning.EnhancementPlan
import com.pixels.enhancer.domain.processing.ops.ToneCurve
import com.pixels.enhancer.domain.processing.stages.ColorFinishStage
import com.pixels.enhancer.domain.processing.stages.DetailStage
import com.pixels.enhancer.domain.processing.stages.ExposureStage
import com.pixels.enhancer.domain.processing.stages.NoiseReductionStage
import com.pixels.enhancer.domain.processing.stages.SharpenStage
import com.pixels.enhancer.domain.processing.stages.ToneStage
import com.pixels.enhancer.domain.processing.stages.WhiteBalanceStage
import com.pixels.enhancer.goodAnalysis
import com.pixels.enhancer.testing.Degradations
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun withPlan(transform: (EnhancementPlan) -> EnhancementPlan, analysis: com.pixels.enhancer.domain.analysis.ImageAnalysis = goodAnalysis()) =
    contextFor(analysis = analysis, planOverride = transform)

private fun lumaAt(image: PixelBuffer, x: Int, y: Int) = Luma.ofPixel(image.pixels[y * image.width + x])

class ExposureStageTest {
    @Test
    fun `lift brightens midtones but keeps black and white anchored`() {
        val lut = ExposureStage.buildLut(0.5f)
        assertEquals(0, lut[0])
        assertEquals(255, lut[255])
        assertTrue(lut[100] > 110, "midtone ${lut[100]}")
        (1..255).forEach { assertTrue(lut[it] >= lut[it - 1], "LUT not monotonic at $it") }
    }

    @Test
    fun `negative exposure darkens`() = runTest {
        val result = ExposureStage().execute(TestImages.solid(150), withPlan({ it.copy(exposure = Adjustment.of(-0.4f, "t")) }))
        assertTrue(Argb.red(result.pixels[0]) < 140)
    }
}

class ToneCurveTest {
    @Test
    fun `identity curve changes nothing`() {
        (0..10).map { it / 10f }.forEach { assertEquals(it, ToneCurve.IDENTITY.map(it), 0.001f) }
    }

    @Test
    fun `curve stays anchored and monotonic even with extreme amounts`() {
        val curve = ToneCurve.build(contrast = 0.5f, highlights = -0.5f, shadows = 0.5f)
        assertEquals(0f, curve.map(0f), 0.001f)
        assertEquals(1f, curve.map(1f), 0.001f)
        var previous = -1f
        (0..1000).forEach { step ->
            val value = curve.map(step / 1000f)
            assertTrue(value >= previous)
            previous = value
        }
    }

    @Test
    fun `shadow lift raises dark tones more than bright ones`() {
        val curve = ToneCurve.build(contrast = 0f, highlights = 0f, shadows = 0.1f)
        assertTrue(curve.map(0.2f) - 0.2f > curve.map(0.8f) - 0.8f)
    }

    @Test
    fun `tone stage keeps grey pixels grey`() = runTest {
        val result = ToneStage().execute(TestImages.solid(60), withPlan({ it.copy(shadows = Adjustment.of(0.1f, "t")) }))
        val color = result.pixels[0]
        assertEquals(Argb.red(color), Argb.green(color))
        assertEquals(Argb.green(color), Argb.blue(color))
        assertTrue(Argb.red(color) > 60)
    }
}

class WhiteBalanceStageTest {
    private val warmBalance = ChannelBalance(1.3f, 1.0f, 0.7f, 0.5f)

    @Test
    fun `warm cast on grey is pulled toward neutral`() = runTest {
        val warmGrey = TestImages.solidColor(140, 120, 90)
        val context = withPlan({ it.copy(whiteBalance = Adjustment.of(0.8f, "t")) }, goodAnalysis(castScore = 0.9f, balance = warmBalance))
        val before = SaturationEstimator.chromaOf(warmGrey.pixels[0])
        val after = SaturationEstimator.chromaOf(WhiteBalanceStage().execute(warmGrey.copy(), context).pixels[0])
        assertTrue(after < before * 0.6f, "chroma $before -> $after")
    }

    @Test
    fun `clipped white is not tinted`() = runTest {
        val context = withPlan({ it.copy(whiteBalance = Adjustment.of(1f, "t")) }, goodAnalysis(castScore = 0.9f, balance = warmBalance))
        assertEquals(Argb.opaque(255, 255, 255), WhiteBalanceStage().execute(TestImages.solid(255), context).pixels[0])
    }
}

class NoiseReductionStageTest {
    @Test
    fun `reduces noise on flat areas`() = runTest {
        val noisy = TestImages.withGaussianNoise(TestImages.solid(120, 128, 128), 8.0)
        val sigma = NoiseEstimator.estimateSigma(noisy)
        val context = withPlan({ it.copy(noiseReduction = Adjustment.of(0.8f, "t")) }, goodAnalysis(noiseScore = sigma / 0.04f))
        val after = NoiseEstimator.estimateSigma(NoiseReductionStage().execute(noisy.copy(), context))
        assertTrue(after < sigma * 0.7f, "sigma $sigma -> $after")
    }

    @Test
    fun `preserves strong edges`() = runTest {
        val edge = TestImages.withGaussianNoise(TestImages.stepEdge(50, 200), 4.0)
        val context = withPlan({ it.copy(noiseReduction = Adjustment.of(1f, "t")) }, goodAnalysis(noiseScore = 0.4f))
        val result = NoiseReductionStage().execute(edge.copy(), context)
        val step = lumaAt(result, 33, 16) - lumaAt(result, 30, 16)
        assertTrue(step > 0.5f, "edge step $step")
    }
}

class SharpenStageTest {
    @Test
    fun `steepens a soft edge without large halos`() = runTest {
        val soft = Degradations.blur(TestImages.stepEdge(60, 190), radius = 1)
        val context = withPlan({ it.copy(sharpening = Adjustment.of(0.4f, "t")) })
        val result = SharpenStage().execute(soft.copy(), context)
        val before = lumaAt(soft, 32, 16) - lumaAt(soft, 31, 16)
        val after = lumaAt(result, 32, 16) - lumaAt(result, 31, 16)
        assertTrue(after > before, "edge $before -> $after")
        val maxLuma = result.pixels.maxOf { Luma.ofPixel(it) }
        assertTrue(maxLuma <= 190 / 255f + SharpenStage.OVERSHOOT + 0.01f, "overshoot $maxLuma")
    }

    @Test
    fun `leaves flat areas and smooth gradients alone`() = runTest {
        val ramp = TestImages.ramp(40, 200)
        val result = SharpenStage().execute(ramp.copy(), withPlan({ it.copy(sharpening = Adjustment.of(0.5f, "t")) }))
        assertTrue(TestImages.meanAbsoluteDifference(ramp, result) < 0.5)
    }
}

class DetailStageTest {
    @Test
    fun `adds bounded local contrast`() = runTest {
        val image = Degradations.blur(TestImages.checkerboard(cell = 32), radius = 6)
        val result = DetailStage().execute(image.copy(), withPlan({ it.copy(detail = Adjustment.of(0.15f, "t")) }))
        val before = lumaAt(image, 40, 40) - lumaAt(image, 20, 40)
        val after = lumaAt(result, 40, 40) - lumaAt(result, 20, 40)
        assertTrue(abs(after) > abs(before), "local contrast $before -> $after")
        assertTrue(TestImages.meanAbsoluteDifference(image, result) < DetailStage.MAX_LUMA_CHANGE * 255)
    }
}

class ColorFinishStageTest {
    @Test
    fun `boost favours muted colours over saturated ones`() = runTest {
        val context = withPlan({ it.copy(saturation = Adjustment.of(0.1f, "t")) })
        val muted = TestImages.solidColor(120, 140, 120)
        val vivid = TestImages.solidColor(40, 200, 40)
        val mutedGain = SaturationEstimator.chromaOf(ColorFinishStage().execute(muted.copy(), context).pixels[0]) / SaturationEstimator.chromaOf(muted.pixels[0])
        val vividGain = SaturationEstimator.chromaOf(ColorFinishStage().execute(vivid.copy(), context).pixels[0]) / SaturationEstimator.chromaOf(vivid.pixels[0])
        assertTrue(mutedGain > vividGain, "muted $mutedGain vivid $vividGain")
    }

    @Test
    fun `skin is boosted less than other muted colours`() = runTest {
        val context = withPlan({ it.copy(saturation = Adjustment.of(0.1f, "t")) })
        val skin = TestImages.solidColor(210, 160, 130)
        val other = TestImages.solidColor(130, 160, 210)
        suspend fun gain(image: PixelBuffer) =
            SaturationEstimator.chromaOf(ColorFinishStage().execute(image.copy(), context).pixels[0]) / SaturationEstimator.chromaOf(image.pixels[0])
        assertTrue(gain(skin) < gain(other))
    }

    @Test
    fun `reduction lowers chroma`() = runTest {
        val vivid = TestImages.solidColor(220, 40, 40)
        val result = ColorFinishStage().execute(vivid.copy(), withPlan({ it.copy(saturation = Adjustment.of(-0.15f, "t")) }))
        assertTrue(SaturationEstimator.chromaOf(result.pixels[0]) < SaturationEstimator.chromaOf(vivid.pixels[0]))
    }
}

class CreativeStageTest {
    @Test
    fun `positive temperature warms grey`() = runTest {
        val context = withPlan({ it.copy(temperature = Adjustment.of(0.8f, "t")) })
        val color = WhiteBalanceStage().execute(TestImages.solid(128), context).pixels[0]
        assertTrue(Argb.red(color) > Argb.blue(color) + 10, "r=${Argb.red(color)} b=${Argb.blue(color)}")
    }

    @Test
    fun `saturation minus one makes the image monochrome`() = runTest {
        val context = withPlan({ it.copy(globalSaturation = Adjustment.of(-1f, "t")) })
        val color = ColorFinishStage().execute(TestImages.solidColor(200, 60, 40), context).pixels[0]
        assertTrue(SaturationEstimator.chromaOf(color) < 0.01f)
    }

    @Test
    fun `negative vignette darkens corners but not the centre`() = runTest {
        val image = TestImages.solid(150, 101, 101)
        val result = com.pixels.enhancer.domain.processing.stages.VignetteStage()
            .execute(image.copy(), withPlan({ it.copy(vignette = Adjustment.of(-1f, "t")) }))
        assertEquals(150, Argb.red(result.pixels[50 * 101 + 50]))
        assertTrue(Argb.red(result.pixels[0]) < 110)
    }

    @Test
    fun `grain adds deterministic texture to midtones only`() = runTest {
        val context = withPlan({ it.copy(grain = Adjustment.of(1f, "t")) })
        val stage = com.pixels.enhancer.domain.processing.stages.GrainStage()
        val first = stage.execute(TestImages.solid(128), context)
        val second = stage.execute(TestImages.solid(128), context)
        assertTrue(first.pixels.contentEquals(second.pixels))
        assertTrue(NoiseEstimator.estimateSigma(first) > 0.01f)
        assertEquals(Argb.opaque(0, 0, 0), stage.execute(TestImages.solid(0), context).pixels[0])
    }
}
