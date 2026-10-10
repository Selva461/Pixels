package com.pixels.enhancer.processing

import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.domain.analysis.SceneType
import com.pixels.enhancer.domain.analysis.StatisticalImageAnalyzer
import com.pixels.enhancer.domain.editing.EditDiff
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.geometry.CropRect
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.geometry.GeometryOps
import com.pixels.enhancer.domain.geometry.LensCorrection
import com.pixels.enhancer.domain.geometry.Perspective
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.local.BrushStroke
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.local.MaskShape
import com.pixels.enhancer.domain.local.RangeMask
import com.pixels.enhancer.domain.planning.Calibration
import com.pixels.enhancer.domain.planning.ColorGrading
import com.pixels.enhancer.domain.planning.ColorMixer
import com.pixels.enhancer.domain.planning.CurveChannel
import com.pixels.enhancer.domain.planning.CurvePreset
import com.pixels.enhancer.domain.planning.GradeWheel
import com.pixels.enhancer.domain.planning.HslShift
import com.pixels.enhancer.domain.planning.HueBand
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.planning.ToneCurves
import com.pixels.enhancer.domain.processing.PipelineImageProcessor
import com.pixels.enhancer.domain.processing.stages.DefaultPipeline
import com.pixels.enhancer.domain.project.EditStateCodec
import com.pixels.enhancer.domain.project.Project
import com.pixels.enhancer.domain.project.ProjectCodec
import com.pixels.enhancer.domain.retouch.Retouch
import com.pixels.enhancer.domain.retouch.RetouchMode
import com.pixels.enhancer.domain.retouch.RetouchSpot
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.usecase.RenderTarget
import com.pixels.enhancer.domain.usecase.toRequest
import com.pixels.enhancer.domain.validation.NaturalOutputValidator
import com.pixels.enhancer.testing.GoldenScenario
import com.pixels.enhancer.testing.InMemoryImageRepository
import com.pixels.enhancer.testing.RecordingImageSaver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

/**
 * Property tests over seeded random edits: whatever combination of tools a user reaches, rendering
 * never throws, fails only with a controlled code, keeps the photo opaque and the promised size,
 * and every edit survives saving and loading unchanged. Seeds are fixed so failures reproduce.
 */
class EditFuzzTest {

    private class EditGenerator(seed: Int) {
        private val random = Random(seed)

        private fun unit() = random.nextFloat() * 2f - 1f
        private fun positive() = random.nextFloat()
        private fun chance(p: Float) = random.nextFloat() < p

        fun edit(): EditState = EditState(
            strength = positive(),
            manual = manual(),
            geometry = geometry(),
            colorMixer = mixer(),
            toneCurves = curves(),
            localAdjustments = masks(),
            colorGrading = grading(),
            retouch = retouch(),
            calibration = if (chance(0.4f)) Calibration(unit(), unit(), unit(), unit(), unit(), unit(), unit()) else Calibration.NONE,
            autoWhiteBalance = chance(0.7f),
            sceneOverride = if (chance(0.3f)) SceneType.entries.random(random) else null,
        )

        private fun manual(): ManualAdjustments = ManualControl.entries.fold(ManualAdjustments.NONE) { acc, control ->
            if (chance(0.35f)) acc.with(control, control.min + positive() * (control.max - control.min)) else acc
        }

        private fun geometry(): Geometry {
            var g = Geometry()
            repeat(random.nextInt(0, 3)) { g = g.rotatedClockwise() }
            if (chance(0.3f)) g = g.flipped()
            if (chance(0.3f)) g = g.straightened(unit() * 20f)
            if (chance(0.4f)) {
                val left = positive() * 0.4f
                val top = positive() * 0.4f
                g = g.copy(crop = CropRect.of(left, top, left + 0.2f + positive() * 0.4f, top + 0.2f + positive() * 0.4f))
            }
            if (chance(0.3f)) g = g.withLens(LensCorrection(unit(), unit(), unit()))
            if (chance(0.3f)) g = g.withPerspective(Perspective(unit(), unit(), unit() * 10f, unit(), unit(), unit(), unit()))
            return g
        }

        private fun mixer(): ColorMixer = HueBand.entries.fold(ColorMixer.NONE) { acc, band ->
            if (chance(0.25f)) acc.with(band, HslShift(unit(), unit(), unit())) else acc
        }

        private fun curves(): ToneCurves = CurveChannel.entries.fold(ToneCurves.NONE) { acc, channel ->
            if (chance(0.25f)) acc.with(channel, CurvePreset.entries.random(random).points) else acc
        }

