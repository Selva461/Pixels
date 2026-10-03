package com.pixels.enhancer.domain.planning

import com.pixels.enhancer.domain.analysis.SceneType

/**
 * A preset bundles planner limits and working resolution. New presets (Portrait, Low Light…)
 * are added here without touching the pipeline.
 */
data class QualityPreset(
    val id: String,
    val displayName: String,
    val limits: NaturalLimits,
    /** Long edge of the image the pipeline processes; bounds memory use for huge photos. */
    val maxWorkingLongEdge: Int,
) {
    companion object {
        /** ~4.9 MP at 4:3. Peak memory in denoise is roughly 6 float planes at this size (~120 MB). */
        private const val NATURAL_WORKING_LONG_EDGE = 2560

        val NATURAL = QualityPreset(
            id = "natural",
            displayName = "Natural",
            limits = NaturalLimits(),
            maxWorkingLongEdge = NATURAL_WORKING_LONG_EDGE,
        )

        val ALL: List<QualityPreset> = listOf(NATURAL)

        fun byId(id: String): QualityPreset = ALL.firstOrNull { it.id == id } ?: NATURAL

        /**
         * Scene-aware limits: the same planner with priorities tuned per scene (spec 3.10). Every
         * variant only narrows or redirects corrections; none adds content or aggressive effects.
         */
        fun forScene(scene: SceneType, base: QualityPreset = NATURAL): QualityPreset {
            val l = base.limits
            val limits = when (scene) {
                SceneType.GENERAL -> l
                // Natural skin, restrained highlights, realistic detail.
                SceneType.PORTRAIT -> l.copy(maxSaturationBoost = 0.04f, maxSharpening = 0.2f, maxDetail = 0.06f, maxHighlightRecovery = 0.12f)
                // Balanced sky/foreground, realistic greens and depth.
                SceneType.LANDSCAPE -> l.copy(maxContrastBoost = 0.12f, maxDetail = 0.15f, maxHighlightRecovery = 0.14f)
                SceneType.NATURE -> l.copy(maxDetail = 0.14f, vividSaturationThreshold = 0.3f, maxSaturationBoost = 0.05f)
                // Realistic water and sand; bright scenes are meant to be bright.
                SceneType.BEACH -> l.copy(maxHighlightRecovery = 0.15f, exposureTargetHigh = 0.62f, maxExposureCutEv = 0.3f)
                // Keep it looking like night: limited lift, early noise control, warm light kept.
                SceneType.NIGHT -> l.copy(
                    exposureTargetLow = 0.18f, exposureTarget = 0.24f, maxExposureLiftEv = 0.35f,
                    noiseThreshold = 0.15f, maxSharpening = 0.15f, colorCastThreshold = 0.75f,
                )
                // Restrained shadow recovery, denoise before sharpening.
                SceneType.LOW_LIGHT -> l.copy(noiseThreshold = 0.15f, minNoiseReduction = 0.35f, maxShadowLift = 0.08f, maxSharpening = 0.18f)
                // Mixed light: correct casts more readily.
                SceneType.INDOOR -> l.copy(colorCastThreshold = 0.4f, maxWhiteBalanceCorrection = 0.85f)
                // Accurate colour, restrained warmth and texture.
                SceneType.FOOD -> l.copy(colorCastThreshold = 0.65f, maxWhiteBalanceCorrection = 0.5f, maxSaturationBoost = 0.06f, maxDetail = 0.1f)
                // Neutral colour, balanced contrast.
                SceneType.ARCHITECTURE -> l.copy(colorCastThreshold = 0.4f, maxContrastBoost = 0.08f)
                // Legibility: bright clean paper, strong contrast, neutral, crisp.
                SceneType.DOCUMENT -> l.copy(
                    flatContrastThreshold = 0.8f, maxContrastBoost = 0.14f, exposureTargetLow = 0.55f, exposureTarget = 0.7f,
                    exposureTargetHigh = 0.85f, maxSaturationBoost = 0f, colorCastThreshold = 0.3f, maxWhiteBalanceCorrection = 1f,
                    maxSharpening = 0.45f,
                )
            }
            return base.copy(id = "${base.id}-${scene.name.lowercase()}", displayName = "${base.displayName} · ${scene.label}", limits = limits)
        }
    }
}
