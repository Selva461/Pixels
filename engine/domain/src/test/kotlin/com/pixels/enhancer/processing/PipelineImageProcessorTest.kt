package com.pixels.enhancer.processing

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.planning.Adjustment
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.planning.QualityPreset
import com.pixels.enhancer.domain.processing.PipelineImageProcessor
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingListener
import com.pixels.enhancer.domain.processing.ProcessingStage
import com.pixels.enhancer.domain.processing.StageConfig
import com.pixels.enhancer.domain.processing.stages.DefaultPipeline
import com.pixels.enhancer.domain.processing.stages.StageIds
import com.pixels.enhancer.goodAnalysis
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

fun contextFor(
    analysis: com.pixels.enhancer.domain.analysis.ImageAnalysis = goodAnalysis(),
    stageConfigs: Map<String, StageConfig> = emptyMap(),
    planOverride: (com.pixels.enhancer.domain.planning.EnhancementPlan) -> com.pixels.enhancer.domain.planning.EnhancementPlan = { it },
): ProcessingContext {
    val plan = planOverride(NaturalEnhancementPlanner().createPlan(analysis, 0.5f, QualityPreset.NATURAL))
    return ProcessingContext(analysis, plan, QualityPreset.NATURAL, debugEnabled = true, processingId = "IMG-TEST-0001", stageConfigs = stageConfigs)
}

/** Enables every stage with a fixed amount so pipeline behaviour can be tested independent of the planner. */
fun everythingPlan(plan: com.pixels.enhancer.domain.planning.EnhancementPlan) = plan.copy(
    exposure = Adjustment.of(0.3f, "test"),
    contrast = Adjustment.of(0.05f, "test"),
    highlights = Adjustment.of(-0.05f, "test"),
    shadows = Adjustment.of(0.05f, "test"),
    whiteBalance = Adjustment.of(0.5f, "test"),
    saturation = Adjustment.of(0.05f, "test"),
    noiseReduction = Adjustment.of(0.5f, "test"),
    detail = Adjustment.of(0.1f, "test"),
    sharpening = Adjustment.of(0.2f, "test"),
    temperature = Adjustment.of(0.2f, "test"),
    tint = Adjustment.of(0.1f, "test"),
    globalSaturation = Adjustment.of(0.1f, "test"),
    vignette = Adjustment.of(-0.3f, "test"),
    grain = Adjustment.of(0.2f, "test"),
)

class PipelineImageProcessorTest {

    @Test
    fun `default stage order follows the spec - denoise before detail and sharpening`() {
        assertEquals(
            listOf(
                StageIds.EXPOSURE, StageIds.WHITE_BALANCE, StageIds.TONE, StageIds.NOISE_REDUCTION,
                StageIds.DETAIL, StageIds.SHARPEN, StageIds.COLOR_FINISH, StageIds.VIGNETTE, StageIds.GRAIN,
            ),
            DefaultPipeline.stages().map { it.id },
        )
    }

    @Test
    fun `original image is never modified`() = runTest {
        val original = TestImages.checkerboard(width = 64, height = 64)
        val snapshot = original.pixels.copyOf()
        PipelineImageProcessor(DefaultPipeline.stages()).process(original, contextFor(planOverride = ::everythingPlan))
        assertContentEquals(snapshot, original.pixels)
    }

    @Test
    fun `stages with nothing planned are skipped with a reason`() = runTest {
        val result = PipelineImageProcessor(DefaultPipeline.stages()).process(TestImages.solid(120), contextFor())
        assertTrue(result.executedStages.isEmpty())
        assertEquals(DefaultPipeline.stages().size, result.skippedStages.size)
        assertTrue(result.skippedStages.all { it.reason == "No correction planned" })
    }

    @Test
    fun `developer toggles disable individual stages`() = runTest {
        val context = contextFor(stageConfigs = mapOf(StageIds.NOISE_REDUCTION to StageConfig(enabled = false)), planOverride = ::everythingPlan)
        val result = PipelineImageProcessor(DefaultPipeline.stages()).process(TestImages.solid(120), context)
        assertTrue(StageIds.NOISE_REDUCTION !in result.executedStages)
        assertEquals("Disabled in developer settings", result.skippedStages.single().reason)
    }

    @Test
    fun `run until stops after the selected stage`() = runTest {
        val result = PipelineImageProcessor(DefaultPipeline.stages())
            .process(TestImages.solid(120), contextFor(planOverride = ::everythingPlan), runUntilStageId = StageIds.NOISE_REDUCTION)
        assertEquals(listOf(StageIds.EXPOSURE, StageIds.WHITE_BALANCE, StageIds.TONE, StageIds.NOISE_REDUCTION), result.executedStages)
    }

    @Test
    fun `listener sees every executed stage with timing`() = runTest {
        val started = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val listener = object : ProcessingListener {
            override fun onStageStarted(stage: ProcessingStage, index: Int, total: Int) {
                started += stage.id
            }

            override fun onStageCompleted(stage: ProcessingStage, durationMs: Long) {
                completed += stage.id
            }
        }
        val result = PipelineImageProcessor(DefaultPipeline.stages()).process(TestImages.solid(120), contextFor(planOverride = ::everythingPlan), listener)
        assertEquals(result.executedStages, started)
        assertEquals(started, completed)
        assertEquals(result.executedStages.size, result.stageTimings.entries.size)
    }

    @Test
    fun `a stage that changes dimensions is rejected`() = runTest {
        val broken = object : ProcessingStage {
            override val id = "broken"
            override val displayName = "Broken"
            override fun isEnabled(context: ProcessingContext) = true
            override suspend fun execute(input: PixelBuffer, context: ProcessingContext) = TestImages.solid(1, 2, 2)
        }
        assertFailsWith<IllegalStateException> { PipelineImageProcessor(listOf(broken)).process(TestImages.solid(1), contextFor()) }
    }

    @Test
    fun `new stages plug in without changing the processor`() = runTest {
        val invert = object : ProcessingStage {
            override val id = "invert"
            override val displayName = "Invert"
            override fun isEnabled(context: ProcessingContext) = true
            override suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer {
                for (i in input.pixels.indices) input.pixels[i] = input.pixels[i] xor 0x00FFFFFF
                return input
            }
        }
        val result = PipelineImageProcessor(DefaultPipeline.stages() + invert).process(TestImages.solid(0), contextFor())
        assertEquals(listOf("invert"), result.executedStages)
        assertEquals(-1, result.image.pixels[0])
    }
}