        private fun grading(): ColorGrading = if (!chance(0.4f)) {
            ColorGrading.NONE
        } else {
            ColorGrading(
                shadows = wheel(), midtones = wheel(), highlights = wheel(), global = wheel(),
                blending = positive(), balance = unit(), monochrome = chance(0.3f),
            ).clamped()
        }

        private fun wheel() = if (chance(0.5f)) GradeWheel(positive() * 359f, positive(), unit()).clamped() else GradeWheel.NONE

        private fun masks(): LocalAdjustments {
            val items = (1..random.nextInt(0, 4)).map { id ->
                val shape = when (random.nextInt(4)) {
                    0 -> MaskShape.Linear(positive(), positive(), positive(), positive())
                    1 -> MaskShape.Radial(positive(), positive(), 0.05f + positive() * 0.5f, 0.05f + positive() * 0.5f, positive())
                    2 -> MaskShape.Full
                    else -> MaskShape.None
                }
                val brush = if (chance(0.4f)) {
                    listOf(BrushStroke(List(6) { positive() }, 0.01f + positive() * 0.1f, positive(), 0.2f + positive() * 0.8f, erase = chance(0.3f)))
                } else {
                    emptyList()
                }
                val range = when (random.nextInt(3)) {
                    0 -> RangeMask.Luminance(positive() * 0.5f, 0.5f + positive() * 0.5f, 0.01f + positive() * 0.3f)
                    1 -> RangeMask.Color(random.nextInt(256), random.nextInt(256), random.nextInt(256), positive())
                    else -> null
                }
                LocalAdjustment(
                    id, shape, chance(0.3f), unit(), unit(), unit(), unit(), unit(), unit(), unit(), unit(), unit(),
                    brush, range, if (chance(0.2f)) "Mask $id" else "",
                ).clamped()
            }
            return LocalAdjustments(items)
        }

        private fun retouch(): Retouch = Retouch(
            (1..random.nextInt(0, 3)).map { id ->
                RetouchSpot(id, positive(), positive(), positive(), positive(), 0.01f + positive() * 0.08f, positive(), positive(), RetouchMode.entries.random(random)).clamped()
            },
        )
    }

    @Test
    fun `random edits always render safely`() = runTest {
        val photo = GoldenScenario.HIGH_CONTRAST.render(96, 64)
        val useCase = EnhanceImageUseCase(
            InMemoryImageRepository(mapOf("p" to photo)), StatisticalImageAnalyzer(), NaturalEnhancementPlanner(),
            PipelineImageProcessor(DefaultPipeline.stages()), NaturalOutputValidator(), RecordingImageSaver(),
            dispatcher = Dispatchers.Unconfined,
        )
        val session = (useCase.open("p") as OperationResult.Success).value
        var rendered = 0
        for (seed in 1..RENDER_CASES) {
            val edit = EditGenerator(seed).edit()
            when (val result = useCase.enhance(session, edit.toRequest(RenderTarget.FULL))) {
                is OperationResult.Failure -> assertEquals(ErrorCode.VALIDATION_FAILED, result.code, "seed $seed: ${result.message}")
                is OperationResult.Success -> {
                    rendered++
                    val out = result.value.output
                    val expected = GeometryOps.outputSize(session.original.width, session.original.height, edit.geometry)
                    assertEquals(expected, out.width to out.height, "seed $seed size")
                    assertTrue(out.pixels.all { Argb.alpha(it) == Argb.OPAQUE_ALPHA }, "seed $seed lost opacity")
                }
            }
        }
        assertTrue(rendered > RENDER_CASES * 3 / 4, "most random edits should render; only $rendered of $RENDER_CASES did")
    }

    @Test
    fun `random edits survive saving and loading`() {
        for (seed in 1..CODEC_CASES) {
            val edit = EditGenerator(seed + CODEC_SEED_OFFSET).edit()
            assertEquals(edit, EditStateCodec.decode(EditStateCodec.encode(edit)), "seed $seed edit codec")
            val project = Project("id-$seed", "content://$seed", "Photo $seed", seed.toLong(), seed + 1L, edit, undo = listOf(EditState()))
            assertEquals(project, ProjectCodec.decode(ProjectCodec.encode(project)), "seed $seed project codec")
        }
    }

    @Test
    fun `every random change has a history label`() {
        for (seed in 1..CODEC_CASES) {
            val before = EditGenerator(seed).edit()
            val after = EditGenerator(seed + 1).edit()
            val label = EditDiff.describe(before, after)
            assertTrue(label.isNotBlank())
            if (before != after) assertTrue(label != "No change", "seed $seed: a real change needs a name")
        }
    }

    private companion object {
        const val RENDER_CASES = 60
        const val CODEC_CASES = 200
        const val CODEC_SEED_OFFSET = 10_000
    }
}
