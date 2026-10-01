package com.pixels.enhancer.domain.analysis

import com.pixels.enhancer.domain.image.PixelBuffer

interface ImageAnalyzer {
    suspend fun analyze(image: PixelBuffer): ImageAnalysis
}
