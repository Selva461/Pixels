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
) {
    fun stageConfig(stageId: String): StageConfig = stageConfigs[stageId] ?: StageConfig.DEFAULT

    /** Planned amount for [adjustment] after the developer intensity override for [stageId]. */
    fun effectiveAmount(stageId: String, adjustment: Adjustment): Float =
        if (adjustment.enabled) adjustment.amount * stageConfig(stageId).intensity else 0f
}
