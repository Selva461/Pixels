package com.pixels.enhancer.domain.analysis

/** Measurement constants shared by the estimators. Changing any of these changes output: bump the algorithm version. */
object AnalysisThresholds {
    /** Roughly how many pixels each statistic visits; keeps analysis cost flat for large images. */
    const val TARGET_SAMPLE_COUNT = 400_000

    /** 250/255 — anything brighter is treated as clipped white. */
    const val HIGHLIGHT_CLIP_LUMA = 0.98f

    /** 5/255 — anything darker is treated as clipped black. */
    const val SHADOW_CLIP_LUMA = 0.02f

    /** Darker pixels are noise-dominated and unreliable as a white-balance reference. */
    const val NEUTRAL_MIN_LUMA = 0.05f

    /** Coloured objects (foliage, sky) are excluded from the white-balance reference edges. */
    const val NEUTRAL_MAX_CHROMA = 0.30f

    /**
     * Chromaticity distance of the grey-edge estimate from neutral that maps to a cast score
     * of 1. Clean test scenes measure ~0.06; a strong tungsten cast ~0.15.
     */
    const val CAST_DISTANCE_FOR_FULL_SCORE = 0.18f

    /** Luma noise sigma that maps to a noise score of 1 (≈10/255). */
    const val NOISE_SIGMA_FOR_FULL_SCORE = 0.04f

    /** Share of lowest-gradient pixels used for noise estimation, so texture is not counted as noise. */
    const val NOISE_FLAT_REGION_FRACTION = 0.5f

    /** Chroma above which a pixel counts as "extremely saturated" for colour-explosion checks. */
    const val EXTREME_CHROMA = 0.85f
}
