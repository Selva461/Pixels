package com.pixels.enhancer.usecase

import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.domain.analysis.StatisticalImageAnalyzer
import com.pixels.enhancer.domain.export.ExportFormat
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.export.ExportSize
import com.pixels.enhancer.domain.export.MetadataPolicy
import com.pixels.enhancer.domain.geometry.CropRect
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.processing.PipelineImageProcessor
import com.pixels.enhancer.domain.processing.stages.DefaultPipeline
import com.pixels.enhancer.domain.repository.ImageSaver
import com.pixels.enhancer.domain.repository.SaveRequest
import com.pixels.enhancer.domain.repository.SavedImage
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.usecase.EnhanceRequest
import com.pixels.enhancer.domain.usecase.EnhancementSession
import com.pixels.enhancer.domain.validation.NaturalOutputValidator
import com.pixels.enhancer.testing.GoldenScenario
import com.pixels.enhancer.testing.InMemoryImageRepository
import com.pixels.enhancer.testing.RecordingImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportTest {
    private val saver = RecordingImageSaver()

    /** 3200×2400 source: larger than the 2560 working size, so full export must re-decode. */
    private val source = GoldenScenario.UNDEREXPOSED.render(3200, 2400)

    private fun useCase(imageSaver: ImageSaver = saver) = EnhanceImageUseCase(
        InMemoryImageRepository(mapOf("photo" to source)),
        StatisticalImageAnalyzer(),
        NaturalEnhancementPlanner(),
        // Small tile threshold so the export path exercises tiling in tests.
        PipelineImageProcessor(DefaultPipeline.stages(), tilePixelThreshold = 4_000_000),
        NaturalOutputValidator(),
        imageSaver,
        dispatcher = Dispatchers.Unconfined,
    )

    private suspend fun open(useCase: EnhanceImageUseCase): EnhancementSession =
        (useCase.open("photo") as OperationResult.Success).value

    @Test
    fun `full export keeps the source resolution, not the working size`() = runTest {
        val useCase = useCase()
        val session = open(useCase)
        assertEquals(2560, session.original.width)
        val result = useCase.export(session, EnhanceRequest(0.45f), ExportOptions(size = ExportSize.FULL))
        assertTrue(result is OperationResult.Success, "$result")
        assertEquals(3200 to 2400, result.value.width to result.value.height)
        assertEquals(3200, saver.saved.single().second.width)
    }

    @Test
    fun `sized export hits the requested long edge exactly`() = runTest {
        val useCase = useCase()
        val result = useCase.export(open(useCase), EnhanceRequest(0.45f), ExportOptions(size = ExportSize.MEDIUM)) as OperationResult.Success
        assertEquals(1600 to 1200, result.value.width to result.value.height)
    }

    @Test
    fun `cropped full export is cropped from the full-resolution source`() = runTest {
        val useCase = useCase()
        val geometry = Geometry(crop = CropRect.of(0f, 0f, 0.5f, 0.5f))
        val result = useCase.export(open(useCase), EnhanceRequest(0.45f, geometry = geometry), ExportOptions()) as OperationResult.Success
        assertEquals(1600 to 1200, result.value.width to result.value.height)
    }

    @Test
    fun `format, quality, metadata and name reach the saver`() = runTest {
        val useCase = useCase()
        useCase.export(open(useCase), EnhanceRequest(0.45f), ExportOptions(format = ExportFormat.PNG, size = ExportSize.SMALL, metadata = MetadataPolicy.REMOVE_ALL))
        val request = saver.saved.single().first
        assertEquals("image/png", request.mimeType)
        assertEquals("photo_enhanced.png", request.displayName)
        assertEquals(MetadataPolicy.REMOVE_ALL, request.metadata)
        assertEquals("photo", request.metadataSourceId)
    }

    @Test
    fun `write failure is reported and the session stays usable`() = runTest {
        val failing = object : ImageSaver {
            override suspend fun save(image: PixelBuffer, request: SaveRequest): SavedImage = throw IOException("No space left on device")
        }
        val useCase = useCase(failing)
        val session = open(useCase)
        val failure = useCase.export(session, EnhanceRequest(0.45f), ExportOptions(size = ExportSize.SMALL)) as OperationResult.Failure
        assertEquals(ErrorCode.SAVE_FAILED, failure.code)
        assertTrue(useCase.enhance(session, EnhanceRequest(0.45f)) is OperationResult.Success)
    }

    @Test
    fun `size estimate matches the real export`() = runTest {
        val useCase = useCase()
        val session = open(useCase)
        val request = EnhanceRequest(0.45f, geometry = Geometry(quarterTurns = 1, crop = CropRect.of(0.1f, 0f, 0.9f, 0.75f)))
        listOf(ExportSize.FULL, ExportSize.MEDIUM).forEach { size ->
            val options = ExportOptions(size = size)
            val estimate = useCase.estimateExportSize(session, request, options)
            val result = useCase.export(session, request, options) as OperationResult.Success
            val actual = result.value.width to result.value.height
            assertTrue(kotlin.math.abs(estimate.first - actual.first) <= 2 && kotlin.math.abs(estimate.second - actual.second) <= 2, "$size: $estimate vs $actual")
        }
    }

    @Test
    fun `quality outside the supported range is rejected`() {
        assertTrue(runCatching { ExportOptions(quality = 10) }.isFailure)
    }
}
