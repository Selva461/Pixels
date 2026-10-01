package com.pixels.enhancer.testing

import com.pixels.enhancer.domain.image.PixelBuffer

/** The golden test set from the spec, generated from the synthetic scene so no binary assets are needed. */
enum class GoldenScenario(val fileStem: String) {
    UNDEREXPOSED("01_underexposed"),
    OVEREXPOSED("02_overexposed"),
    LOW_LIGHT_NOISE("03_low_light_noise"),
    COLOR_CAST("04_color_cast"),
    SOFT_FOCUS("05_soft_focus"),
    HIGH_CONTRAST("06_high_contrast"),
    HAZY("07_hazy"),
    OVERSATURATED("08_oversaturated"),
    ALREADY_GOOD("10_already_good"),
    ;

    fun render(width: Int = 640, height: Int = 480): PixelBuffer {
        val clean = SyntheticScenes.natural(width, height)
        return when (this) {
            UNDEREXPOSED -> Degradations.exposure(clean, linearGain = 0.4f)
            OVEREXPOSED -> Degradations.exposure(clean, linearGain = 2.0f)
            LOW_LIGHT_NOISE -> Degradations.noise(Degradations.exposure(clean, linearGain = 0.45f), lumaSigma = 5f, chromaSigma = 4f)
            COLOR_CAST -> Degradations.channelGains(clean, red = 1.15f, green = 1.0f, blue = 0.62f)
            SOFT_FOCUS -> Degradations.blur(clean, radius = 2)
            HIGH_CONTRAST -> Degradations.harshContrast(clean, gain = 1.7f)
            HAZY -> Degradations.haze(clean, amount = 0.4f)
            OVERSATURATED -> Degradations.saturation(clean, factor = 1.9f)
            ALREADY_GOOD -> clean
        }
    }
}
