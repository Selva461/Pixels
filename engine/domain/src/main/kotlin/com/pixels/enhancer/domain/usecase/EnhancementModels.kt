package com.pixels.enhancer.domain.usecase

import com.pixels.enhancer.core.timing.TimingReport
import com.pixels.enhancer.domain.analysis.ImageAnalysis
import com.pixels.enhancer.domain.analysis.SceneEstimate
import com.pixels.enhancer.domain.analysis.SceneType
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.model.ImageSource
import com.pixels.enhancer.domain.planning.ColorMixer
import com.pixels.enhancer.domain.planning.EnhancementPlan
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ToneCurves
import com.pixels.enhancer.domain.planning.ColorGrading
import com.pixels.enhancer.domain.planning.Calibration
import com.pixels.enhancer.domain.retouch.Retouch
import com.pixels.enhancer.domain.planning.QualityPreset
import com.pixels.enhancer.domain.processing.ProcessedImage
import com.pixels.enhancer.domain.processing.StageConfig
import com.pixels.enhancer.domain.validation.ValidationResult

/**
 * A decoded and analysed image. Kept between strength changes so moving the slider only re-plans
 * and re-processes — it never re-decodes or re-analyses.
 */
data class EnhancementSession(
    val source: ImageSource,
    val original: PixelBuffer,
    /** Downscaled copy of [original] for interactive editing; the plan is still decided from the full analysis. */
    val preview: PixelBuffer,
    val analysis: ImageAnalysis,
    /** Detected scene; advisory — [EnhanceRequest.sceneOverride] wins. */
    val scene: SceneEstimate,
    val preset: QualityPreset,
    /** Decode and analysis durations. */
    val loadTimings: TimingReport,
)

data class EnhanceRequest(
    val strength: Float,
    val manual: ManualAdjustments = ManualAdjustments.NONE,
    val geometry: Geometry = Geometry.NONE,
    val colorMixer: ColorMixer = ColorMixer.NONE,
    val toneCurves: ToneCurves = ToneCurves.NONE,
    /** Masked adjustments, applied after geometry in output coordinates. */
    val localAdjustments: LocalAdjustments = LocalAdjustments.NONE,
    val colorGrading: ColorGrading = ColorGrading.NONE,
    val calibration: Calibration = Calibration.NONE,
    /** False keeps the camera's white balance ("As shot"); manual temperature/tint still apply. */
    val autoWhiteBalance: Boolean = true,
    /** Heal/clone spots, applied after geometry and before local masks. */
    val retouch: Retouch = Retouch.NONE,
    /** User's scene choice; null uses the detected scene. */
    val sceneOverride: SceneType? = null,
    val target: RenderTarget = RenderTarget.FULL,
    val debugEnabled: Boolean = false,
    val stageConfigs: Map<String, StageConfig> = emptyMap(),
    val runUntilStageId: String? = null,
)

/** True when the user asked for creative changes, so validation only checks for broken output. */
val EnhanceRequest.hasManualEdits: Boolean
    get() = !manual.isNeutral || !colorMixer.isNeutral || !toneCurves.isIdentity || !localAdjustments.isNeutral ||
        !colorGrading.isNeutral || !retouch.isEmpty || !calibration.isNeutral || !autoWhiteBalance

/**
 * The render request for an edit — the one place every [EditState] field is mapped, so a new
 * edit field can't be silently left out of previews, exports or batches.
 */
fun EditState.toRequest(target: RenderTarget = RenderTarget.FULL): EnhanceRequest = EnhanceRequest(
    strength = strength,
    manual = manual,
    geometry = geometry,
    colorMixer = colorMixer,
    toneCurves = toneCurves,
    localAdjustments = localAdjustments,
    colorGrading = colorGrading,
    calibration = calibration,
    autoWhiteBalance = autoWhiteBalance,
    retouch = retouch,
    sceneOverride = sceneOverride,
    target = target,
)

enum class RenderTarget {
    /** Small image for live slider feedback. */
    PREVIEW,

    /** Working-resolution image, used for saving and sharing. */
    FULL,
}

data class EnhancementOutcome(
    val processingId: String,
    val request: EnhanceRequest,
    val plan: EnhancementPlan,
    val processed: ProcessedImage,
    val validation: ValidationResult,
    /** [processed] with the request's geometry (rotate/flip/straighten/crop) applied — what is shown and saved. */
    val output: PixelBuffer,
    /** The unedited source with the same geometry, for a pixel-aligned before/after comparison. */
    val originalView: PixelBuffer,
    /** Full timing breakdown: decode, analysis, planning, each stage, validation. */
    val timings: TimingReport,
)

data class ExportResult(val saved: com.pixels.enhancer.domain.repository.SavedImage, val width: Int, val height: Int)

fun EnhancementSession.sceneFor(request: EnhanceRequest): SceneType = request.sceneOverride ?: scene.scene
