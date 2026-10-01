package com.pixels.enhancer.domain.processing

import com.pixels.enhancer.domain.image.PixelBuffer

/**
 * One independently testable step of the pipeline.
 *
 * Stages may modify [input] in place and return it: the pipeline hands them a private working
 * copy, so in-place edits avoid an extra full-resolution allocation per stage.
 */
interface ProcessingStage {
    val id: String
    val displayName: String

    /** Whether the plan asks this stage to do anything; a stage with nothing to do is skipped. */
    fun isEnabled(context: ProcessingContext): Boolean

    suspend fun execute(input: PixelBuffer, context: ProcessingContext): PixelBuffer
}

/** Per-stage developer override. [intensity] multiplies the planned amount. */
data class StageConfig(
    val enabled: Boolean = true,
    val intensity: Float = 1f,
) {
    companion object {
        val DEFAULT = StageConfig()
    }
}

interface ProcessingListener {
    fun onStageStarted(stage: ProcessingStage, index: Int, total: Int)
    fun onStageCompleted(stage: ProcessingStage, durationMs: Long)
    fun onStageSkipped(stage: ProcessingStage, reason: String) = Unit
}
