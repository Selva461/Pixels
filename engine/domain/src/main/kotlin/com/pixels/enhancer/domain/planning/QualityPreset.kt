package com.pixels.enhancer.domain.planning

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
    }
}
