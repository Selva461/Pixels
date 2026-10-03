package com.pixels.enhancer.domain.usecase

import com.pixels.enhancer.core.timing.TimingReport
import com.pixels.enhancer.domain.analysis.ImageAnalysis
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.model.ImageSource
import com.pixels.enhancer.domain.planning.EnhancementPlan
import com.pixels.enhancer.domain.planning.ManualAdjustments
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
    val preset: QualityPreset,
    /** Decode and analysis durations. */
    val loadTimings: TimingReport,
)

data class EnhanceRequest(
    val strength: Float,
    val manual: ManualAdjustments = ManualAdjustments.NONE,
    val geometry: Geometry = Geometry.NONE,
    val target: RenderTarget = RenderTarget.FULL,
    val debugEnabled: Boolean = false,
    val stageConfigs: Map<String, StageConfig> = emptyMap(),
    val runUntilStageId: String? = null,
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
