package com.pixels.enhancer.processing

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.planning.CurveChannel
import com.pixels.enhancer.domain.planning.CurvePoint
import com.pixels.enhancer.domain.planning.CurvePoints
import com.pixels.enhancer.domain.planning.ToneCurves
import com.pixels.enhancer.domain.processing.stages.CurvesStage
import com.pixels.enhancer.domain.project.Project
import com.pixels.enhancer.domain.project.ProjectCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CurvesTest {
    @Test
    fun `identity curve maps every value to itself`() {
        (0..255).forEach { assertEquals(it, CurvePoints.IDENTITY.lut()[it]) }
    }

    @Test
    fun `curve passes through its points and never overshoots`() {
        val curve = CurvePoints.of(listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.2f), CurvePoint(0.5f, 0.65f), CurvePoint(0.75f, 0.9f), CurvePoint(1f, 1f)))
        assertEquals(0.65f, curve.valueAt(0.5f), 0.001f)
        var previous = -1f
        (0..100).forEach { step ->
            val y = curve.valueAt(step / 100f)
            assertTrue(y >= previous - 1e-5f, "monotone data must give a monotone curve at $step")
            previous = y
        }
    }

    @Test
    fun `invalid point sets are repaired`() {
        val messy = CurvePoints.of(listOf(CurvePoint(0.5f, 2f), CurvePoint(0.51f, 0.1f), CurvePoint(-1f, 0.1f)) + (1..20).map { CurvePoint(it / 21f, 0.5f) })
        assertEquals(0f, messy.points.first().x)
        assertEquals(1f, messy.points.last().x)
        assertTrue(messy.points.size <= CurvePoints.MAX_POINTS)
        messy.points.zipWithNext().forEach { (a, b) -> assertTrue(b.x - a.x >= CurvePoints.MIN_GAP - 1e-6f) }
        assertTrue(messy.points.all { it.y in 0f..1f })
    }

    @Test
    fun `master and channel curves compose`() = runTest {
        val brighten = CurvePoints.of(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f)))
        val noBlue = CurvePoints.of(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 0f)))
        val curves = ToneCurves.NONE.with(CurveChannel.MASTER, brighten).with(CurveChannel.BLUE, noBlue)
        val result = CurvesStage().execute(TestImages.solid(128), contextFor(planOverride = { it.copy(toneCurves = curves) })).pixels[0]
        assertTrue(Argb.red(result) > 160)
        assertEquals(Argb.red(result), Argb.green(result))
        assertEquals(0, Argb.blue(result))
    }

    @Test
    fun `curves survive a project round trip`() {
        val curves = ToneCurves.NONE.with(CurveChannel.RED, CurvePoints.of(listOf(CurvePoint(0f, 0.05f), CurvePoint(0.4f, 0.5f), CurvePoint(1f, 0.95f))))
        val project = Project("p-2", "src", null, 1, 2, EditState(toneCurves = curves))
        assertEquals(curves, ProjectCodec.decode(ProjectCodec.encode(project)).edit.toneCurves)
    }
}
