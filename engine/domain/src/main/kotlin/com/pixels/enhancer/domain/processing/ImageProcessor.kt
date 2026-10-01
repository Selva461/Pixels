package com.pixels.enhancer.domain.processing

import com.pixels.enhancer.core.timing.TimingReport
import com.pixels.enhancer.domain.image.PixelBuffer

interface ImageProcessor {
    /** Stage IDs in execution order — used by the debug screen for toggles and "run until". */
    val stageIds: List<String>

    /**
     * Runs the pipeline on a copy of [image]; the input is never modified.
     *
     * @param runUntilStageId when set, stops after that stage so its output can be inspected.
     */
    suspend fun process(
        image: PixelBuffer,
        context: ProcessingContext,
        listener: ProcessingListener? = null,
        runUntilStageId: String? = null,
    ): ProcessedImage
}

data class SkippedStage(val stageId: String, val reason: String)

data class ProcessedImage(
    val image: PixelBuffer,
    val processingId: String,
    val executedStages: List<String>,
    val skippedStages: List<SkippedStage>,
    val stageTimings: TimingReport,
)
