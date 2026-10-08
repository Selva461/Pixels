package com.pixels.enhancer.processing

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.domain.analysis.NoiseEstimator
import com.pixels.enhancer.domain.analysis.SaturationEstimator
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.geometry.GeometryOps
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.model.SupportedFormats
import com.pixels.enhancer.domain.planning.Adjustment
import com.pixels.enhancer.domain.planning.ManualAdjustmentMerger
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.planning.QualityPreset
import com.pixels.enhancer.domain.processing.ops.ToneCurve
import com.pixels.enhancer.domain.processing.stages.NoiseReductionStage
import com.pixels.enhancer.domain.processing.stages.SharpenStage
import com.pixels.enhancer.domain.processing.stages.TextureStage
import com.pixels.enhancer.goodAnalysis
import com.pixels.enhancer.testing.Degradations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ExtraControlsTest {
    @Test
    fun `gamma 2 brightens midtones and keeps the end points`() {
        val curve = ToneCurve.build(0f, 0f, 0f, gammaLog2 = 1f)
        assertEquals(0.707f, curve.map(0.5f), 0.01f)
        assertEquals(0f, curve.map(0f), 0.001f)
        assertEquals(1f, curve.map(1f), 0.001f)
    }

    @Test
    fun `brightness is broader than midtones`() {
        val brightness = ToneCurve.build(0f, 0f, 0f, brightness = 0.1f)
        val midtones = ToneCurve.build(0f, 0f, 0f, midtones = 0.1f)
        assertEquals(brightness.map(0.5f), midtones.map(0.5f), 0.01f)
        assertTrue(brightness.map(0.2f) - 0.2f > midtones.map(0.2f) - 0.2f)
    }

    @Test
    fun `exposure and exposure compensation add up to the spec range`() {
        val plan = NaturalEnhancementPlanner().createPlan(goodAnalysis(), 0f, QualityPreset.NATURAL)
        val merged = ManualAdjustmentMerger.merge(plan, ManualAdjustments.of(ManualControl.EXPOSURE to 1f, ManualControl.EXPOSURE_COMPENSATION to -0.25f))
        assertEquals(2.5f, merged.exposure.amount, 1e-4f)
        assertEquals("+3.0 EV", ManualControl.EXPOSURE.format(1f))
        assertEquals("γ 0.50", ManualControl.GAMMA.format(-1f))
        assertEquals("-40", ManualControl.CONTRAST.format(-0.4f))
    }

    @Test
    fun `texture adds bounded fine contrast`() = runTest {
        val image = Degradations.blur(TestImages.checkerboard(cell = 4), radius = 1)
        val result = TextureStage().execute(image.copy(), contextFor(planOverride = { it.copy(texture = Adjustment.of(0.5f, "t")) }))
        assertTrue(TestImages.meanAbsoluteDifference(image, result) > 1.0)
        assertTrue(TestImages.meanAbsoluteDifference(image, result) < TextureStage.MAX_LUMA_CHANGE * 255)
    }

    @Test
    fun `colour noise reduction alone removes chroma speckle but keeps luma noise`() = runTest {
        val noisy = Degradations.noise(TestImages.solid(128, 128, 128), lumaSigma = 6f, chromaSigma = 8f)
        val context = contextFor(
            analysis = goodAnalysis(noiseScore = 0.3f),
            planOverride = { it.copy(colorNoiseReduction = Adjustment.of(1f, "t")) },
        )
        val result = NoiseReductionStage().execute(noisy.copy(), context)
        assertTrue(SaturationEstimator.compute(result).meanChroma < SaturationEstimator.compute(noisy).meanChroma * 0.6f)
        assertTrue(NoiseEstimator.estimateSigma(result) > NoiseEstimator.estimateSigma(noisy) * 0.7f, "luma grain should survive")
    }

    @Test
    fun `sharpen masking leaves fine texture alone`() = runTest {
        val texture = TestImages.withGaussianNoise(TestImages.solid(128, 96, 96), 3.0)
        val plan = { masking: Float -> contextFor(planOverride = { it.copy(sharpening = Adjustment.of(0.5f, "t"), sharpenMasking = Adjustment.of(masking, "t")) }) }
        val unmasked = TestImages.meanAbsoluteDifference(texture, SharpenStage().execute(texture.copy(), plan(0f)))
        val masked = TestImages.meanAbsoluteDifference(texture, SharpenStage().execute(texture.copy(), plan(1f)))
        assertTrue(masked < unmasked * 0.5, "masked $masked vs unmasked $unmasked")
    }

    @Test
    fun `vertical flip turns the image upside down`() {
        val ramp = TestImages.ramp(0, 255, 4, 3)
        val rotated = GeometryOps.rotateQuarterTurns(ramp, 1) // vertical gradient: dark at top
        val flipped = GeometryOps.apply(rotated, Geometry.NONE.flippedVertically())
        assertTrue(Luma.ofPixel(flipped.pixels[0]) > Luma.ofPixel(flipped.pixels.last()))
    }

    @Test
    fun `heic is accepted`() {
        assertTrue(SupportedFormats.isSupported("image/heic"))
        assertTrue(SupportedFormats.isSupported("image/HEIF"))
    }
}
