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

/**
 * Runs an ordered list of stages. Adding a stage means adding it to the list — nothing else changes.
 *
 * Images larger than [tilePixelThreshold] (full-resolution export) are processed in overlapping
 * tiles so the float working planes of denoise/detail never exist at full size.
 */
class PipelineImageProcessor(
    private val stages: List<ProcessingStage>,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val logger: EnhancerLogger = NoOpLogger,
    private val tilePixelThreshold: Int = DEFAULT_TILE_PIXEL_THRESHOLD,
    private val tileSize: Int = DEFAULT_TILE_SIZE,
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
        if (image.pixelCount > tilePixelThreshold) return processTiled(image, context, listener, runUntilStageId)
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

    private suspend fun processTiled(
        image: PixelBuffer,
        context: ProcessingContext,
        listener: ProcessingListener?,
        runUntilStageId: String?,
    ): ProcessedImage {
        val selected = stages.take(runUntilStageId?.let { id -> stageIds.indexOf(id) + 1 } ?: stages.size)
        val skipped = selected.mapNotNull { stage -> skipReason(stage, context)?.let { SkippedStage(stage.id, it) } }
        val active = selected.filter { stage -> skipped.none { it.stageId == stage.id } }
        skipped.forEach { entry -> listener?.onStageSkipped(stages.first { it.id == entry.stageId }, entry.reason) }

        val fullFrame = ImageFrame(image.width, image.height, 0, 0)
        // Stages are chained, so their neighbourhoods add up.
        val margin = active.sumOf { it.margin(context, fullFrame) } + TILE_SAFETY_MARGIN
        val output = IntArray(image.pixelCount)
        val stageMillis = LongArray(active.size)
        val tiles = tileOrigins(image.width, image.height)
        tiles.forEachIndexed { tileIndex, (tileX, tileY) ->
            val coreWidth = minOf(tileSize, image.width - tileX)
            val coreHeight = minOf(tileSize, image.height - tileY)
            val left = maxOf(0, tileX - margin)
            val top = maxOf(0, tileY - margin)
            val right = minOf(image.width, tileX + coreWidth + margin)
            val bottom = minOf(image.height, tileY + coreHeight + margin)
            var tile = extract(image, left, top, right, bottom)
            val tileContext = context.copy(frame = ImageFrame(image.width, image.height, left, top))
            active.forEachIndexed { stageIndex, stage ->
                currentCoroutineContext().ensureActive()
                listener?.onStageStarted(stage, tileIndex * active.size + stageIndex, tiles.size * active.size)
                val input = tile
                val result = clock.measure { stage.execute(input, tileContext) }
                check(result.value.width == input.width && result.value.height == input.height) { "Stage ${stage.id} changed tile dimensions" }
                tile = result.value
                stageMillis[stageIndex] += result.durationMs
            }
            for (row in 0 until coreHeight) {
                System.arraycopy(tile.pixels, (tileY - top + row) * tile.width + (tileX - left), output, (tileY + row) * image.width + tileX, coreWidth)
            }
        }
        active.forEachIndexed { index, stage ->
            listener?.onStageCompleted(stage, stageMillis[index])
            logger.event(
                "STAGE_COMPLETE",
                mapOf("processingId" to context.processingId, "stage" to stage.id, "durationMs" to stageMillis[index], "tiles" to tiles.size),
            )
        }
        return ProcessedImage(
            PixelBuffer(image.width, image.height, output),
            context.processingId,
            active.map { it.id },
            skipped,
            TimingReport(active.mapIndexed { index, stage -> StageTiming(stage.displayName, stageMillis[index]) }),
        )
    }

    private fun tileOrigins(width: Int, height: Int): List<Pair<Int, Int>> =
        (0 until height step tileSize).flatMap { y -> (0 until width step tileSize).map { x -> x to y } }

    private fun extract(image: PixelBuffer, left: Int, top: Int, right: Int, bottom: Int): PixelBuffer {
        val width = right - left
        val pixels = IntArray(width * (bottom - top))
        for (row in top until bottom) System.arraycopy(image.pixels, row * image.width + left, pixels, (row - top) * width, width)
        return PixelBuffer(width, bottom - top, pixels)
    }

    private fun skipReason(stage: ProcessingStage, context: ProcessingContext): String? = when {
        !context.stageConfig(stage.id).enabled -> "Disabled in developer settings"
        !stage.isEnabled(context) -> "No correction planned"
        else -> null
    }

    companion object {
        /** Above ~6 MP the float planes of denoise would cost >150 MB; tile instead. */
        const val DEFAULT_TILE_PIXEL_THRESHOLD = 6_000_000
        const val DEFAULT_TILE_SIZE = 1024

        /** Extra overlap on top of the summed stage margins, against off-by-one surprises at seams. */
        private const val TILE_SAFETY_MARGIN = 8
    }
}
