package com.pixels.enhancer.processing

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.domain.analysis.StatisticalImageAnalyzer
import com.pixels.enhancer.domain.editing.EditDiff
import com.pixels.enhancer.domain.editing.EditHistory
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.export.Border
import com.pixels.enhancer.domain.export.BorderOps
import com.pixels.enhancer.domain.export.ExportDecorator
import com.pixels.enhancer.domain.export.ExportFormat
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.export.ExportSize
import com.pixels.enhancer.domain.export.Watermark
import com.pixels.enhancer.domain.export.WatermarkPosition
import com.pixels.enhancer.domain.geometry.CropRect
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.model.OutputNaming
import com.pixels.enhancer.domain.planning.Calibration
import com.pixels.enhancer.domain.planning.ColorGrading
import com.pixels.enhancer.domain.planning.CurvePreset
import com.pixels.enhancer.domain.planning.GradeWheel
import com.pixels.enhancer.domain.planning.HslShift
import com.pixels.enhancer.domain.planning.HueBand
import com.pixels.enhancer.domain.planning.ManualAdjustmentMerger
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.presets.Preset
import com.pixels.enhancer.domain.presets.PresetMath
import com.pixels.enhancer.domain.presets.SettingsGroup
import com.pixels.enhancer.domain.processing.PipelineImageProcessor
import com.pixels.enhancer.domain.processing.stages.CalibrationMatrix
import com.pixels.enhancer.domain.processing.stages.CalibrationStage
import com.pixels.enhancer.domain.processing.stages.DefaultPipeline
import com.pixels.enhancer.domain.processing.stages.DefringeStage
import com.pixels.enhancer.domain.project.ExportOptionsCodec
import com.pixels.enhancer.domain.project.FileProjectStore
import com.pixels.enhancer.domain.project.Project
import com.pixels.enhancer.domain.project.ProjectCodec
import com.pixels.enhancer.domain.project.ProjectManager
import com.pixels.enhancer.domain.project.WallClock
import com.pixels.enhancer.domain.retouch.Retouch
import com.pixels.enhancer.domain.retouch.RetouchSpot
import com.pixels.enhancer.domain.usecase.BatchExportUseCase
import com.pixels.enhancer.domain.usecase.BatchItemResult
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.usecase.EnhanceRequest
import com.pixels.enhancer.domain.usecase.RenderTarget
import com.pixels.enhancer.domain.usecase.toRequest
import com.pixels.enhancer.domain.validation.NaturalOutputValidator
import com.pixels.enhancer.testing.GoldenScenario
import com.pixels.enhancer.testing.InMemoryImageRepository
import com.pixels.enhancer.testing.RecordingImageSaver
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class ProEditorExtrasTest {

    private fun useCaseFor(images: Map<String, PixelBuffer>, saver: RecordingImageSaver = RecordingImageSaver(), decorator: ExportDecorator = ExportDecorator.NONE) =
        EnhanceImageUseCase(
            InMemoryImageRepository(images), StatisticalImageAnalyzer(), NaturalEnhancementPlanner(),
            PipelineImageProcessor(DefaultPipeline.stages()), NaturalOutputValidator(), saver,
            dispatcher = Dispatchers.Unconfined, exportDecorator = decorator,
        )

    private fun chroma(color: Int): Int = max(Argb.red(color), max(Argb.green(color), Argb.blue(color))) -
        min(Argb.red(color), min(Argb.green(color), Argb.blue(color)))

    private fun calibrate(calibration: Calibration, image: PixelBuffer): PixelBuffer {
        val context = contextFor(planOverride = { it.copy(calibration = calibration) })
        return runBlocking { CalibrationStage().execute(image.copy(), context) }
    }

    // --- Calibration ---

    @Test
    fun `calibration never tints greys`() {
        val calibration = Calibration(redHue = 1f, redSaturation = -1f, greenHue = -0.7f, greenSaturation = 1f, blueHue = 0.4f, blueSaturation = 0.6f)
        for (value in listOf(5, 40, 128, 200, 250)) {
            val out = calibrate(calibration, TestImages.solid(value)).pixels[0]
            assertTrue(chroma(out) <= 1, "grey $value became ${Integer.toHexString(out)}")
        }
        val m = CalibrationMatrix.of(calibration)
        for (row in 0..2) assertEquals(1f, m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2], 1e-4f)
    }

    @Test
    fun `red primary hue moves reds toward orange and blue saturation mutes blues`() {
        val red = calibrate(Calibration(redHue = 1f), TestImages.solidColor(200, 30, 30)).pixels[0]
        assertTrue(Argb.green(red) > Argb.blue(red) + 8, "red should turn orange: ${Integer.toHexString(red)}")
        val blue = TestImages.solidColor(40, 60, 200)
        val muted = calibrate(Calibration(blueSaturation = -1f), blue).pixels[0]
        assertTrue(chroma(muted) < chroma(blue.pixels[0]) - 20, "blue should lose saturation: ${Integer.toHexString(muted)}")
    }

    @Test
    fun `shadows tint colours dark greys and leaves bright ones`() {
        val dark = calibrate(Calibration(shadowsTint = 1f), TestImages.solid(40)).pixels[0]
        assertTrue(Argb.green(dark) < Argb.red(dark) && Argb.green(dark) < Argb.blue(dark), "dark grey should go magenta: ${Integer.toHexString(dark)}")
        val bright = calibrate(Calibration(shadowsTint = 1f), TestImages.solid(230)).pixels[0]
        assertTrue(chroma(bright) <= 1)
    }

    @Test
    fun `calibration stage is skipped when neutral`() {
        assertFalse(CalibrationStage().isEnabled(contextFor()))
        assertTrue(CalibrationStage().isEnabled(contextFor(planOverride = { it.copy(calibration = Calibration(greenHue = 0.1f)) })))
    }

    // --- Defringe ---

    /** Black | 2 px purple fringe | white, plus a separate flat purple patch with no edge. */
    private fun fringedEdge(): PixelBuffer {
        val w = 80
        val h = 40
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                y >= 30 && x >= 60 -> Argb.opaque(150, 70, 190)
                x < 30 -> Argb.opaque(10, 10, 10)
                x < 32 -> Argb.opaque(160, 60, 200)
                else -> Argb.opaque(245, 245, 245)
            }
        }
        return PixelBuffer(w, h, pixels)
    }

    private fun defringe(purple: Float, green: Float, image: PixelBuffer): PixelBuffer {
        val manual = ManualAdjustments.of(ManualControl.DEFRINGE_PURPLE to purple, ManualControl.DEFRINGE_GREEN to green)
        val context = contextFor(planOverride = { ManualAdjustmentMerger.merge(it, manual) })
        return runBlocking { DefringeStage().execute(image.copy(), context) }
    }

    @Test
    fun `purple defringe removes purple at edges but not purple surfaces`() {
        val image = fringedEdge()
        val out = defringe(1f, 0f, image)
        val fringe = 10 * 80 + 30
        val patch = 35 * 80 + 70
        assertTrue(chroma(out.pixels[fringe]) < chroma(image.pixels[fringe]) / 3, "fringe ${Integer.toHexString(out.pixels[fringe])}")
        assertEquals(image.pixels[patch], out.pixels[patch], "a purple surface away from edges keeps its colour")
        val greenOnly = defringe(0f, 1f, image)
        assertEquals(image.pixels[fringe], greenOnly.pixels[fringe], "green defringe leaves purple alone")
    }

    @Test
    fun `defringe keeps brightness of the fringe pixel`() {
        val image = fringedEdge()
        val out = defringe(1f, 0f, image)
        val fringe = 10 * 80 + 31
        assertEquals(Luma.ofPixel(image.pixels[fringe]), Luma.ofPixel(out.pixels[fringe]), 0.02f)
    }

    // --- As shot white balance ---

    @Test
    fun `as shot turns off only the automatic white balance`() = runTest {
        val useCase = useCaseFor(mapOf("cast" to GoldenScenario.COLOR_CAST.render(160, 120)))
        val session = (useCase.open("cast") as OperationResult.Success).value
        val auto = (useCase.enhance(session, EnhanceRequest(0.5f)) as OperationResult.Success).value
        assertTrue(auto.plan.whiteBalance.enabled, "the cast photo should get an automatic correction")
        val asShot = (useCase.enhance(session, EnhanceRequest(0.5f, autoWhiteBalance = false)) as OperationResult.Success).value
        assertFalse(asShot.plan.whiteBalance.enabled)
        assertTrue(asShot.plan.exposure == auto.plan.exposure, "other corrections are unchanged")
        val manualTemperature = EnhanceRequest(0.5f, ManualAdjustments.of(ManualControl.TEMPERATURE to -0.3f), autoWhiteBalance = false)
        val cooled = (useCase.enhance(session, manualTemperature) as OperationResult.Success).value
        assertTrue(cooled.plan.temperature.enabled, "manual temperature still applies in As shot")
    }

    // --- Export: border, WebP, watermark, names ---

    @Test
    fun `sized export with a border keeps the requested long edge and frames the photo`() = runTest {
        val saver = RecordingImageSaver()
        val useCase = useCaseFor(mapOf("p" to GoldenScenario.ALREADY_GOOD.render(1600, 1200)), saver)
        val session = (useCase.open("p") as OperationResult.Success).value
        val options = ExportOptions(size = ExportSize.SMALL, border = Border(0.05f, Border.BLACK))
        val estimate = useCase.estimateExportSize(session, EnhanceRequest(0.5f), options)
        val result = (useCase.export(session, EnhanceRequest(0.5f), options) as OperationResult.Success).value
        val image = saver.saved.single().second
        assertEquals(ExportSize.SMALL.longEdge!!, max(image.width, image.height), 2)
        assertEquals(estimate, image.width to image.height)
        assertEquals(result.width to result.height, image.width to image.height)
        assertEquals(Border.BLACK, image.pixels[0])
        assertEquals(Border.BLACK, image.pixels[image.pixelCount - 1])
        assertNotEquals(Border.BLACK, image.pixels[(image.height / 2) * image.width + image.width / 2])
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, was $actual")

    @Test
    fun `border ops add an even frame`() {
        val framed = BorderOps.apply(TestImages.solid(100, 100, 50), Border(0.1f))
        assertEquals(120 to 70, framed.width to framed.height)
        assertEquals(Border.WHITE, framed.pixels[0])
        assertEquals(TestImages.solid(100).pixels[0], framed.pixels[10 * 120 + 10])
        assertEquals(1000, BorderOps.contentLongEdge(1000, Border.NONE))
        assertEquals(833, BorderOps.contentLongEdge(1000, Border(0.1f)))
    }

    @Test
    fun `webp exports get the right type and name`() = runTest {
        val saver = RecordingImageSaver()
        val useCase = useCaseFor(mapOf("p" to TestImages.ramp(0, 255, 120, 80)), saver)
        val session = (useCase.open("p") as OperationResult.Success).value
        useCase.export(session, EnhanceRequest(0.5f), ExportOptions(format = ExportFormat.WEBP))
        val request = saver.saved.single().first
        assertEquals("image/webp", request.mimeType)
        assertTrue(request.displayName.endsWith("_enhanced.webp"), request.displayName)
    }

    @Test
    fun `watermark decorator runs last on the final image and only with text`() = runTest {
        val calls = mutableListOf<Pair<Int, Int>>()
        val decorator = ExportDecorator { image, options ->
            calls += image.width to image.height
            assertEquals("© Pixels", options.watermark.text)
            PixelBuffer(image.width, image.height, IntArray(image.pixelCount) { Argb.opaque(1, 2, 3) })
        }
        val saver = RecordingImageSaver()
        val useCase = useCaseFor(mapOf("p" to TestImages.ramp(0, 255, 300, 200)), saver, decorator)
        val session = (useCase.open("p") as OperationResult.Success).value
        useCase.export(session, EnhanceRequest(0.5f), ExportOptions(border = Border(0.05f)))
        assertTrue(calls.isEmpty(), "no text, no decoration")
        val watermark = Watermark("  © Pixels\u0007 ", WatermarkPosition.TOP_LEFT)
        useCase.export(session, EnhanceRequest(0.5f), ExportOptions(border = Border(0.05f), watermark = watermark))
        val saved = saver.saved.last().second
        assertEquals(listOf(saved.width to saved.height), calls)
        assertEquals(Argb.opaque(1, 2, 3), saved.pixels[0])
    }

    @Test
    fun `watermark text is sanitised and limited`() {
        assertEquals("abc", Watermark("\u0000 abc\n").sanitizedText())
        assertEquals(Watermark.MAX_LENGTH, Watermark("x".repeat(500)).sanitizedText().length)
        assertTrue(Watermark("   ").isNone)
        assertEquals(Watermark.MAX_SIZE, Watermark("a", size = 9f).clamped().size)
    }

    @Test
    fun `output names are sanitised`() {
        assertEquals("IMG_1234_enhanced.jpg", OutputNaming.enhancedName("IMG_1234.JPG".replace("JPG", "jpg")))
        assertEquals("photo.final_enhanced.png", OutputNaming.enhancedName("photo.final.jpg", "png"))
        assertEquals("c_enhanced.jpg", OutputNaming.enhancedName("a/b\\c.png"))
        assertEquals("evil_name_enhanced.jpg", OutputNaming.enhancedName("../../evil\u0000name.jpg"))
        assertEquals("IMG_enhanced.jpg", OutputNaming.enhancedName(".hidden"))
        assertEquals("IMG_enhanced.jpg", OutputNaming.enhancedName(null))
        assertEquals("IMG_enhanced.jpg", OutputNaming.enhancedName("***.jpg"))
        assertEquals("照片_enhanced.jpg", OutputNaming.enhancedName("照片.jpg"))
        assertEquals(OutputNaming.MAX_BASE_LENGTH, OutputNaming.safeBaseName("a".repeat(400) + ".jpg").length)
    }

    @Test
    fun `export options survive their codec and bad input falls back to defaults`() {
        val options = ExportOptions(
            format = ExportFormat.WEBP, quality = 80, size = ExportSize.MEDIUM,
            border = Border(0.04f, Border.BLACK), watermark = Watermark("me", WatermarkPosition.CENTER, 0.05f, 0.5f),
        )
        assertEquals(options, ExportOptionsCodec.decode(ExportOptionsCodec.encode(options)))
        assertEquals(ExportOptions(), ExportOptionsCodec.decode("{not json"))
        assertEquals(ExportOptions(), ExportOptionsCodec.decode(null))
        assertEquals(ExportOptions.MAX_QUALITY, ExportOptionsCodec.decode("""{"quality": 900}""").quality)
    }

    // --- Curves, history, diff ---

    @Test
    fun `curve presets are valid, monotone and ordered by contrast`() {
        CurvePreset.entries.forEach { preset ->
            val curve = preset.points
            var previous = -1f
            for (i in 0..100) {
                val v = curve.valueAt(i / 100f)
                assertTrue(v >= previous - 1e-6f, "${preset.label} not monotone at $i")
                previous = v
            }
        }
        assertTrue(CurvePreset.LINEAR.points.isIdentity)
        assertTrue(CurvePreset.STRONG_CONTRAST.points.valueAt(0.25f) < CurvePreset.MEDIUM_CONTRAST.points.valueAt(0.25f))
        assertTrue(CurvePreset.MEDIUM_CONTRAST.points.valueAt(0.25f) < 0.25f)
        assertTrue(CurvePreset.FADED.points.valueAt(0f) > 0.05f)
    }

    @Test
    fun `history timeline can jump to any step`() {
        val a = EditState(strength = 0.1f)
        val b = a.copy(manual = ManualAdjustments.of(ManualControl.EXPOSURE to 0.3f))
        val c = b.copy(geometry = Geometry(crop = CropRect.of(0.1f, 0.1f, 0.9f, 0.9f)))
        val history = EditHistory(EditState())
        history.commit(a)
        history.commit(b)
        history.commit(c)
        assertEquals(listOf(EditState(), a, b, c), history.timeline)
        assertEquals(3, history.position)
        assertEquals(a, history.jumpTo(1))
        assertEquals(listOf(EditState(), a, b, c), history.timeline, "jumping keeps every step")
        assertEquals(c, history.jumpTo(3))
        assertFailsWith<IllegalArgumentException> { history.jumpTo(4) }
    }

    @Test
    fun `edit diff names what changed`() {
        val base = EditState()
        assertEquals("No change", EditDiff.describe(base, base))
        assertEquals("Exposure", EditDiff.describe(base, base.copy(manual = ManualAdjustments.of(ManualControl.EXPOSURE to 0.2f))))
        assertEquals("Crop", EditDiff.describe(base, base.copy(geometry = Geometry(crop = CropRect.of(0f, 0f, 0.5f, 1f)))))
        assertEquals("Rotate", EditDiff.describe(base, base.copy(geometry = Geometry.NONE.rotatedClockwise())))
        assertEquals("Black & white", EditDiff.describe(base, base.copy(colorGrading = ColorGrading(monochrome = true))))
        assertEquals("Mask added", EditDiff.describe(base, base.copy(localAdjustments = LocalAdjustments(listOf(LocalAdjustment.linearTop(1))))))
        assertEquals("Spot added", EditDiff.describe(base, base.copy(retouch = Retouch(listOf(RetouchSpot(1, 0.5f, 0.5f, 0.2f, 0.2f))))))
        val many = base.copy(manual = ManualAdjustments.of(ManualControl.EXPOSURE to 0.2f, ManualControl.CONTRAST to 0.1f, ManualControl.SHADOWS to 0.3f, ManualControl.GRAIN to 0.2f))
        assertEquals("Exposure, Contrast +2", EditDiff.describe(base, many))
        assertEquals("As shot white balance", EditDiff.describe(base, base.copy(autoWhiteBalance = false)))
        assertEquals("Calibration", EditDiff.describe(base, base.copy(calibration = Calibration(redHue = 0.2f))))
    }

    // --- Presets, paste ---

    @Test
    fun `preset amount zero gives back the previous edit exactly`() {
        val before = EditState(
            manual = ManualAdjustments.of(ManualControl.EXPOSURE to 0.4f, ManualControl.GRAIN to 0.3f),
            colorMixer = com.pixels.enhancer.domain.planning.ColorMixer.NONE.with(HueBand.BLUE, HslShift(0.2f, 0f, 0f)),
            colorGrading = ColorGrading(shadows = GradeWheel(200f, 0.4f)),
        )
        val preset = Preset("p", "P", "Test", EditState(manual = ManualAdjustments.of(ManualControl.CONTRAST to 0.5f), colorGrading = ColorGrading(monochrome = true)))
        assertEquals(before, PresetMath.apply(before, preset, 0f))
        val full = PresetMath.apply(before, preset, 1f)
        assertEquals(0.5f, full.manual[ManualControl.CONTRAST])
        assertEquals(0f, full.manual[ManualControl.EXPOSURE], "a preset sets every Light slider")
        assertEquals(0.3f, full.manual[ManualControl.GRAIN], "groups the preset doesn't use are kept")
        assertTrue(full.colorGrading.monochrome)
        val half = PresetMath.apply(before, preset, 0.5f)
        assertEquals(0.2f, half.manual[ManualControl.EXPOSURE], 1e-6f)
        assertEquals(0.25f, half.manual[ManualControl.CONTRAST], 1e-6f)
        val double = PresetMath.apply(before, preset, 2f)
        assertEquals(ManualControl.CONTRAST.max, double.manual[ManualControl.CONTRAST])
    }

    @Test
    fun `paste carries calibration and white balance mode only when asked`() {
        val from = EditState(calibration = Calibration(blueHue = 0.5f), autoWhiteBalance = false)
        val to = EditState()
        val none = PresetMath.paste(from, to, setOf(SettingsGroup.LIGHT))
        assertEquals(Calibration.NONE, none.calibration)
        assertTrue(none.autoWhiteBalance)
        val all = PresetMath.paste(from, to, SettingsGroup.ALL)
        assertEquals(from.calibration, all.calibration)
        assertFalse(all.autoWhiteBalance)
    }

    // --- Requests, codec ---

    @Test
    fun `toRequest maps every edit field`() {
        val edit = EditState(
            strength = 0.3f,
            manual = ManualAdjustments.of(ManualControl.TEXTURE to 0.2f),
            geometry = Geometry.NONE.rotatedClockwise(),
            colorMixer = com.pixels.enhancer.domain.planning.ColorMixer.NONE.with(HueBand.RED, HslShift(0.1f, 0f, 0f)),
            toneCurves = com.pixels.enhancer.domain.planning.ToneCurves.NONE.with(
                com.pixels.enhancer.domain.planning.CurveChannel.MASTER,
                CurvePreset.FADED.points,
            ),
            localAdjustments = LocalAdjustments(listOf(LocalAdjustment.linearTop(1).copy(exposure = 0.2f))),
            colorGrading = ColorGrading(monochrome = true),
            calibration = Calibration(greenHue = 0.2f),
            autoWhiteBalance = false,
            retouch = Retouch(listOf(RetouchSpot(1, 0.4f, 0.4f, 0.6f, 0.6f))),
            sceneOverride = com.pixels.enhancer.domain.analysis.SceneType.entries.last(),
        )
        val request = edit.toRequest(RenderTarget.PREVIEW)
        assertEquals(
            EnhanceRequest(
                strength = edit.strength, manual = edit.manual, geometry = edit.geometry, colorMixer = edit.colorMixer,
                toneCurves = edit.toneCurves, localAdjustments = edit.localAdjustments, colorGrading = edit.colorGrading,
                calibration = edit.calibration, autoWhiteBalance = false, retouch = edit.retouch,
                sceneOverride = edit.sceneOverride, target = RenderTarget.PREVIEW,
            ),
            request,
        )
        val roundTrip = ProjectCodec.decode(ProjectCodec.encode(Project("id-1", "s", null, 1, 2, edit))).edit
        assertEquals(edit, roundTrip)
    }

    // --- Projects and masks ---

    @Test
    fun `projects can be duplicated and renamed`() = runTest {
        var id = 0
        val manager = ProjectManager(
            FileProjectStore(Files.createTempDirectory("projects").toFile(), dispatcher = Dispatchers.Unconfined),
            clock = WallClock { 1000L },
            newId = { "p-${++id}" },
        )
        val source = com.pixels.enhancer.domain.model.ImageSource("content://x", "Beach.jpg", "image/jpeg", 10, 10, 0, false)
        val edit = EditState(manual = ManualAdjustments.of(ManualControl.EXPOSURE to 0.3f))
        val original = manager.startOrResume(source, edit, ExportOptions())
        val copy = manager.duplicate(original.id)!!
        assertNotEquals(original.id, copy.id)
        assertEquals("Beach.jpg (copy)", copy.displayName)
        assertEquals(original.edit, copy.edit)
        assertTrue(copy.undo.isEmpty())
        assertEquals(2, manager.recent().size)
        assertEquals("Sunset", manager.rename(copy.id, "  Sun\u0000set ")!!.displayName)
        assertEquals("Sunset", manager.rename(copy.id, "   ")!!.displayName, "a blank name is ignored")
        assertNull(manager.duplicate("missing"))
    }

    @Test
    fun `masks can be duplicated next to the original and renamed`() {
        val masks = LocalAdjustments(listOf(LocalAdjustment.linearTop(1).copy(exposure = 0.3f), LocalAdjustment.brush(2)))
        val duplicated = masks.duplicate(1)
        assertEquals(listOf(1, 3, 2), duplicated.items.map { it.id })
        assertEquals("Mask 1 copy", duplicated.items[1].name)
        assertEquals(0.3f, duplicated.items[1].exposure)
        assertEquals(masks, masks.duplicate(99))
        val full = LocalAdjustments((1..LocalAdjustments.MAX_ITEMS).map { LocalAdjustment.brush(it) })
        assertEquals(full, full.duplicate(1), "no copy past the limit")
        assertEquals("Sky", masks.renamed(1, " Sky\n").items[0].name)
    }

    // --- Batch ---

    @Test
    fun `batch export applies the chosen settings to every photo and survives failures`() = runTest {
        val saver = RecordingImageSaver()
        val images = mapOf("a" to TestImages.ramp(0, 255, 160, 120), "b" to GoldenScenario.UNDEREXPOSED.render(160, 120))
        val useCase = useCaseFor(images, saver)
        val projects = ProjectManager(FileProjectStore(Files.createTempDirectory("batch").toFile(), dispatcher = Dispatchers.Unconfined))
        val batch = BatchExportUseCase(useCase, projects)
        val settings = EditState(
            strength = 0.4f,
            manual = ManualAdjustments.of(ManualControl.CONTRAST to 0.3f, ManualControl.GRAIN to 0.2f),
            geometry = Geometry(crop = CropRect.of(0f, 0f, 0.5f, 0.5f)),
        )
        val progress = mutableListOf<Int>()
        val results = batch.run(listOf("a", "missing", "b"), settings, setOf(SettingsGroup.LIGHT), ExportOptions(size = ExportSize.SMALL)) { done, _ -> progress += done }
        assertEquals(listOf(0, 1, 2, 3), progress)
        assertTrue(results[0] is BatchItemResult.Saved)
        assertEquals(ErrorCode.IMAGE_NOT_FOUND, (results[1] as BatchItemResult.Failed).code)
        assertTrue(results[2] is BatchItemResult.Saved)
        assertEquals(2, saver.saved.size)
        assertEquals(160 to 120, saver.saved[0].second.let { it.width to it.height }, "crop is never copied in a batch")
        val recorded = projects.recent()
        assertEquals(2, recorded.size)
        recorded.forEach { project ->
            assertEquals(0.3f, project.edit.manual[ManualControl.CONTRAST])
            assertEquals(0f, project.edit.manual[ManualControl.GRAIN], "Effects were not selected")
            assertEquals(project.edit, project.lastExportedEdit)
        }
        assertFailsWith<IllegalArgumentException> {
            batch.run(List(BatchExportUseCase.MAX_ITEMS + 1) { "a" }, settings, SettingsGroup.ALL, ExportOptions())
        }
    }
}
