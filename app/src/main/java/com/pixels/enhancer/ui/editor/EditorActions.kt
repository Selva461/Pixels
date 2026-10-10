package com.pixels.enhancer.ui.editor

import android.net.Uri
import com.pixels.enhancer.domain.analysis.SceneType
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.geometry.CropRect
import com.pixels.enhancer.domain.geometry.LensCorrection
import com.pixels.enhancer.domain.geometry.Perspective
import com.pixels.enhancer.domain.local.BrushStroke
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.domain.planning.Calibration
import com.pixels.enhancer.domain.planning.ColorGrading
import com.pixels.enhancer.domain.planning.CurveChannel
import com.pixels.enhancer.domain.planning.CurvePoints
import com.pixels.enhancer.domain.planning.CurvePreset
import com.pixels.enhancer.domain.planning.HslShift
import com.pixels.enhancer.domain.planning.HueBand
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.presets.Preset
import com.pixels.enhancer.domain.presets.SettingsGroup
import com.pixels.enhancer.domain.project.EditVersion
import com.pixels.enhancer.domain.retouch.RetouchSpot
import com.pixels.enhancer.ui.panels.PanelReset

/**
 * Everything the editor screen can ask for. Live changes (`…Changed`) are followed by
 * [onEditFinished] when the gesture ends, which records one undo step.
 */
interface EditorActions {
    fun onCloseRequested()
    fun onClose()
    fun onLeaveDismissed()
    fun onUndo()
    fun onRedo()
    fun onSave()
    fun onShare()
    fun onViewSaved(uri: Uri)
    fun onShowOriginalEdit()
    fun onResetAll()
    fun onEditFinished()

    fun onStrengthChanged(value: Float)
    fun onControlChanged(control: ManualControl, value: Float)
    fun onResetControl(control: ManualControl)
    fun onSceneSelected(scene: SceneType?)

    fun onRotateClockwise()
    fun onRotateCounterClockwise()
    fun onFlip()
    fun onFlipVertical()
    fun onStraightenChanged(degrees: Float)
    fun onCropChanged(crop: CropRect)
    fun onCropAspectSelected(aspect: CropAspect)
    fun onResetGeometry()
    fun onCropModeChanged(enabled: Boolean)
    fun aspectRatioFor(aspect: CropAspect): Float?

    fun onColorMixerChanged(band: HueBand, shift: HslShift)
    fun onResetColorMixer()
    fun onCurveChanged(channel: CurveChannel, points: CurvePoints)
    fun onResetCurve(channel: CurveChannel)
    fun onGradingChanged(grading: ColorGrading)
    fun onMonochromeChanged(enabled: Boolean)
    fun onResetGrading()

    fun onLensChanged(lens: LensCorrection)
    fun onPerspectiveChanged(perspective: Perspective)
    fun onResetLens()
    fun onResetPerspective()

    fun onPresetApplied(preset: Preset)
    fun onPresetAmountChanged(amount: Float)
    fun onSavePreset(name: String)
    fun onDeletePreset(preset: Preset)
    fun onCopySettings()
    fun onPasteSettings(groups: Set<SettingsGroup>)

    fun onSaveVersion(name: String)
    fun onApplyVersion(version: EditVersion)
    fun onDeleteVersion(version: EditVersion)

    fun onAddMask(kind: MaskKind)
    fun onSelectMask(id: Int?)
    fun onLocalChanged(item: LocalAdjustment)
    fun onMaskRemoved(id: Int)
    fun onBrushStroke(stroke: BrushStroke)
    fun onSampleMaskColor(x: Float, y: Float)
    fun onMaskOverlayChanged(show: Boolean)

    fun onAddSpot(x: Float, y: Float)
    fun onSpotChanged(spot: RetouchSpot)
    fun onSelectSpot(id: Int?)
    fun onSpotRemoved(id: Int)
    fun onHealSettingsChanged(settings: HealSettings)

    fun onPickWhiteBalance(x: Float, y: Float)
    fun onAutoStraighten()
    fun onAutoUpright()

    /** Finds the subject, sky and background and sets masks with their own sliders for each. */
    fun onSmartEdit()
    fun onShowClippingChanged(show: Boolean)

    fun onCalibrationChanged(calibration: Calibration)
    fun onResetCalibration()
    fun onAutoWhiteBalanceChanged(enabled: Boolean)
    fun onCurvePresetSelected(channel: CurveChannel, preset: CurvePreset)
    fun onResetPanel(panel: PanelReset)

    fun onJumpToHistory(index: Int)

    fun onDuplicateMask(id: Int)
    fun onRenameMask(id: Int, name: String)

    /** Remembers which settings a batch copies; the photo picker opens next. */
    fun onBatchGroupsChosen(groups: Set<SettingsGroup>)
    fun onCancelBatch()
    fun onDismissBatch()

    fun onExportOptionsChanged(options: ExportOptions)
    fun onExportConfirmed()
    fun onExportDismissed()
    fun onCancelExport()
}
