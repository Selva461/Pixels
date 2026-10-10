package com.pixels.enhancer.processing

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.domain.analysis.StatisticalImageAnalyzer
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.geometry.GeometryOps
import com.pixels.enhancer.domain.geometry.LensCorrection
import com.pixels.enhancer.domain.geometry.OpticsWarp
import com.pixels.enhancer.domain.geometry.Perspective
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.local.BrushStroke
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.domain.local.LocalAdjustmentRenderer
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.local.MaskShape
import com.pixels.enhancer.domain.planning.ColorGrading
import com.pixels.enhancer.domain.planning.GradeRange
import com.pixels.enhancer.domain.planning.GradeWheel
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.presets.FilePresetStore
import com.pixels.enhancer.domain.presets.Preset
import com.pixels.enhancer.domain.presets.PresetLibrary
import com.pixels.enhancer.domain.presets.PresetMath
import com.pixels.enhancer.domain.presets.SettingsGroup
import com.pixels.enhancer.domain.processing.PipelineImageProcessor
import com.pixels.enhancer.domain.processing.stages.ColorGradingStage
import com.pixels.enhancer.domain.processing.stages.DefaultPipeline
import com.pixels.enhancer.domain.project.EditStateCodec
import com.pixels.enhancer.domain.project.EditVersion
import com.pixels.enhancer.domain.project.Project
import com.pixels.enhancer.domain.project.ProjectCodec
import com.pixels.enhancer.domain.retouch.Retouch
import com.pixels.enhancer.domain.retouch.RetouchMode
import com.pixels.enhancer.domain.retouch.RetouchRenderer
import com.pixels.enhancer.domain.retouch.RetouchSourceFinder
import com.pixels.enhancer.domain.retouch.RetouchSpot
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.usecase.EnhanceRequest
import com.pixels.enhancer.domain.validation.NaturalOutputValidator
import com.pixels.enhancer.testing.InMemoryImageRepository
import com.pixels.enhancer.testing.RecordingImageSaver
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

class PremiumToolsTest {

    private fun lumaAt(image: PixelBuffer, x: Int, y: Int) = Luma.ofPixel(image.pixels[y * image.width + x])

    // --- Optics and perspective ---

    @Test
    fun `identity optics return the input`() {
        val image = TestImages.ramp(0, 255)
        assertSame(image, OpticsWarp.apply(image, LensCorrection.NONE, Perspective.NONE))
    }

    @Test
    fun `lens vignetting correction brightens corners and leaves the centre`() {
        val image = TestImages.solid(100, 101, 81)
        val out = OpticsWarp.apply(image, LensCorrection(vignetting = 1f), Perspective.NONE)
        assertEquals(lumaAt(image, 50, 40), lumaAt(out, 50, 40), 0.01f)
        assertTrue(lumaAt(out, 0, 0) > lumaAt(image, 0, 0) + 0.1f)
    }

    /** Two dark lines converging toward the top, as when shooting a building from below. */
    private fun convergingLines(): PixelBuffer {
        val w = 160
        val h = 160
        val pixels = IntArray(w * h) { Argb.opaque(230, 230, 230) }
        for (y in 0 until h) {
            val t = y / (h - 1f)
            val left = (55 - 25 * t).toInt()
            val right = (105 + 25 * t).toInt()
            for (dx in 0..1) {
                pixels[y * w + left + dx] = Argb.opaque(10, 10, 10)
                pixels[y * w + right + dx] = Argb.opaque(10, 10, 10)
            }
        }
        return PixelBuffer(w, h, pixels)
    }

    private fun gap(image: PixelBuffer, y: Int): Int {
        val dark = (0 until image.width).filter { lumaAt(image, it, y) < 0.4f }
        return dark.last() - dark.first()
    }

