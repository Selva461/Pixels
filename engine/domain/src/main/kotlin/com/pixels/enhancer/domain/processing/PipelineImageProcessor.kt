package com.pixels.enhancer.domain.processing

import com.pixels.enhancer.core.logging.EnhancerLogger
import com.pixels.enhancer.core.logging.NoOpLogger
import com.pixels.enhancer.core.timing.MonotonicClock
import com.pixels.enhancer.core.timing.StageTiming
import com.pixels.enhancer.core.timing.SystemMonotonicClock
import com.pixels.enhancer.core.timing.TimingReport
import com.pixels.enhancer.core.timing.measure
import com.pixels.enhancer.domain.image.PixelBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Runs an ordered list of stages. Adding a stage means adding it to the list — nothing else changes. */
class PipelineImageProcessor(
    private val stages: List<ProcessingStage>,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val logger: EnhancerLogger = NoOpLogger,
) : ImageProcessor {

    init {
        require(stages.map { it.id }.toSet().size == stages.size) { "Stage IDs must be unique" }
    }

    override val stageIds: List<String> = stages.map { it.id }

    override suspend fun process(
        image: PixelBuffer,
        context: ProcessingContext,
        listener: ProcessingListener?,
        runUntilStageId: String?,
    ): ProcessedImage {
        require(runUntilStageId == null || runUntilStageId in stageIds) { "Unknown stage: $runUntilStageId" }
        var working = image.copy()
        val executed = mutableListOf<String>()
        val skipped = mutableListOf<SkippedStage>()
        val timings = mutableListOf<StageTiming>()

        for ((index, stage) in stages.withIndex()) {
            currentCoroutineContext().ensureActive()
            val skipReason = skipReason(stage, context)
            if (skipReason != null) {
                skipped += SkippedStage(stage.id, skipReason)
                listener?.onStageSkipped(stage, skipReason)
                logger.event("STAGE_SKIPPED", mapOf("processingId" to context.processingId, "stage" to stage.id, "reason" to skipReason))
            } else {
                listener?.onStageStarted(stage, index, stages.size)
                val input = working
                val result = clock.measure { stage.execute(input, context) }
                check(result.value.width == image.width && result.value.height == image.height) {
                    "Stage ${stage.id} changed image dimensions"
                }
                working = result.value
                executed += stage.id
                timings += StageTiming(stage.displayName, result.durationMs)
                listener?.onStageCompleted(stage, result.durationMs)
                logger.event(
                    "STAGE_COMPLETE",
                    mapOf("processingId" to context.processingId, "stage" to stage.id, "durationMs" to result.durationMs),
                )
            }
            if (stage.id == runUntilStageId) break
        }
        return ProcessedImage(working, context.processingId, executed, skipped, TimingReport(timings))
    }

    private fun skipReason(stage: ProcessingStage, context: ProcessingContext): String? = when {
        !context.stageConfig(stage.id).enabled -> "Disabled in developer settings"
        !stage.isEnabled(context) -> "No correction planned"
        else -> null
    }
}
