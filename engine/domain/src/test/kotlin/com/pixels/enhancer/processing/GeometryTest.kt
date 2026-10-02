package com.pixels.enhancer.processing

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.domain.analysis.StatisticalImageAnalyzer
import com.pixels.enhancer.domain.geometry.CropRect
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.geometry.GeometryOps
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.processing.PipelineImageProcessor
import com.pixels.enhancer.domain.processing.stages.DefaultPipeline
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.usecase.EnhanceRequest
import com.pixels.enhancer.domain.validation.NaturalOutputValidator
import com.pixels.enhancer.testing.GoldenScenario
import com.pixels.enhancer.testing.InMemoryImageRepository
import com.pixels.enhancer.testing.RecordingImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GeometryTest {
    /** 3×2 image whose pixels encode their own position, so remapping is easy to check. */
    private val labelled = PixelBuffer(3, 2, IntArray(6) { Argb.opaque(it * 10, 0, 0) })

    private fun redAt(image: PixelBuffer, x: Int, y: Int) = Argb.red(image.pixels[y * image.width + x])

    @Test
    fun `identity geometry returns the input`() {
        assertSame(labelled, GeometryOps.apply(labelled, Geometry.NONE))
    }

    @Test
    fun `quarter turn clockwise moves the bottom-left pixel to the top-left`() {
        val rotated = GeometryOps.rotateQuarterTurns(labelled, 1)
        assertEquals(2 to 3, rotated.width to rotated.height)
        assertEquals(30, redAt(rotated, 0, 0)) // was (0,1)
        assertEquals(0, redAt(rotated, 1, 0)) // was (0,0)
    }

    @Test
    fun `four quarter turns and double flip are identities`() {
        var image = labelled
        repeat(4) { image = GeometryOps.rotateQuarterTurns(image, 1) }
        assertTrue(image.pixels.contentEquals(labelled.pixels))
        assertTrue(GeometryOps.flipHorizontal(GeometryOps.flipHorizontal(labelled)).pixels.contentEquals(labelled.pixels))
    }

    @Test
    fun `on-screen rotate after flip matches flipping the rotated image`() {
        val image = TestImages.checkerboard(cell = 3, width = 9, height = 6)
        val viaGeometry = GeometryOps.apply(image, Geometry.NONE.flipped().rotatedClockwise())
        val expected = GeometryOps.rotateQuarterTurns(GeometryOps.flipHorizontal(image), 1)
        assertTrue(viaGeometry.pixels.contentEquals(expected.pixels))
    }

    @Test
    fun `crop selects the requested region`() {
        val cropped = GeometryOps.crop(TestImages.ramp(0, 255, 100, 40), CropRect.of(0.5f, 0f, 1f, 0.5f))
        assertEquals(50 to 20, cropped.width to cropped.height)
        assertTrue(Argb.red(cropped.pixels[0]) > 120)
    }

    @Test
    fun `crop rectangle follows on-screen rotation`() {
        val crop = CropRect.of(0f, 0f, 0.5f, 1f) // left half
        assertEquals(CropRect.of(0f, 0f, 1f, 0.5f), crop.rotatedClockwise()) // becomes top half
        assertEquals(crop, crop.rotatedClockwise().rotatedCounterClockwise())
    }

    @Test
    fun `straighten auto-crops so no corner samples outside the photo`() {
        val white = TestImages.solid(255, 200, 150)
        val straightened = GeometryOps.straighten(white, 12f)
        assertTrue(straightened.width < 200 && straightened.height < 150)
        assertEquals(200f / 150f, straightened.width.toFloat() / straightened.height, 0.03f)
        listOf(0, straightened.width - 1, straightened.pixelCount - straightened.width, straightened.pixelCount - 1).forEach {
            assertEquals(255, Argb.red(straightened.pixels[it]))
        }
    }

    @Test
    fun `output size prediction matches the real output`() {
        val geometry = Geometry(quarterTurns = 1, straightenDegrees = -7f, crop = CropRect.of(0.1f, 0.2f, 0.8f, 0.9f))
        val image = TestImages.solid(100, 120, 80)
        val output = GeometryOps.apply(image, geometry)
        assertEquals(output.width to output.height, GeometryOps.outputSize(120, 80, geometry))
    }

    @Test
    fun `crop rectangles are clamped to a valid minimum size`() {
        val crop = CropRect.of(0.9f, 0.9f, 0.1f, 0.1f)
        assertTrue(crop.width >= CropRect.MIN_SIZE && crop.right <= 1f)
    }

    @Test
    fun `saved output carries the geometry while validation still passes`() = runTest {
        val saver = RecordingImageSaver()
        val useCase = EnhanceImageUseCase(
            InMemoryImageRepository(mapOf("p" to GoldenScenario.UNDEREXPOSED.render(400, 300))),
            StatisticalImageAnalyzer(), NaturalEnhancementPlanner(), PipelineImageProcessor(DefaultPipeline.stages()),
            NaturalOutputValidator(), saver, dispatcher = Dispatchers.Unconfined,
        )
        val session = (useCase.open("p") as OperationResult.Success).value
        val geometry = Geometry.NONE.rotatedClockwise().copy(crop = CropRect.of(0f, 0f, 1f, 0.5f))
        val outcome = (useCase.enhance(session, EnhanceRequest(0.5f, geometry = geometry)) as OperationResult.Success).value
        assertEquals(300 to 200, outcome.output.width to outcome.output.height)
        assertEquals(outcome.output.width to outcome.output.height, outcome.originalView.width to outcome.originalView.height)
        assertTrue(useCase.save(session, EnhanceRequest(0.5f, geometry = geometry)) is OperationResult.Success)
        assertEquals(300 to 200, saver.saved.single().second.let { it.width to it.height })
    }
}

