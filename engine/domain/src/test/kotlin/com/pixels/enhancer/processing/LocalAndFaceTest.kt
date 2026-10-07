package com.pixels.enhancer.processing

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.domain.analysis.FaceLocator
import com.pixels.enhancer.domain.analysis.FaceMetrics
import com.pixels.enhancer.domain.analysis.FaceRegion
import com.pixels.enhancer.domain.analysis.StatisticalImageAnalyzer
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.domain.local.LocalAdjustmentRenderer
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.local.MaskShape
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.planning.QualityPreset
import com.pixels.enhancer.domain.processing.PipelineImageProcessor
import com.pixels.enhancer.domain.processing.stages.DefaultPipeline
import com.pixels.enhancer.domain.project.Project
import com.pixels.enhancer.domain.project.ProjectCodec
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.usecase.EnhanceRequest
import com.pixels.enhancer.domain.validation.NaturalOutputValidator
import com.pixels.enhancer.goodAnalysis
import com.pixels.enhancer.testing.InMemoryImageRepository
import com.pixels.enhancer.testing.RecordingImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalAdjustmentTest {
    private val grey = TestImages.solid(128, 200, 100)
    private fun lumaAt(image: PixelBuffer, x: Int, y: Int) = Luma.ofPixel(image.pixels[y * image.width + x])

    @Test
    fun `radial mask brightens inside and leaves the corners alone`() {
        val mask = LocalAdjustment(1, MaskShape.Radial(0.5f, 0.5f, 0.2f, 0.4f, 0.3f), exposure = 0.5f)
        val result = LocalAdjustmentRenderer.apply(grey, LocalAdjustments(listOf(mask)))
        assertTrue(lumaAt(result, 100, 50) > lumaAt(grey, 100, 50) + 0.1f)
        assertEquals(grey.pixels[0], result.pixels[0])
    }

    @Test
    fun `inverted radial affects only the outside`() {
        val mask = LocalAdjustment(1, MaskShape.Radial(0.5f, 0.5f, 0.2f, 0.4f, 0.3f), invert = true, exposure = -0.5f)
        val result = LocalAdjustmentRenderer.apply(grey, LocalAdjustments(listOf(mask)))
        assertEquals(grey.pixels[50 * 200 + 100], result.pixels[50 * 200 + 100])
        assertTrue(lumaAt(result, 2, 2) < lumaAt(grey, 2, 2) - 0.1f)
    }

    @Test
    fun `linear mask fades smoothly from start to end`() {
        val mask = LocalAdjustment(1, MaskShape.Linear(0.5f, 0f, 0.5f, 1f), saturation = -1f)
        val colour = TestImages.solidColor(200, 60, 60, 20, 100)
        val result = LocalAdjustmentRenderer.apply(colour, LocalAdjustments(listOf(mask)))
        val chroma = (0 until 100 step 10).map { y -> com.pixels.enhancer.domain.analysis.SaturationEstimator.chromaOf(result.pixels[y * 20 + 10]) }
        assertTrue(chroma.zipWithNext().all { (a, b) -> b >= a - 1e-3f }, "chroma should rise from top to bottom: $chroma")
        assertTrue(chroma.first() < 0.1f && chroma.last() > 0.5f)
    }

    @Test
    fun `neutral masks change nothing and the input is never modified`() {
        val snapshot = grey.pixels.copyOf()
        val out = LocalAdjustmentRenderer.apply(grey, LocalAdjustments(listOf(LocalAdjustment.radialAt(1, 0.5f, 0.5f, 2f).copy(exposure = 1f))))
        assertTrue(snapshot.contentEquals(grey.pixels))
        assertFalse(out === grey)
        assertTrue(LocalAdjustmentRenderer.apply(grey, LocalAdjustments(listOf(LocalAdjustment.linearTop(1)))) === grey)
    }

    @Test
    fun `masks survive a project round trip`() {
        val local = LocalAdjustments(
            listOf(
                LocalAdjustment.linearTop(1).copy(exposure = -0.3f, temperature = 0.2f),
                LocalAdjustment.radialAt(2, 0.3f, 0.6f, 1.5f).copy(invert = true, contrast = 0.4f, saturation = -0.2f),
            ),
        )
        val project = Project("p-3", "src", null, 1, 2, EditState(localAdjustments = local))
        assertEquals(local, ProjectCodec.decode(ProjectCodec.encode(project)).edit.localAdjustments)
    }
}

class FaceExposureTest {
    /** Bright background with a dark "face" disc, like a backlit portrait. */
    private val backlit = PixelBuffer(240, 320, IntArray(240 * 320) { index ->
        val x = index % 240
        val y = index / 240
        val dx = x - 120
        val dy = y - 140
        if (dx * dx + dy * dy < 50 * 50) Argb.opaque(70, 52, 42) else Argb.opaque(200, 205, 210)
    })
    private val face = FaceRegion(0.5f, 140f / 320f, 50f / 240f, 0.9f)

    @Test
    fun `face luma is measured inside the face`() {
        assertTrue(FaceMetrics.meanLuma(backlit, listOf(face))!! < 0.25f)
        assertEquals(null, FaceMetrics.meanLuma(backlit, emptyList()))
    }

    @Test
    fun `planner lifts a dark face gently and leaves bright faces alone`() {
        val planner = NaturalEnhancementPlanner()
        val dark = planner.createPlan(goodAnalysis().copy(faces = listOf(face), faceLuma = 0.2f), 0.5f, QualityPreset.NATURAL)
        val bright = planner.createPlan(goodAnalysis().copy(faces = listOf(face), faceLuma = 0.55f), 0.5f, QualityPreset.NATURAL)
        val none = planner.createPlan(goodAnalysis(), 0.5f, QualityPreset.NATURAL)
        assertTrue(dark.faceExposure.enabled && dark.faceExposure.amount in 0.1f..1.25f, "${dark.faceExposure}")
        assertFalse(bright.faceExposure.enabled)
        assertFalse(none.faceExposure.enabled)
    }

    @Test
    fun `backlit face is brightened while the background barely changes`() = runTest {
        val useCase = EnhanceImageUseCase(
            InMemoryImageRepository(mapOf("p" to backlit)), StatisticalImageAnalyzer(), NaturalEnhancementPlanner(),
            PipelineImageProcessor(DefaultPipeline.stages()), NaturalOutputValidator(), RecordingImageSaver(),
            dispatcher = Dispatchers.Unconfined, faceLocator = FaceLocator { listOf(face) },
        )
        val session = (useCase.open("p") as OperationResult.Success).value
        assertEquals(1, session.analysis.faces.size)
        val result = (useCase.enhance(session, EnhanceRequest(0.5f)) as OperationResult.Success).value
        assertTrue(result.plan.faceExposure.enabled, result.plan.faceExposure.reason)
        assertFalse(result.plan.exposure.amount < 0f, "a backlit portrait must not be darkened globally: ${result.plan.exposure}")
        val out = result.output
        val faceBefore = Luma.ofPixel(backlit.pixels[140 * 240 + 120])
        val faceAfter = Luma.ofPixel(out.pixels[140 * 240 + 120])
        val cornerChange = kotlin.math.abs(Luma.ofPixel(out.pixels[5]) - Luma.ofPixel(backlit.pixels[5]))
        assertTrue(faceAfter > faceBefore + 0.05f, "face $faceBefore -> $faceAfter")
        assertTrue(cornerChange < 0.05f, "background changed by $cornerChange")
    }
}
