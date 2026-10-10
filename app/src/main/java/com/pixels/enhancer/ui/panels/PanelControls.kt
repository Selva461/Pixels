package com.pixels.enhancer.ui.panels

import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.geometry.LensCorrection
import com.pixels.enhancer.domain.planning.Calibration
import com.pixels.enhancer.domain.planning.ColorGrading
import com.pixels.enhancer.domain.planning.ColorMixer
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.planning.ToneCurves

/**
 * Which sliders each editor panel shows — the single source for drawing the panels, the panel
 * Reset buttons and the "edited" dots in the tool strip, so they can never disagree.
 */
object PanelControls {
    val LIGHT_MAIN = listOf(
        ManualControl.EXPOSURE, ManualControl.CONTRAST, ManualControl.HIGHLIGHTS, ManualControl.SHADOWS,
        ManualControl.WHITES, ManualControl.BLACKS,
    )
    val LIGHT_FINE = listOf(ManualControl.BRIGHTNESS, ManualControl.MIDTONES, ManualControl.GAMMA, ManualControl.EXPOSURE_COMPENSATION)
    val LIGHT_PORTRAIT = listOf(ManualControl.FACE_EXPOSURE)
    val LIGHT = LIGHT_MAIN + LIGHT_FINE + LIGHT_PORTRAIT

    val COLOR = listOf(ManualControl.TEMPERATURE, ManualControl.TINT, ManualControl.VIBRANCE, ManualControl.SATURATION, ManualControl.HUE)

    val EFFECTS_PRESENCE = listOf(ManualControl.TEXTURE, ManualControl.CLARITY, ManualControl.DEHAZE)
    val EFFECTS_VIGNETTE = listOf(ManualControl.VIGNETTE, ManualControl.VIGNETTE_MIDPOINT, ManualControl.VIGNETTE_FEATHER, ManualControl.VIGNETTE_ROUNDNESS)
    val EFFECTS_GRAIN = listOf(ManualControl.GRAIN, ManualControl.GRAIN_SIZE, ManualControl.GRAIN_ROUGHNESS)
    val EFFECTS = EFFECTS_PRESENCE + EFFECTS_VIGNETTE + EFFECTS_GRAIN

    val DETAIL_SHARPEN = listOf(ManualControl.SHARPNESS, ManualControl.SHARPEN_RADIUS, ManualControl.SHARPEN_DETAIL, ManualControl.SHARPEN_MASKING)
    val DETAIL_NOISE = listOf(ManualControl.NOISE_REDUCTION, ManualControl.COLOR_NOISE_REDUCTION)
    val DETAIL = DETAIL_SHARPEN + DETAIL_NOISE

    val OPTICS = listOf(ManualControl.DEFRINGE_PURPLE, ManualControl.DEFRINGE_GREEN)

    /** Every slider appears in exactly one panel. */
    val ALL = LIGHT + COLOR + EFFECTS + DETAIL + OPTICS

    fun edited(edit: EditState, controls: List<ManualControl>) = controls.any { edit.manual[it] != 0f }

    fun cleared(manual: ManualAdjustments, controls: List<ManualControl>): ManualAdjustments =
        controls.fold(manual) { acc, control -> acc.with(control, 0f) }
}

/** The panels with a Reset button, and what each resets. Crop and Geometry reset elsewhere. */
enum class PanelReset {
    LIGHT,
    COLOR,
    EFFECTS,
    DETAIL,
    OPTICS,
    ;

    fun reset(edit: EditState): EditState = when (this) {
        LIGHT -> edit.copy(manual = PanelControls.cleared(edit.manual, PanelControls.LIGHT), toneCurves = ToneCurves.NONE)
        COLOR -> edit.copy(
            manual = PanelControls.cleared(edit.manual, PanelControls.COLOR),
            colorMixer = ColorMixer.NONE,
            colorGrading = ColorGrading.NONE,
            calibration = Calibration.NONE,
            autoWhiteBalance = true,
        )
        EFFECTS -> edit.copy(manual = PanelControls.cleared(edit.manual, PanelControls.EFFECTS))
        DETAIL -> edit.copy(manual = PanelControls.cleared(edit.manual, PanelControls.DETAIL))
        OPTICS -> edit.copy(
            manual = PanelControls.cleared(edit.manual, PanelControls.OPTICS),
            geometry = edit.geometry.withLens(LensCorrection.NONE),
        )
    }
}
