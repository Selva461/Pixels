package com.pixels.enhancer.golden

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.domain.analysis.ImageAnalysis
import com.pixels.enhancer.domain.analysis.SaturationEstimator
import com.pixels.enhancer.domain.analysis.StatisticalImageAnalyzer
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.planning.EnhancementStrength
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.processing.PipelineImageProcessor
import com.pixels.enhancer.domain.processing.stages.DefaultPipeline
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.usecase.EnhanceRequest
import com.pixels.enhancer.domain.usecase.EnhancementOutcome
import com.pixels.enhancer.domain.validation.NaturalOutputValidator
import com.pixels.enhancer.testing.GoldenScenario
import com.pixels.enhancer.testing.InMemoryImageRepository
import com.pixels.enhancer.testing.RecordingImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden scenarios (spec section 27/28): each degraded scene runs through the full use case at
 * the default strength, and the result is checked with measurable thresholds rather than exact
 * pixels, so algorithm tuning does not require re-baselining images.
 */
class GoldenScenarioTest {

    private class Run(val before: ImageAnalysis, val after: ImageAnalysis, val outcome: EnhancementOutcome, val input: PixelBuffer)

    private suspend fun run(scenario: GoldenScenario): Run {
        val input = scenario.render()
        val useCase = EnhanceImageUseCase(
            imageRepository = InMemoryImageRepository(mapOf(scenario.fileStem to input)),
            analyzer = StatisticalImageAnalyzer(),
            planner = NaturalEnhancementPlanner(),
            processor = PipelineImageProcessor(DefaultPipeline.stages()),
            validator = NaturalOutputValidator(),
            saver = RecordingImageSaver(),
            dispatcher = Dispatchers.Unconfined,
        )
        val session = (useCase.open(scenario.fileStem) as OperationResult.Success).value
        val result = useCase.enhance(session, EnhanceRequest(EnhancementStrength.DEFAULT))
        assertTrue(result is OperationResult.Success, "$scenario failed: $result")
        val outcome = result.value
        assertTrue(outcome.validation.passed)
        val after = StatisticalImageAnalyzer().analyze(outcome.processed.image)
        return Run(session.analysis, after, outcome, input)
    }

    @Test
    fun `already good photo is left almost untouched`() = runTest {
        val run = run(GoldenScenario.ALREADY_GOOD)
        assertTrue(TestImages.meanAbsoluteDifference(run.input, run.outcome.processed.image) < 2.0)
        assertFalse(run.outcome.plan.exposure.enabled)
        assertFalse(run.outcome.plan.whiteBalance.enabled)
    }

    @Test
    fun `underexposed photo is lifted moderately without colour shifts`() = runTest {
        val run = run(GoldenScenario.UNDEREXPOSED)
        assertTrue(run.after.exposureScore > run.before.exposureScore + 0.03f, "${run.before.exposureScore} -> ${run.after.exposureScore}")
        assertTrue(run.after.exposureScore < 0.58f)
        assertTrue(kotlin.math.abs(run.after.saturationScore - run.before.saturationScore) < 0.05f)
        assertFalse(run.outcome.plan.whiteBalance.enabled)
    }

    @Test
    fun `overexposed photo is darkened without new clipping`() = runTest {
        val run = run(GoldenScenario.OVEREXPOSED)
        assertTrue(run.after.exposureScore < run.before.exposureScore)
        assertTrue(run.after.highlightClipping <= run.before.highlightClipping + 0.005f)
    }

    @Test
    fun `noisy low light photo ends up less noisy despite the lift`() = runTest {
        val run = run(GoldenScenario.LOW_LIGHT_NOISE)
        assertTrue(run.outcome.plan.noiseReduction.enabled)
        assertTrue(run.after.noiseSigma < run.before.noiseSigma, "${run.before.noiseSigma} -> ${run.after.noiseSigma}")
        assertTrue(run.outcome.plan.sharpening.amount <= 0.1f)
    }

    @Test
    fun `colour cast is reduced and the grey card gets closer to neutral`() = runTest {
        val run = run(GoldenScenario.COLOR_CAST)
        assertTrue(run.after.colorCastScore < run.before.colorCastScore * 0.7f, "${run.before.colorCastScore} -> ${run.after.colorCastScore}")
        assertTrue(greyCardChroma(run.outcome.processed.image) < greyCardChroma(run.input) * 0.6f)
    }

    @Test
    fun `soft photo gets sharper`() = runTest {
        val run = run(GoldenScenario.SOFT_FOCUS)
        assertTrue(run.after.sharpnessScore > run.before.sharpnessScore + 0.02f, "${run.before.sharpnessScore} -> ${run.after.sharpnessScore}")
    }

    @Test
    fun `harsh contrast is not made harsher`() = runTest {
        val run = run(GoldenScenario.HIGH_CONTRAST)
        assertTrue(run.after.contrastScore <= run.before.contrastScore + 0.01f)
        assertTrue(run.after.highlightClipping <= run.before.highlightClipping)
    }

    @Test
    fun `hazy photo regains contrast`() = runTest {
        val run = run(GoldenScenario.HAZY)
        assertTrue(run.after.contrastScore > run.before.contrastScore + 0.03f, "${run.before.contrastScore} -> ${run.after.contrastScore}")
    }

    @Test
    fun `oversaturated photo is not made more saturated`() = runTest {
        val run = run(GoldenScenario.OVERSATURATED)
        assertTrue(run.after.saturationScore < run.before.saturationScore)
    }

    /** Mean chroma inside the synthetic scene's grey card. */
    private fun greyCardChroma(image: PixelBuffer): Float {
        var total = 0f
        var count = 0
        for (y in (image.height * 0.52f).toInt() until (image.height * 0.58f).toInt()) {
            for (x in (image.width * 0.67f).toInt() until (image.width * 0.71f).toInt()) {
                total += SaturationEstimator.chromaOf(image.pixels[y * image.width + x])
                count++
            }
        }
        return total / count
    }
}
