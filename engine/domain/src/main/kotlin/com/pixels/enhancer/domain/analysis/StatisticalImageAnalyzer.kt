package com.pixels.enhancer.domain.analysis

import com.pixels.enhancer.domain.image.PixelBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Deterministic, statistics-only analyzer. Each metric lives in its own estimator in QualityMetrics.kt. */
class StatisticalImageAnalyzer : ImageAnalyzer {

    override suspend fun analyze(image: PixelBuffer): ImageAnalysis {
        val luminance = LuminanceStatistics.compute(image)
        currentCoroutineContext().ensureActive()
        val saturation = SaturationEstimator.compute(image)
        val cast = ColorCastEstimator.estimate(image)
        currentCoroutineContext().ensureActive()
        val noiseSigma = NoiseEstimator.estimateSigma(image)
        currentCoroutineContext().ensureActive()
        val sharpness = SharpnessEstimator.estimate(image)

        return ImageAnalysis(
            width = image.width,
            height = image.height,
            exposureScore = luminance.mean,
            contrastScore = luminance.percentiles.p95 - luminance.percentiles.p5,
            saturationScore = saturation.meanChroma,
            sharpnessScore = sharpness,
            noiseScore = (noiseSigma / AnalysisThresholds.NOISE_SIGMA_FOR_FULL_SCORE).coerceIn(0f, 1f),
            noiseSigma = noiseSigma,
            highlightClipping = luminance.highlightClipping,
            shadowClipping = luminance.shadowClipping,
            colorCastScore = cast.score,
            neutralBalance = cast.balance,
            luminance = luminance.percentiles,
        )
    }
}
