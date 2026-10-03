package com.pixels.enhancer.domain.analysis

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer

/** Per-channel histograms of a (sampled) image, for display behind sliders and curves. */
class Histogram(val red: IntArray, val green: IntArray, val blue: IntArray, val luma: IntArray) {
    val bins: Int get() = luma.size

    /** Largest bin count across channels, for normalising the drawing. */
    val peak: Int get() = maxOf(red.max(), green.max(), blue.max(), luma.max())

    companion object {
        const val DEFAULT_BINS = 64

        fun compute(image: PixelBuffer, bins: Int = DEFAULT_BINS): Histogram {
            require(bins in 2..Argb.CHANNEL_MAX + 1)
            val red = IntArray(bins)
            val green = IntArray(bins)
            val blue = IntArray(bins)
            val luma = IntArray(bins)
            val step = SampleGrid.step(image.pixelCount)
            for (y in 0 until image.height step step) {
                for (x in 0 until image.width step step) {
                    val color = image.pixels[y * image.width + x]
                    red[Argb.red(color) * bins / (Argb.CHANNEL_MAX + 1)]++
                    green[Argb.green(color) * bins / (Argb.CHANNEL_MAX + 1)]++
                    blue[Argb.blue(color) * bins / (Argb.CHANNEL_MAX + 1)]++
                    luma[(Luma.ofPixel(color) * (bins - 1) + 0.5f).toInt()]++
                }
            }
            return Histogram(red, green, blue, luma)
        }
    }
}