    @Test
    fun `vertical perspective makes converging verticals more parallel`() {
        val image = convergingLines()
        val before = gap(image, 20).toFloat() / gap(image, 140)
        val out = OpticsWarp.apply(image, LensCorrection.NONE, Perspective(vertical = 0.6f))
        val after = gap(out, 20).toFloat() / gap(out, 140)
        assertTrue(abs(1f - after) < abs(1f - before) * 0.6f, "ratio top/bottom $before -> $after")
    }

    @Test
    fun `corrected frame is zoomed so every border pixel comes from inside the photo`() {
        for (perspective in listOf(Perspective(vertical = 1f), Perspective(horizontal = -1f), Perspective(rotate = 8f))) {
            for (lens in listOf(LensCorrection.NONE, LensCorrection(distortion = -1f))) {
                val mapper = OpticsWarp.Mapper(200, 120, lens, perspective)
                val point = FloatArray(2)
                for ((x, y) in listOf(0f to 0f, 199f to 0f, 0f to 119f, 199f to 119f, 100f to 0f, 0f to 60f)) {
                    mapper.toSource(x, y, point)
                    assertTrue(point[0] in -0.6f..199.6f && point[1] in -0.6f..119.6f, "$perspective $lens ($x,$y) -> ${point.toList()}")
                }
            }
        }
    }

    @Test
    fun `perspective follows the picture when it is rotated or flipped`() {
        val geometry = Geometry().withPerspective(Perspective(vertical = 0.5f, offsetX = 0.2f))
        val turned = geometry.rotatedClockwise()
        assertEquals(0.5f, turned.perspective.horizontal * -1f, 1e-6f)
        assertEquals(geometry.perspective, turned.rotatedCounterClockwise().perspective)
        assertEquals(-0.2f, geometry.flipped().perspective.offsetX, 1e-6f)
        val image = TestImages.ramp(0, 255, 64, 40)
        assertEquals(64 to 40, GeometryOps.apply(image, geometry).let { it.width to it.height })
    }

    // --- Masks ---

    @Test
    fun `brush stroke selects along its path and erase strokes remove it`() {
        val stroke = BrushStroke(listOf(0.1f, 0.5f, 0.9f, 0.5f), radius = 0.05f, feather = 0.2f)
        val painted = LocalAdjustment.brush(1).withStroke(stroke).copy(exposure = 0.5f)
        val mask = LocalAdjustmentRenderer.maskOf(TestImages.solid(128, 100, 100), painted)
        assertTrue(mask[50 * 100 + 50] > 0.95f)
        assertEquals(0f, mask[10 * 100 + 50])
        val erased = painted.withStroke(BrushStroke(listOf(0.5f, 0.3f, 0.5f, 0.7f), radius = 0.08f, feather = 0f, erase = true))
        val after = LocalAdjustmentRenderer.maskOf(TestImages.solid(128, 100, 100), erased)
        assertTrue(after[50 * 100 + 50] < 0.05f)
        assertTrue(after[50 * 100 + 20] > 0.95f)
    }

    @Test
    fun `luminance range limits a whole-photo mask to bright tones`() {
        val ramp = TestImages.ramp(0, 255)
        val mask = LocalAdjustmentRenderer.maskOf(ramp, LocalAdjustment.luminanceRange(1, 0.7f, 1f).copy(exposure = -0.5f))
        assertEquals(0f, mask[10], 1e-3f)
        assertEquals(1f, mask[250], 1e-3f)
    }

    @Test
    fun `colour range selects the sampled colour only`() {
        val w = 64
        val image = PixelBuffer(w, 16, IntArray(w * 16) { if (it % w < w / 2) Argb.opaque(200, 40, 40) else Argb.opaque(40, 60, 200) })
        val adjustment = LocalAdjustment.colorRange(1, 210, 50, 45).copy(saturation = -1f)
        val mask = LocalAdjustmentRenderer.maskOf(image, adjustment)
        assertTrue(mask[5] > 0.9f)
        assertTrue(mask[w - 5] < 0.05f)
        val out = LocalAdjustmentRenderer.apply(image, LocalAdjustments(listOf(adjustment)))
        val red = out.pixels[5]
        assertTrue(abs(Argb.red(red) - Argb.green(red)) < 10, "red half should be grey")
        assertEquals(image.pixels[w - 5], out.pixels[w - 5])
    }

