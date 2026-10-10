package com.pixels.enhancer.analysis

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.domain.analysis.ColorCastEstimator
import com.pixels.enhancer.domain.analysis.LuminanceStatistics
import com.pixels.enhancer.domain.analysis.NoiseEstimator
import com.pixels.enhancer.domain.analysis.SaturationEstimator
import com.pixels.enhancer.domain.analysis.SharpnessEstimator
import com.pixels.enhancer.domain.analysis.StatisticalImageAnalyzer
import com.pixels.enhancer.testing.Degradations
import com.pixels.enhancer.testing.SyntheticScenes
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ExposureAnalysisTest {
    @Test
    fun `mean luma tracks brightness`() {
        assertEquals(40 / 255f, LuminanceStatistics.compute(TestImages.solid(40)).mean, 0.005f)
        assertEquals(220 / 255f, LuminanceStatistics.compute(TestImages.solid(220)).mean, 0.005f)
    }

    @Test
    fun `clipping fractions count near-white and near-black pixels`() {
        val stats = LuminanceStatistics.compute(TestImages.stepEdge(left = 0, right = 255))
        assertEquals(0.5f, stats.highlightClipping, 0.02f)
        assertEquals(0.5f, stats.shadowClipping, 0.02f)
    }
}

class ContrastAnalysisTest {
    @Test
    fun `full ramp has wide tonal spread and narrow ramp is flat`() {
        val wide = LuminanceStatistics.compute(TestImages.ramp(0, 255)).percentiles
        val narrow = LuminanceStatistics.compute(TestImages.ramp(110, 150)).percentiles
        assertTrue(wide.p95 - wide.p5 > 0.85f)
        assertTrue(narrow.p95 - narrow.p5 < 0.15f)
    }
}

class NoiseEstimationTest {
    @Test
    fun `estimates the sigma of gaussian noise on a flat image`() {
        val sigma8Bit = 8.0
        val estimate = NoiseEstimator.estimateSigma(TestImages.withGaussianNoise(TestImages.solid(128, 256, 256), sigma8Bit))
        val expected = (sigma8Bit / 255).toFloat()
        assertTrue(abs(estimate - expected) / expected < 0.25f, "estimate $estimate vs $expected")
    }

    @Test
    fun `clean flat images and smooth gradients have almost no noise`() {
        assertTrue(NoiseEstimator.estimateSigma(TestImages.solid(128)) < 0.001f)
        assertTrue(NoiseEstimator.estimateSigma(TestImages.ramp(20, 230)) < 0.003f)
    }

    @Test
    fun `texture is not mistaken for heavy noise`() {
        val clean = NoiseEstimator.estimateSigma(SyntheticScenes.natural())
        val noisy = NoiseEstimator.estimateSigma(Degradations.noise(SyntheticScenes.natural(), lumaSigma = 6f, chromaSigma = 2f))
        assertTrue(noisy > clean * 3, "clean=$clean noisy=$noisy")
    }
}

class SharpnessEstimationTest {
    @Test
    fun `hard edges score sharp and blurred edges score soft`() {
        val sharp = SharpnessEstimator.estimate(TestImages.checkerboard())
        val soft = SharpnessEstimator.estimate(Degradations.blur(TestImages.checkerboard(), radius = 3))
        assertTrue(sharp > 0.7f, "sharp=$sharp")
        assertTrue(soft < 0.45f, "soft=$soft")
    }

    @Test
    fun `flat image has nothing to sharpen`() {
        assertEquals(1f, SharpnessEstimator.estimate(TestImages.solid(100)))
    }
}

class SaturationAndCastTest {
    @Test
    fun `grey has no chroma and pure colours have a lot`() {
        assertEquals(0f, SaturationEstimator.compute(TestImages.solid(128)).meanChroma)
        assertTrue(SaturationEstimator.compute(TestImages.solidColor(230, 20, 20)).meanChroma > 0.8f)
    }

    @Test
    fun `tungsten cast scores clearly higher than the clean scene and points warm`() {
        val clean = ColorCastEstimator.estimate(SyntheticScenes.natural())
        val warm = ColorCastEstimator.estimate(Degradations.channelGains(SyntheticScenes.natural(), 1.15f, 1f, 0.62f))
        assertTrue(warm.score > clean.score + 0.3f, "clean=${clean.score} warm=${warm.score}")
        assertTrue(warm.balance.red > warm.balance.blue * 1.5f)
    }

    @Test
    fun `analyzer fills every field within range`() = runTest {
        val analysis = StatisticalImageAnalyzer().analyze(SyntheticScenes.natural())
        listOf(
            analysis.exposureScore, analysis.contrastScore, analysis.saturationScore, analysis.sharpnessScore,
            analysis.noiseScore, analysis.highlightClipping, analysis.shadowClipping, analysis.colorCastScore,
        ).forEach { assertTrue(it in 0f..1f, "score $it out of range") }
    }
}