class CropMathTest {
    private fun assertRect(expected: CropRect, actual: CropRect) {
        listOf(expected.left to actual.left, expected.top to actual.top, expected.right to actual.right, expected.bottom to actual.bottom)
            .forEach { (e, a) -> assertEquals(e, a, 1e-4f, "expected $expected, got $actual") }
    }

    private fun pixelRatio(crop: CropRect, frameAspect: Float) = crop.width / crop.height * frameAspect

    @Test
    fun `largest square in a 4 by 3 frame is full height and centred`() {
        val crop = com.pixels.enhancer.domain.geometry.CropMath.largestCentered(CropRect.FULL, 1f, 4f / 3f)
        assertEquals(1f, crop.height, 1e-4f)
        assertEquals(0.75f, crop.width, 1e-4f)
        assertEquals(0.125f, crop.left, 1e-4f)
    }

    @Test
    fun `move stops at the frame edge without resizing`() {
        val moved = com.pixels.enhancer.domain.geometry.CropMath.move(CropRect.of(0.2f, 0.2f, 0.6f, 0.6f), 0.9f, -0.9f)
        assertRect(CropRect.of(0.6f, 0f, 1f, 0.4f), moved)
    }

    @Test
    fun `free resize keeps the opposite corner fixed`() {
        val resized = com.pixels.enhancer.domain.geometry.CropMath.resize(
            CropRect.FULL, com.pixels.enhancer.domain.geometry.CropCorner.TOP_LEFT, 0.2f, 0.1f, null, 1.5f,
        )
        assertRect(CropRect.of(0.2f, 0.1f, 1f, 1f), resized)
    }

    @Test
    fun `locked resize holds the pixel aspect ratio and stays in the frame`() {
        listOf(0.3f to 0.05f, -0.4f to 0.6f, 0.05f to -0.5f).forEach { (dx, dy) ->
            val resized = com.pixels.enhancer.domain.geometry.CropMath.resize(
                CropRect.of(0.1f, 0.1f, 0.5f, 0.5f), com.pixels.enhancer.domain.geometry.CropCorner.BOTTOM_RIGHT, dx, dy, 16f / 9f, 4f / 3f,
            )
            assertEquals(16f / 9f, pixelRatio(resized, 4f / 3f), 0.02f)
            assertTrue(resized.right <= 1f && resized.bottom <= 1f && resized.left == 0.1f)
        }
    }
}