    @Test
    fun `local clarity adds contrast at edges and local shadows lift dark tones`() {
        val edge = TestImages.stepEdge(80, 160, 128, 32)
        val out = LocalAdjustmentRenderer.apply(edge, LocalAdjustments(listOf(LocalAdjustment(1, MaskShape.Full, clarity = 1f))))
        assertTrue(lumaAt(out, 63, 16) < lumaAt(edge, 63, 16))
        assertTrue(lumaAt(out, 64, 16) > lumaAt(edge, 64, 16))
        val dark = TestImages.solid(40)
        val lifted = LocalAdjustmentRenderer.apply(dark, LocalAdjustments(listOf(LocalAdjustment(1, MaskShape.Full, shadows = 1f))))
        assertTrue(lumaAt(lifted, 5, 5) > lumaAt(dark, 5, 5) + 0.03f)
    }

    // --- Retouch ---

    /** Smooth gradient with a dark blemish at (60, 40). */
    private fun blemished(): Pair<PixelBuffer, PixelBuffer> {
        val w = 120
        val h = 80
        val clean = PixelBuffer(w, h, IntArray(w * h) { val x = it % w; val v = 90 + x; Argb.opaque(v, v - 10, v - 20) })
        val spotted = clean.copy()
        for (y in 36..44) for (x in 56..64) spotted.pixels[y * w + x] = Argb.opaque(15, 10, 10)
        return clean to spotted
    }

    @Test
    fun `heal removes a blemish and matches the surrounding gradient`() {
        val (clean, spotted) = blemished()
        // Source taken from a brighter part of the gradient: heal must still match colour.
        val spot = RetouchSpot(1, 60.5f / 120, 40.5f / 80, 60.5f / 120, 15.5f / 80, radius = 9f / 120, feather = 0.3f)
        val healed = RetouchRenderer.apply(spotted, Retouch(listOf(spot)))
        for ((x, y) in listOf(60 to 40, 57 to 37, 63 to 43)) {
            val diff = abs(lumaAt(healed, x, y) - lumaAt(clean, x, y))
            assertTrue(diff < 0.04f, "($x,$y) differs by $diff")
        }
        assertEquals(spotted.pixels[5], healed.pixels[5])
    }

    @Test
    fun `clone copies the source exactly at the centre`() {
        val (_, spotted) = blemished()
        val spot = RetouchSpot(1, 0.5f, 0.5f, 0.2f, 0.5f, radius = 0.05f, feather = 0f, mode = RetouchMode.CLONE)
        val out = RetouchRenderer.apply(spotted, Retouch(listOf(spot)))
        assertEquals(spotted.pixels[40 * 120 + 24], out.pixels[40 * 120 + 60])
    }

    @Test
    fun `automatic source is a clean nearby patch, not the blemish`() {
        val (_, spotted) = blemished()
        val (sx, sy) = RetouchSourceFinder.find(spotted, 60.5f / 120, 40.5f / 80, 9f / 120)
        val distance = kotlin.math.hypot((sx * 120 - 60.5f).toDouble(), (sy * 80 - 40.5f).toDouble())
        assertTrue(distance > 15, "source $sx,$sy too close to the blemish")
        assertTrue(abs(sx * 120 - 60.5f) < 30, "vertical neighbours match the gradient best, got x=${sx * 120}")
    }

    // --- Colour grading ---

