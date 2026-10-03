package com.pixels.enhancer.usecase

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.domain.analysis.StatisticalImageAnalyzer
import com.pixels.enhancer.domain.debug.DebugReport
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.model.ImageSource
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.planning.QualityPreset
import com.pixels.enhancer.domain.processing.ImageProcessor
import com.pixels.enhancer.domain.processing.PipelineImageProcessor
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingListener
import com.pixels.enhancer.domain.processing.stages.DefaultPipeline
import com.pixels.enhancer.domain.repository.ImageRepository
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.usecase.EnhanceRequest
import com.pixels.enhancer.domain.usecase.RenderTarget
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.validation.ValidationMode
import com.pixels.enhancer.domain.validation.NaturalOutputValidator
import com.pixels.enhancer.domain.validation.OutputValidator
import com.pixels.enhancer.domain.validation.ValidationCheck
import com.pixels.enhancer.domain.validation.ValidationResult
import com.pixels.enhancer.testing.GoldenScenario
import com.pixels.enhancer.testing.InMemoryImageRepository
import com.pixels.enhancer.testing.RecordingImageSaver
import com.pixels.enhancer.testing.RecordingLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EnhanceImageUseCaseTest {
    private val logger = RecordingLogger()
    private val saver = RecordingImageSaver()

    private fun useCase(
        repository: ImageRepository = InMemoryImageRepository(mapOf("dark" to GoldenScenario.UNDEREXPOSED.render(320, 240))),
        processor: ImageProcessor = PipelineImageProcessor(DefaultPipeline.stages(), logger = logger),
        validator: OutputValidator = NaturalOutputValidator(),
    ) = EnhanceImageUseCase(
        imageRepository = repository,
        analyzer = StatisticalImageAnalyzer(),
        planner = NaturalEnhancementPlanner(),
        processor = processor,
        validator = validator,
        saver = saver,
        logger = logger,
        dispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `full flow enhances, logs every phase and saves under a new name`() = runTest {
        val useCase = useCase()
        val session = assertIs<OperationResult.Success<*>>(useCase.open("dark")).value as com.pixels.enhancer.domain.usecase.EnhancementSession
        val outcome = (useCase.enhance(session, EnhanceRequest(strength = 0.45f)) as OperationResult.Success).value
        val saved = (useCase.save(session, EnhanceRequest(strength = 0.45f, target = RenderTarget.PREVIEW)) as OperationResult.Success).value
        assertEquals(session.original.width, saver.saved.single().second.width, "save must render full resolution, not the preview")

        assertEquals("dark_enhanced.jpg", saved.displayName)
        assertTrue(outcome.validation.passed)
        assertTrue(outcome.processed.executedStages.isNotEmpty())
        assertEquals(
            listOf("SOURCE_OPENED", "ANALYSIS_COMPLETE", "SCENE_DETECTED", "PROCESS_START", "PLAN_CREATED"),
            logger.names().take(5),
        )
        assertTrue(logger.names().containsAll(listOf("STAGE_COMPLETE", "VALIDATION_COMPLETE", "PROCESS_COMPLETE", "EXPORT_COMPLETE")))
        assertTrue(logger.lines.all { !it.contains("dark.jpg") }, "file names must not be logged")
        val timingNames = outcome.timings.entries.map { it.name }
        assertTrue(timingNames.containsAll(listOf("Decode", "Analyze", "Plan", "Validation")))
    }

    @Test
    fun `slider changes reuse the session and never touch the original`() = runTest {
        val useCase = useCase()
        val session = (useCase.open("dark") as OperationResult.Success).value
        val snapshot = session.original.pixels.copyOf()
        val low = (useCase.enhance(session, EnhanceRequest(0.2f)) as OperationResult.Success).value
        val high = (useCase.enhance(session, EnhanceRequest(0.9f)) as OperationResult.Success).value
        assertTrue(snapshot.contentEquals(session.original.pixels))
        assertTrue(high.plan.exposure.amount > low.plan.exposure.amount)
        assertTrue(low.processingId != high.processingId)
    }

    @Test
    fun `preview renders a small image with the same plan as full resolution`() = runTest {
        val repository = InMemoryImageRepository(mapOf("big" to GoldenScenario.UNDEREXPOSED.render(2400, 1800)))
        val useCase = useCase(repository)
        val session = (useCase.open("big") as OperationResult.Success).value
        val preview = (useCase.enhance(session, EnhanceRequest(0.5f, target = RenderTarget.PREVIEW)) as OperationResult.Success).value
        val full = (useCase.enhance(session, EnhanceRequest(0.5f, target = RenderTarget.FULL)) as OperationResult.Success).value
        assertEquals(1280, preview.processed.image.width)
        assertEquals(2400, full.processed.image.width)
        assertEquals(full.plan.entries().map { it.second.amount }, preview.plan.entries().map { it.second.amount })
    }

    @Test
    fun `strong manual looks are allowed past the natural validation limits`() = runTest {
        val useCase = useCase()
        val session = (useCase.open("dark") as OperationResult.Success).value
        val manual = ManualAdjustments.of(ManualControl.EXPOSURE to 1f, ManualControl.SATURATION to 1f, ManualControl.CONTRAST to 1f)
        val outcome = (useCase.enhance(session, EnhanceRequest(0.5f, manual = manual)) as OperationResult.Success).value
        assertTrue(outcome.plan.exposure.reason.contains("manual"))
        assertTrue(outcome.plan.globalSaturation.enabled)
    }

    @Test
    fun `missing image maps to IMAGE_NOT_FOUND`() = runTest {
        assertEquals(ErrorCode.IMAGE_NOT_FOUND, (useCase().open("nope") as OperationResult.Failure).code)
    }

    @Test
    fun `unsupported type maps to IMAGE_UNSUPPORTED`() = runTest {
        val repository = InMemoryImageRepository(mapOf("gif" to TestImages.solid(10)), mimeType = "image/gif")
        assertEquals(ErrorCode.IMAGE_UNSUPPORTED, (useCase(repository).open("gif") as OperationResult.Failure).code)
    }

    @Test
    fun `absurd dimensions map to IMAGE_TOO_LARGE before decoding`() = runTest {
        val repository = object : ImageRepository {
            override suspend fun readSource(sourceId: String) = ImageSource(sourceId, null, "image/jpeg", 30_000, 20_000, 0, false)
            override suspend fun loadWorkingImage(source: ImageSource, maxLongEdge: Int): PixelBuffer = error("must not decode")
        }
        assertEquals(ErrorCode.IMAGE_TOO_LARGE, (useCase(repository).open("huge") as OperationResult.Failure).code)
    }

    @Test
    fun `case H - large image is processed at working resolution`() = runTest {
        val repository = InMemoryImageRepository(mapOf("big" to TestImages.solid(90, 4000, 3000)))
        val session = (useCase(repository).open("big") as OperationResult.Success).value
        assertEquals(QualityPreset.NATURAL.maxWorkingLongEdge, session.original.width)
    }

    @Test
    fun `out of memory during processing becomes a controlled failure`() = runTest {
        val exploding = object : ImageProcessor {
            override val stageIds = emptyList<String>()
            override suspend fun process(image: PixelBuffer, context: ProcessingContext, listener: ProcessingListener?, runUntilStageId: String?) =
                throw OutOfMemoryError("simulated")
        }
        val useCase = useCase(processor = exploding)
        val session = (useCase.open("dark") as OperationResult.Success).value
        assertEquals(ErrorCode.OUT_OF_MEMORY, (useCase.enhance(session, EnhanceRequest(0.5f)) as OperationResult.Failure).code)
        assertTrue(logger.lines.any { it.startsWith("PROCESS_FAILED") && it.contains("OUT_OF_MEMORY") })
    }

    @Test
    fun `failed validation is reported and nothing is saved`() = runTest {
        val rejecting = object : OutputValidator {
            override fun validate(original: PixelBuffer, enhanced: PixelBuffer, mode: ValidationMode) =
                ValidationResult(listOf(ValidationCheck("Not blank", passed = false, detail = "simulated")))
        }
        val useCase = useCase(validator = rejecting)
        val session = (useCase.open("dark") as OperationResult.Success).value
        val failure = useCase.enhance(session, EnhanceRequest(0.5f)) as OperationResult.Failure
        assertEquals(ErrorCode.VALIDATION_FAILED, failure.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, (useCase.save(session, EnhanceRequest(0.5f)) as OperationResult.Failure).code)
        assertTrue(saver.saved.isEmpty())
    }

    @Test
    fun `debug report contains every section`() = runTest {
        val useCase = useCase()
        val session = (useCase.open("dark") as OperationResult.Success).value
        val outcome = (useCase.enhance(session, EnhanceRequest(0.5f)) as OperationResult.Success).value
        val report = DebugReport.format(session, outcome)
        listOf("[Input]", "[Image Analysis]", "[Enhancement Plan]", "[Pipeline]", "[Stage Timings]", "[Output Validation]", "TOTAL")
            .forEach { assertTrue(report.contains(it), "missing $it") }
    }
}
