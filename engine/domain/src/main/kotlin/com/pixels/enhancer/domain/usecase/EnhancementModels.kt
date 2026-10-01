package com.pixels.enhancer.domain.usecase

import com.pixels.enhancer.core.timing.TimingReport
import com.pixels.enhancer.domain.analysis.ImageAnalysis
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.model.ImageSource
import com.pixels.enhancer.domain.planning.EnhancementPlan
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
    val analysis: ImageAnalysis,
    val preset: QualityPreset,
    /** Decode and analysis durations. */
    val loadTimings: TimingReport,
)

data class EnhanceRequest(
    val strength: Float,
    val debugEnabled: Boolean = false,
    val stageConfigs: Map<String, StageConfig> = emptyMap(),
    val runUntilStageId: String? = null,
)

data class EnhancementOutcome(
    val processingId: String,
    val request: EnhanceRequest,
    val plan: EnhancementPlan,
    val processed: ProcessedImage,
    val validation: ValidationResult,
    /** Full timing breakdown: decode, analysis, planning, each stage, validation. */
    val timings: TimingReport,
)
