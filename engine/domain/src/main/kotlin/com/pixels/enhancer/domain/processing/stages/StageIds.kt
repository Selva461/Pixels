package com.pixels.enhancer.domain.processing.stages

import com.pixels.enhancer.domain.processing.ProcessingStage

object StageIds {
    const val EXPOSURE = "exposure"
    const val WHITE_BALANCE = "white_balance"
    const val CALIBRATION = "calibration"
    const val DEFRINGE = "defringe"
    const val DEHAZE = "dehaze"
    const val FACE_EXPOSURE = "face_exposure"
    const val TONE = "tone"
    const val CURVES = "curves"
    const val NOISE_REDUCTION = "noise_reduction"
    const val DETAIL = "detail"
    const val TEXTURE = "texture"
    const val SHARPEN = "sharpen"
    const val COLOR_FINISH = "color_finish"
    const val COLOR_MIXER = "color_mixer"
    const val COLOR_GRADING = "color_grading"
    const val VIGNETTE = "vignette"
    const val GRAIN = "grain"
}

object DefaultPipeline {
    /**
     * Order matters: colour cast is fixed before saturation is touched (calibration re-tunes the
     * primaries right after white balance; lens fringes go before any saturation boost), denoising runs before
     * detail and sharpening so they never amplify noise. Creative vignette and grain come last
     * so grain is never smoothed or sharpened.
     */
    fun stages(): List<ProcessingStage> = listOf(
        ExposureStage(),
        WhiteBalanceStage(),
        CalibrationStage(),
        DefringeStage(),
        DehazeStage(),
        FaceExposureStage(),
        ToneStage(),
        CurvesStage(),
        NoiseReductionStage(),
        DetailStage(),
        TextureStage(),
        SharpenStage(),
        ColorFinishStage(),
        ColorMixerStage(),
        ColorGradingStage(),
        VignetteStage(),
        GrainStage(),
    )
}
