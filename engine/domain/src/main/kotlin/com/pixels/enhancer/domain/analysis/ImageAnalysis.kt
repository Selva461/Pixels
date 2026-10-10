package com.pixels.enhancer.domain.analysis

/**
 * Structured result of the quality analysis. All scores are 0..1 unless noted, measured on the
 * working image (the resolution the pipeline will process).
 */
data class ImageAnalysis(
    val width: Int,
    val height: Int,
    /** Mean luma (gamma-encoded). Natural exposures typically sit around 0.40–0.56. */
    val exposureScore: Float,
    /** Luma spread between the 5th and 95th percentile. Hazy/flat images score low. */
    val contrastScore: Float,
    /** Mean chroma (max channel − min channel). */
    val saturationScore: Float,
    /** 1 − Crete blur estimate; high = crisp edges. */
    val sharpnessScore: Float,
    /** Noise sigma normalised to 0..1 (see [AnalysisThresholds.NOISE_SIGMA_FOR_FULL_SCORE]). */
    val noiseScore: Float,
    /** Estimated luma noise standard deviation in 0..1 units. */
    val noiseSigma: Float,
    /** Fraction of pixels at (or within a hair of) white. */
    val highlightClipping: Float,
    /** Fraction of pixels at (or within a hair of) black. */
    val shadowClipping: Float,
    /** Strength of a global colour cast; 0 = neutral. */
    val colorCastScore: Float,
    /** Relative per-channel edge energy of near-neutral pixels (grey-edge) — the white-balance reference. */
    val neutralBalance: ChannelBalance,
    val luminance: LuminancePercentiles,
    /** Faces found by the platform detector (empty when none or unsupported). */
    val faces: List<FaceRegion> = emptyList(),
    /** Mean luma of the detected faces, or null without faces. */
    val faceLuma: Float? = null,
)

data class LuminancePercentiles(
    val p1: Float,
    val p5: Float,
    val p50: Float,
    val p95: Float,
    val p99: Float,
)

/**
 * Relative channel response of the scene (1, 1, 1 = neutral).
 *
 * @property sampleFraction share of sampled pixels usable as a neutral reference; a low value
 * means the white-balance estimate rests on little evidence.
 */
data class ChannelBalance(
    val red: Float,
    val green: Float,
    val blue: Float,
    val sampleFraction: Float,
) {
    companion object {
        val NEUTRAL = ChannelBalance(1f, 1f, 1f, 1f)
    }
}
