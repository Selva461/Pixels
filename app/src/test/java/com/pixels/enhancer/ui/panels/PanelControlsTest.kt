package com.pixels.enhancer.ui.panels

import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.geometry.LensCorrection
import com.pixels.enhancer.domain.planning.Calibration
import com.pixels.enhancer.domain.planning.ColorGrading
import com.pixels.enhancer.domain.planning.CurveChannel
import com.pixels.enhancer.domain.planning.CurvePreset
import com.pixels.enhancer.domain.planning.HslShift
import com.pixels.enhancer.domain.planning.HueBand
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ManualControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The panel lists drive drawing, Reset buttons and "edited" dots, so they must cover every control once. */
class PanelControlsTest {

    @Test
    fun everyControlIsInExactlyOnePanel() {
        val counts = PanelControls.ALL.groupingBy { it }.eachCount()
        assertEquals(ManualControl.entries.toSet(), counts.keys)
        assertTrue("controls listed twice: ${counts.filterValues { it > 1 }.keys}", counts.values.all { it == 1 })
    }

    @Test
    fun eachPanelResetClearsItsOwnControlsAndNothingElse() {
        val everything = ManualControl.entries.fold(ManualAdjustments.NONE) { acc, control -> acc.with(control, control.max / 2) }
        val panels = mapOf(
            PanelReset.LIGHT to PanelControls.LIGHT,
            PanelReset.COLOR to PanelControls.COLOR,
            PanelReset.EFFECTS to PanelControls.EFFECTS,
            PanelReset.DETAIL to PanelControls.DETAIL,
            PanelReset.OPTICS to PanelControls.OPTICS,
        )
        panels.forEach { (panel, controls) ->
            val reset = panel.reset(EditState(manual = everything)).manual
            ManualControl.entries.forEach { control ->
                val expected = if (control in controls) 0f else everything[control]
                assertEquals("$panel / $control", expected, reset[control], 0f)
            }
        }
    }

    @Test
    fun colorResetAlsoClearsMixerGradingCalibrationAndWhiteBalanceMode() {
        val edited = EditState(
            colorMixer = EditState().colorMixer.with(HueBand.BLUE, HslShift(0.2f, -0.3f, 0.1f)),
            colorGrading = ColorGrading.NONE.copy(monochrome = true),
            calibration = Calibration(redHue = 0.4f),
            autoWhiteBalance = false,
        )
        val reset = PanelReset.COLOR.reset(edited)
        assertTrue(reset.colorMixer.isNeutral)
        assertTrue(reset.colorGrading.isNeutral)
        assertTrue(reset.calibration.isNeutral)
        assertTrue(reset.autoWhiteBalance)
    }

    @Test
    fun lightResetClearsCurvesAndOpticsResetClearsLensButKeepsCrop() {
        val curved = EditState(toneCurves = EditState().toneCurves.with(CurveChannel.MASTER, CurvePreset.STRONG_CONTRAST.points))
        assertFalse(curved.toneCurves.isIdentity)
        assertTrue(PanelReset.LIGHT.reset(curved).toneCurves.isIdentity)

        val lens = EditState(geometry = EditState().geometry.rotatedClockwise().withLens(LensCorrection(0.3f, 0.2f, 0.1f)))
        val reset = PanelReset.OPTICS.reset(lens)
        assertTrue(reset.geometry.lens.isIdentity)
        assertEquals(1, reset.geometry.quarterTurns)
    }

    @Test
    fun editedReportsOnlyThePanelsThatChanged() {
        val edit = EditState(manual = ManualAdjustments.NONE.with(ManualControl.DEHAZE, 0.2f))
        assertTrue(PanelControls.edited(edit, PanelControls.EFFECTS))
        assertFalse(PanelControls.edited(edit, PanelControls.LIGHT))
        assertFalse(PanelControls.edited(edit, PanelControls.DETAIL))
    }
}
