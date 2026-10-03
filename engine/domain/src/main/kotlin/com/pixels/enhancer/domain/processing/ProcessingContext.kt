package com.pixels.enhancer.domain.processing

import com.pixels.enhancer.domain.analysis.ImageAnalysis
import com.pixels.enhancer.domain.planning.Adjustment
import com.pixels.enhancer.domain.planning.EnhancementPlan
import com.pixels.enhancer.domain.planning.QualityPreset

data class ProcessingContext(
    val analysis: ImageAnalysis,
    val plan: EnhancementPlan,
    val qualityPreset: QualityPreset,
    val debugEnabled: Boolean,
    val processingId: String,
    val stageConfigs: Map<String, StageConfig> = emptyMap(),
    /** Set when stages run on a tile; null means the input buffer is the whole image. */
    val frame: ImageFrame? = null,
) {
    /** Where [input] sits in the full image — position-dependent stages (vignette, grain) and size-relative radii use this. */
    fun frameOf(input: com.pixels.enhancer.domain.image.PixelBuffer): ImageFrame =
        frame ?: ImageFrame(input.width, input.height, 0, 0)

    fun stageConfig(stageId: String): StageConfig = stageConfigs[stageId] ?: StageConfig.DEFAULT

    /** Planned amount for [adjustment] after the developer intensity override for [stageId]. */
    fun effectiveAmount(stageId: String, adjustment: Adjustment): Float =
        if (adjustment.enabled) adjustment.amount * stageConfig(stageId).intensity else 0f
}

/** The full image size and the offset of the buffer a stage is processing within it. */
data class ImageFrame(val fullWidth: Int, val fullHeight: Int, val offsetX: Int, val offsetY: Int)
