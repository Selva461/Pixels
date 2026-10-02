package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.processing.ProcessingStage

object StageIds {
    const val EXPOSURE = "exposure"
    const val WHITE_BALANCE = "white_balance"
    const val TONE = "tone"
    const val NOISE_REDUCTION = "noise_reduction"
    const val DETAIL = "detail"
    const val SHARPEN = "sharpen"
    const val COLOR_FINISH = "color_finish"
    const val VIGNETTE = "vignette"
    const val GRAIN = "grain"
}

object DefaultPipeline {
    /**
     * Order matters: colour cast is fixed before saturation is touched, and denoising runs before
     * detail and sharpening so they never amplify noise. Creative vignette and grain come last
     * so grain is never smoothed or sharpened.
     */
    fun stages(): List<ProcessingStage> = listOf(
        ExposureStage(),
        WhiteBalanceStage(),
        ToneStage(),
        NoiseReductionStage(),
        DetailStage(),
        SharpenStage(),
        ColorFinishStage(),
        VignetteStage(),
        GrainStage(),
    )
}
