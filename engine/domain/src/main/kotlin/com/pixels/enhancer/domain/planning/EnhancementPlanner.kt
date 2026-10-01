package com.pixels.enhancer.domain.planning

import com.pixels.enhancer.domain.analysis.ImageAnalysis

interface EnhancementPlanner {
    fun createPlan(
        analysis: ImageAnalysis,
        userStrength: Float,
        preset: QualityPreset = QualityPreset.NATURAL,
    ): EnhancementPlan
}

object EnhancementStrength {
    const val MIN = 0f
    const val MAX = 1f

    /** The base plan is designed for this strength; the slider scales relative to it. */
    const val NOMINAL = 0.5f

    /** Recommended default (spec: 35–50 %). */
    const val DEFAULT = 0.45f
}