    @Test
    fun `shadow tint colours dark tones and leaves highlights`() {
        val grading = ColorGrading().with(GradeRange.SHADOWS, GradeWheel(240f, 1f))
        val stage = ColorGradingStage()
        val context = contextFor(planOverride = { it.copy(colorGrading = grading) })
        val dark = kotlinx.coroutines.runBlocking { stage.execute(TestImages.solid(40), context) }.pixels[0]
        val bright = kotlinx.coroutines.runBlocking { stage.execute(TestImages.solid(230), context) }.pixels[0]
        assertTrue(Argb.blue(dark) > Argb.red(dark) + 10, "dark should turn blue: ${Integer.toHexString(dark)}")
        assertTrue(abs(Argb.blue(bright) - Argb.red(bright)) < 4)
        assertEquals(40f / 255, Luma.ofPixel(dark), 0.03f)
    }

    @Test
    fun `black and white treatment removes colour`() {
        val context = contextFor(planOverride = { it.copy(colorGrading = ColorGrading(monochrome = true)) })
        val out = kotlinx.coroutines.runBlocking { ColorGradingStage().execute(TestImages.solidColor(200, 60, 30), context) }.pixels[0]
        assertEquals(Argb.red(out), Argb.green(out), absoluteTolerance = 1)
        assertEquals(Argb.green(out), Argb.blue(out), absoluteTolerance = 1)
    }

    private fun assertEquals(expected: Int, actual: Int, absoluteTolerance: Int) =
        assertTrue(abs(expected - actual) <= absoluteTolerance, "expected $expected ± $absoluteTolerance, got $actual")

    // --- Presets, paste, persistence ---

    @Test
    fun `preset amount scales sliders and keeps per-photo edits`() {
        val preset = PresetLibrary.byId("builtin.landscape.vivid")!!
        val current = EditState(geometry = Geometry(quarterTurns = 1), localAdjustments = LocalAdjustments(listOf(LocalAdjustment.linearTop(1))))
        val full = PresetMath.apply(current, preset, 1f)
        val half = PresetMath.apply(current, preset, 0.5f)
        val none = PresetMath.apply(current, preset, 0f)
        assertEquals(preset.settings.manual[ManualControl.VIBRANCE], full.manual[ManualControl.VIBRANCE])
        assertEquals(preset.settings.manual[ManualControl.VIBRANCE] / 2, half.manual[ManualControl.VIBRANCE], 1e-6f)
        assertTrue(none.manual.isNeutral)
        assertEquals(current.geometry, full.geometry)
        assertEquals(current.localAdjustments, full.localAdjustments)
    }

    @Test
    fun `paste copies only the chosen groups`() {
        val from = EditState(manual = ManualAdjustments.of(ManualControl.EXPOSURE to 0.5f, ManualControl.TEMPERATURE to 0.4f), colorGrading = ColorGrading(monochrome = true))
        val to = EditState(manual = ManualAdjustments.of(ManualControl.TEMPERATURE to -0.2f))
        val pasted = PresetMath.paste(from, to, setOf(SettingsGroup.LIGHT))
        assertEquals(0.5f, pasted.manual[ManualControl.EXPOSURE])
        assertEquals(-0.2f, pasted.manual[ManualControl.TEMPERATURE])
        assertEquals(ColorGrading.NONE, pasted.colorGrading)
        assertEquals(from.colorGrading, PresetMath.paste(from, to, SettingsGroup.ALL).colorGrading)
    }

    private fun richEdit() = EditState(
        manual = ManualAdjustments.of(ManualControl.GRAIN_SIZE to 0.4f, ManualControl.SHARPEN_RADIUS to 0.5f),
        geometry = Geometry().withLens(LensCorrection(0.3f, -0.2f, 0.4f)).withPerspective(Perspective(0.2f, -0.1f, 2f, 0.1f, 0.2f, 0.05f, -0.05f)),
        localAdjustments = LocalAdjustments(
            listOf(
                LocalAdjustment.brush(1).withStroke(BrushStroke(listOf(0.1f, 0.2f, 0.3f, 0.4f), erase = true)).copy(highlights = -0.5f, name = "Sky"),
                LocalAdjustment.colorRange(2, 10, 20, 200).copy(clarity = 0.3f, tint = 0.1f),
                LocalAdjustment.luminanceRange(3).copy(sharpness = -0.2f, invert = true),
            ),
        ),
        colorGrading = ColorGrading(shadows = GradeWheel(200f, 0.3f, -0.1f), balance = 0.2f, blending = 0.7f, monochrome = true),
        retouch = Retouch(listOf(RetouchSpot(1, 0.2f, 0.3f, 0.4f, 0.5f, mode = RetouchMode.CLONE, opacity = 0.8f))),
    )

