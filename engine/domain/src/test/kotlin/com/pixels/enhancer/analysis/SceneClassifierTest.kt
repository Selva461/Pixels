package com.pixels.enhancer.analysis

import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.domain.analysis.SceneClassifier
import com.pixels.enhancer.domain.analysis.SceneType
import com.pixels.enhancer.domain.analysis.StatisticalImageAnalyzer
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.planning.QualityPreset
import com.pixels.enhancer.domain.processing.PipelineImageProcessor
import com.pixels.enhancer.domain.processing.stages.DefaultPipeline
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.usecase.EnhanceRequest
import com.pixels.enhancer.domain.validation.NaturalOutputValidator
import com.pixels.enhancer.testing.Degradations
import com.pixels.enhancer.testing.InMemoryImageRepository
import com.pixels.enhancer.testing.RecordingImageSaver
import com.pixels.enhancer.testing.SyntheticScenes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

class SceneClassifierTest {
    private suspend fun classify(image: PixelBuffer): Classified {
        val analysis = StatisticalImageAnalyzer().analyze(image)
        val estimate = SceneClassifier.classify(image, analysis)
        return Classified(estimate.scene, "scores=${estimate.scores} features=${SceneClassifier.measure(image)} sat=${analysis.saturationScore}")
    }

    private data class Classified(val scene: SceneType, val detail: String)

    private fun assertScene(expected: SceneType, actual: Classified) = assertEquals(expected, actual.scene, actual.detail)

    private fun paint(width: Int = 320, height: Int = 240, colorAt: (u: Float, v: Float, random: Random) -> Int): PixelBuffer {
        val random = Random(5)
        return PixelBuffer(width, height, IntArray(width * height) { colorAt((it % width) / width.toFloat(), (it / width) / height.toFloat(), random) })
    }

    @Test
    fun `sky over ground reads as landscape`() = runTest {
        assertScene(SceneType.LANDSCAPE, classify(SyntheticScenes.natural()))
    }

    @Test
    fun `very dark scene with a few lights reads as night`() = runTest {
        val night = paint { u, v, random ->
            if (random.nextFloat() < 0.01f) Argb.opaque(255, 220, 160) else Argb.opaque(18, 20, 30 + (v * 10).toInt())
        }
        assertScene(SceneType.NIGHT, classify(night))
    }

    @Test
    fun `white page with text lines reads as document`() = runTest {
        val page = paint { u, v, _ ->
            val textLine = ((v * 30).toInt() % 3 == 0) && u in 0.1f..0.9f && ((u * 80).toInt() % 4 != 0)
            if (textLine) Argb.opaque(30, 30, 35) else Argb.opaque(235, 233, 228)
        }
        assertScene(SceneType.DOCUMENT, classify(page))
    }

    @Test
    fun `large centred face reads as portrait`() = runTest {
        val portrait = paint { u, v, _ ->
            val dx = (u - 0.5f) / 0.3f
            val dy = (v - 0.5f) / 0.4f
            if (dx * dx + dy * dy < 1f) Argb.opaque(214, 168, 140) else Argb.opaque(70, 80, 95)
        }
        assertScene(SceneType.PORTRAIT, classify(portrait))
    }

    @Test
    fun `blue sky over sand reads as beach`() = runTest {
        val beach = paint { _, v, random ->
            val jitter = random.nextInt(8)
            when {
                v < 0.45f -> Argb.opaque(110 + jitter, 165 + jitter, 225)
                v < 0.55f -> Argb.opaque(40, 110 + jitter, 160)
                else -> Argb.opaque(222 + jitter, 196 + jitter, 150)
            }
        }
        assertScene(SceneType.BEACH, classify(beach))
    }

    @Test
    fun `featureless grey falls back to general`() = runTest {
        assertScene(SceneType.GENERAL, classify(paint { _, _, _ -> Argb.opaque(128, 128, 128) }))
    }

    @Test
    fun `scene presets differ and override changes the plan`() = runTest {
        assertNotEquals(QualityPreset.forScene(SceneType.NIGHT).limits, QualityPreset.forScene(SceneType.GENERAL).limits)
        val dark = Degradations.exposure(SyntheticScenes.natural(), 0.15f)
        val useCase = EnhanceImageUseCase(
            InMemoryImageRepository(mapOf("d" to dark)), StatisticalImageAnalyzer(), NaturalEnhancementPlanner(),
            PipelineImageProcessor(DefaultPipeline.stages()), NaturalOutputValidator(), RecordingImageSaver(),
            dispatcher = Dispatchers.Unconfined,
        )
        val session = (useCase.open("d") as OperationResult.Success).value
        val night = useCase.enhance(session, EnhanceRequest(0.5f, sceneOverride = SceneType.NIGHT))
        val general = useCase.enhance(session, EnhanceRequest(0.5f, sceneOverride = SceneType.GENERAL))
        assertTrue(night is OperationResult.Success, "night: $night")
        assertTrue(general is OperationResult.Success, "general: $general")
        val asNight = night.value
        val asGeneral = general.value
        assertTrue(asNight.plan.exposure.amount < asGeneral.plan.exposure.amount, "night keeps the scene darker")
    }
}
