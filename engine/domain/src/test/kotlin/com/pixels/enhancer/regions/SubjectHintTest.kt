package com.pixels.enhancer.regions

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.domain.analysis.StatisticalImageAnalyzer
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.domain.local.LocalAdjustmentRenderer
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.processing.PipelineImageProcessor
import com.pixels.enhancer.domain.processing.stages.DefaultPipeline
import com.pixels.enhancer.domain.regions.RegionDetector
import com.pixels.enhancer.domain.regions.RegionKind
import com.pixels.enhancer.domain.regions.SubjectHint
import com.pixels.enhancer.domain.regions.SubjectSegmenter
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.usecase.EnhanceRequest
import com.pixels.enhancer.domain.validation.NaturalOutputValidator
import com.pixels.enhancer.testing.InMemoryImageRepository
import com.pixels.enhancer.testing.RecordingImageSaver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

class SubjectHintTest {
    private val classes = SubjectHint.PASCAL_CLASSES

    /** Logits where every cell votes for [label] (strongly), except cells where [at] says otherwise. */
    private fun logits(w: Int, h: Int, at: (x: Int, y: Int) -> Int): FloatArray = FloatArray(w * h * classes).also { out ->
        for (y in 0 until h) for (x in 0 until w) out[(y * w + x) * classes + at(x, y)] = 8f
    }

    @Test
    fun `only people count, with the bicycle they ride`() {
        val person = 15
        val bicycle = 2
        val bus = 6
        val hint = SubjectHint.fromPascalLogits(logits(4, 1) { x, _ -> listOf(person, bicycle, bus, 0)[x] }, 4, 1)
        assertTrue(hint.weights[0] > 0.99f, "person")
        assertTrue(hint.weights[1] > 0.99f, "bicycle with a rider")
        assertTrue(hint.weights[2] < 0.01f, "bus is ignored")
        assertTrue(hint.weights[3] < 0.01f, "background")

        val noRider = SubjectHint.fromPascalLogits(logits(2, 1) { x, _ -> listOf(bicycle, 0)[x] }, 2, 1)
        assertTrue(noRider.weights[0] < 0.01f, "a bicycle alone is not a subject")
        assertFalse(noRider.hasSubject)
    }

    @Test
    fun `model input is the photo squashed to 257 square in -1 to 1`() {
        val input = SubjectHint.pascalInput(TestImages.solidColor(255, 0, 128, width = 640, height = 300))
        assertEquals(SubjectHint.PASCAL_INPUT_SIZE * SubjectHint.PASCAL_INPUT_SIZE * 3, input.size)
        assertEquals(1f, input[0], 1e-3f)
        assertEquals(-1f, input[1], 1e-3f)
        assertEquals(0.0039f, input[2], 1e-3f)
    }

    /** Blue sky, green ground and a dark "person" column in the middle reaching into the sky. */
    private val scene = PixelBuffer(300, 200, IntArray(300 * 200) { i ->
        val x = i % 300
        val y = i / 300
        when {
            x in 130 until 170 && y >= 30 -> Argb.opaque(40, 35, 30)
            y < 80 -> Argb.opaque(110, 160, 230)
            else -> Argb.opaque(90, 130, 60)
        }
    })
    private val personHint = SubjectHint(30, 20, FloatArray(30 * 20) { i -> if (i % 30 in 13 until 17 && i / 30 >= 3) 1f else 0f })

    @Test
    fun `a hint becomes the subject and is never sky`() {
        val maps = RegionDetector.detect(scene, personHint)
        val subject = maps.weights(RegionKind.SUBJECT, 300, 200)
        val sky = maps.weights(RegionKind.SKY, 300, 200)
        assertTrue(subject[60 * 300 + 150] > 0.8f, "person against the sky")
        assertTrue(subject[150 * 300 + 150] > 0.8f, "person on the ground")
        assertTrue(subject[150 * 300 + 30] < 0.05f, "ground")
        assertTrue(sky[60 * 300 + 150] < 0.2f, "the person is not sky")
        assertTrue(sky[20 * 300 + 30] > 0.8f, "sky")
    }

    @Test
    fun `a hint without a person leaves the subject to the rules`() {
        val empty = SubjectHint(30, 20, FloatArray(600))
        assertFalse(empty.hasSubject)
        val withEmpty = RegionDetector.detect(scene, empty)
        val rules = RegionDetector.detect(scene)
        assertTrue(withEmpty.subject.contentEquals(rules.subject))
    }

    @Test
    fun `the hint follows rotation like the photo`() {
        val turned = personHint.transformed(300, 200, Geometry.NONE.rotatedClockwise())
        assertEquals(turned.height > turned.width, true)
        // The column becomes a row: the middle of the left half (where the person's feet were at the bottom) is covered.
        val midRow = turned.height / 2
        assertTrue(turned.weights[midRow * turned.width + turned.width / 6] > 0.8f)
        assertTrue(turned.weights[midRow * turned.width + turned.width - 2] < 0.2f)
    }

    @Test
    fun `the use case keeps the hint and Smart edit uses it`() = runTest {
        val useCase = EnhanceImageUseCase(
            InMemoryImageRepository(mapOf("p" to scene)), StatisticalImageAnalyzer(), NaturalEnhancementPlanner(),
            PipelineImageProcessor(DefaultPipeline.stages()), NaturalOutputValidator(), RecordingImageSaver(),
            dispatcher = Dispatchers.Unconfined, subjectSegmenter = SubjectSegmenter { personHint },
        )
        val session = (useCase.open("p") as OperationResult.Success).value
        assertNotNull(session.subjectHint)
        val outcome = (useCase.enhance(session, EnhanceRequest(0.5f)) as OperationResult.Success).value
        assertNotNull(outcome.subjectHint)
        val subject = LocalAdjustment.region(1, RegionKind.SUBJECT).copy(exposure = 0.5f)
        val mask = LocalAdjustmentRenderer.maskOf(outcome.output, subject, outcome.originalView, outcome.subjectHint)
        assertTrue(mask[150 * 300 + 150] > 0.8f && mask[150 * 300 + 30] < 0.05f)
        val masks = (useCase.suggestSmartEdit(session, EnhanceRequest(0.5f)) as OperationResult.Success).value
        assertTrue(masks.any { it.name == "Subject" }, "$masks")
        val rendered = (useCase.enhance(session, EnhanceRequest(0.5f, localAdjustments = LocalAdjustments(listOf(subject)))) as OperationResult.Success).value
        assertTrue(rendered.output.pixels[150 * 300 + 150] != outcome.output.pixels[150 * 300 + 150])
    }

    @Test
    fun `a failing segmenter does not stop the photo from opening`() = runTest {
        val useCase = EnhanceImageUseCase(
            InMemoryImageRepository(mapOf("p" to scene)), StatisticalImageAnalyzer(), NaturalEnhancementPlanner(),
            PipelineImageProcessor(DefaultPipeline.stages()), NaturalOutputValidator(), RecordingImageSaver(),
            dispatcher = Dispatchers.Unconfined, subjectSegmenter = SubjectSegmenter { error("no model") },
        )
        val session = (useCase.open("p") as OperationResult.Success).value
        assertNull(session.subjectHint)
    }
}
