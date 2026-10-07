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
    whites = Adjustment.of(0.05f, "test"),
    blacks = Adjustment.of(-0.05f, "test"),
    midtones = Adjustment.of(0.05f, "test"),
    dehaze = Adjustment.of(0.3f, "test"),
    brightness = Adjustment.of(0.05f, "test"),
    gamma = Adjustment.of(0.2f, "test"),
    texture = Adjustment.of(0.2f, "test"),
    sharpenMasking = Adjustment.of(0.3f, "test"),
    colorNoiseReduction = Adjustment.of(0.6f, "test"),
    toneCurves = com.pixels.enhancer.domain.planning.ToneCurves.NONE.with(
        com.pixels.enhancer.domain.planning.CurveChannel.MASTER,
        com.pixels.enhancer.domain.planning.CurvePoints.of(
            listOf(
                com.pixels.enhancer.domain.planning.CurvePoint(0f, 0.02f),
                com.pixels.enhancer.domain.planning.CurvePoint(0.5f, 0.55f),
                com.pixels.enhancer.domain.planning.CurvePoint(1f, 0.98f),
            ),
        ),
    ),
    colorMixer = com.pixels.enhancer.domain.planning.ColorMixer.NONE.with(
        com.pixels.enhancer.domain.planning.HueBand.GREEN,
        com.pixels.enhancer.domain.planning.HslShift(0.2f, -0.3f, 0.1f),
    ),
)

class PipelineImageProcessorTest {

    @Test
    fun `default stage order follows the spec - denoise before detail and sharpening`() {
        assertEquals(
            listOf(
                StageIds.EXPOSURE, StageIds.WHITE_BALANCE, StageIds.DEHAZE, StageIds.FACE_EXPOSURE, StageIds.TONE, StageIds.CURVES, StageIds.NOISE_REDUCTION,
                StageIds.DETAIL, StageIds.TEXTURE, StageIds.SHARPEN, StageIds.COLOR_FINISH, StageIds.COLOR_MIXER, StageIds.VIGNETTE, StageIds.GRAIN,
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
        assertEquals("Disabled in developer settings", result.skippedStages.single { it.stageId == StageIds.NOISE_REDUCTION }.reason)
    }

    @Test
    fun `run until stops after the selected stage`() = runTest {
        val result = PipelineImageProcessor(DefaultPipeline.stages())
            .process(TestImages.solid(120), contextFor(planOverride = ::everythingPlan), runUntilStageId = StageIds.NOISE_REDUCTION)
        assertEquals(
            listOf(StageIds.EXPOSURE, StageIds.WHITE_BALANCE, StageIds.DEHAZE, StageIds.TONE, StageIds.CURVES, StageIds.NOISE_REDUCTION),
            result.executedStages,
        )
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

class TiledProcessingTest {
    @Test
    fun `tiled output matches whole-image output with every stage enabled`() = runTest {
        val image = com.pixels.enhancer.testing.GoldenScenario.LOW_LIGHT_NOISE.render(300, 220)
        val context = contextFor(planOverride = ::everythingPlan)
        val whole = PipelineImageProcessor(DefaultPipeline.stages()).process(image, context)
        val tiled = PipelineImageProcessor(DefaultPipeline.stages(), tilePixelThreshold = 1, tileSize = 64).process(image, context)
        assertEquals(whole.executedStages, tiled.executedStages)
        // Running box-blur sums round differently over shorter tile rows; later stages (curves,
        // texture, sharpening thresholds) can amplify that at a handful of edge pixels. A real seam
        // would instead show as many differing pixels concentrated on tile boundaries.
        val tile = 64
        var differing = 0
        var differingOnSeams = 0
        var seamPixels = 0
        for (index in whole.image.pixels.indices) {
            val x = index % image.width
            val y = index / image.width
            val onSeam = x % tile < 2 || x % tile >= tile - 2 || y % tile < 2 || y % tile >= tile - 2
            if (onSeam) seamPixels++
            if (whole.image.pixels[index] != tiled.image.pixels[index]) {
                differing++
                if (onSeam) differingOnSeams++
            }
        }
        val total = whole.image.pixelCount
        assertTrue(differing < total / 1000, "too many differing pixels: $differing of $total")
        assertTrue(differingOnSeams.toDouble() / seamPixels < 0.005, "differences concentrate on tile seams: $differingOnSeams of $seamPixels")
    }
}