    @Test
    fun `new edit fields survive a project round trip, with versions`() {
        val edit = richEdit()
        val project = Project("abc-1", "content://x", "x.jpg", 1, 2, edit, versions = listOf(EditVersion("Before sky", 5, EditState())))
        val decoded = ProjectCodec.decode(ProjectCodec.encode(project))
        assertEquals(edit, decoded.edit)
        assertEquals(project.versions, decoded.versions)
        assertEquals(edit, EditStateCodec.decode(EditStateCodec.encode(edit)))
    }

    @Test
    fun `user presets are saved and listed`() = runTest {
        val directory = Files.createTempDirectory("presets").toFile()
        val store = FilePresetStore(directory, dispatcher = Dispatchers.Unconfined)
        val settings = PresetMath.settingsOf(richEdit())
        store.save(Preset("user-1", "Mine", "User", settings, builtIn = false))
        val listed = store.list().single()
        assertEquals("Mine", listed.name)
        assertEquals(settings, listed.settings)
        assertEquals(Geometry.NONE, listed.settings.geometry)
        store.delete("user-1")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `every built-in preset renders a sensible photo end to end`() = runTest {
        val photo = TestImages.withGaussianNoise(TestImages.ramp(20, 235, 96, 64), 4.0)
        val useCase = EnhanceImageUseCase(
            InMemoryImageRepository(mapOf("p" to photo)), StatisticalImageAnalyzer(), NaturalEnhancementPlanner(),
            PipelineImageProcessor(DefaultPipeline.stages()), NaturalOutputValidator(), RecordingImageSaver(),
            dispatcher = Dispatchers.Unconfined,
        )
        val session = (useCase.open("p") as OperationResult.Success).value
        PresetLibrary.ALL.forEach { preset ->
            val edit = PresetMath.apply(EditState(), preset)
            val request = EnhanceRequest(edit.strength, edit.manual, colorMixer = edit.colorMixer, toneCurves = edit.toneCurves, colorGrading = edit.colorGrading)
            val result = useCase.enhance(session, request)
            assertTrue(result is OperationResult.Success, "${preset.name}: $result")
            val mean = result.value.output.pixels.map { Luma.ofPixel(it) }.average()
            assertTrue(mean in 0.15..0.85, "${preset.name} mean luma $mean")
            assertNotEquals(photo.pixels.toList(), result.value.output.pixels.toList())
        }
    }

    @Test
    fun `retouch and lens edits flow through the use case`() = runTest {
        val (_, spotted) = blemished()
        val useCase = EnhanceImageUseCase(
            InMemoryImageRepository(mapOf("p" to spotted)), StatisticalImageAnalyzer(), NaturalEnhancementPlanner(),
            PipelineImageProcessor(DefaultPipeline.stages()), NaturalOutputValidator(), RecordingImageSaver(),
            dispatcher = Dispatchers.Unconfined,
        )
        val session = (useCase.open("p") as OperationResult.Success).value
        val spot = RetouchSpot(1, 60.5f / 120, 40.5f / 80, 60.5f / 120, 15.5f / 80, radius = 9f / 120)
        val result = useCase.enhance(session, EnhanceRequest(0f, retouch = Retouch(listOf(spot)), geometry = Geometry().withLens(LensCorrection(vignetting = 0.3f))))
        assertTrue(result is OperationResult.Success, "$result")
        assertTrue(lumaAt(result.value.output, 60, 40) > 0.2f, "blemish should be gone")
    }
}
