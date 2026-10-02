package com.pixels.enhancer.domain.usecase

import com.pixels.enhancer.core.constants.ENHANCEMENT_ALGORITHM_VERSION
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.core.error.runControlled
import com.pixels.enhancer.core.id.ProcessingIdGenerator
import com.pixels.enhancer.core.logging.EnhancerLogger
import com.pixels.enhancer.core.logging.NoOpLogger
import com.pixels.enhancer.core.timing.MonotonicClock
import com.pixels.enhancer.core.timing.StageTiming
import com.pixels.enhancer.core.timing.SystemMonotonicClock
import com.pixels.enhancer.core.timing.TimingReport
import com.pixels.enhancer.core.timing.measure
import com.pixels.enhancer.domain.analysis.ImageAnalysis
import com.pixels.enhancer.domain.analysis.ImageAnalyzer
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.PixelResampler
import com.pixels.enhancer.domain.model.ImageSource
import com.pixels.enhancer.domain.model.OutputNaming
import com.pixels.enhancer.domain.model.SupportedFormats
import com.pixels.enhancer.domain.planning.EnhancementPlan
import com.pixels.enhancer.domain.planning.EnhancementPlanner
import com.pixels.enhancer.domain.planning.ManualAdjustmentMerger
import com.pixels.enhancer.domain.planning.QualityPreset
import com.pixels.enhancer.domain.processing.ImageProcessor
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingListener
import com.pixels.enhancer.domain.repository.ImageRepository
import com.pixels.enhancer.domain.repository.ImageSaver
import com.pixels.enhancer.domain.repository.SaveRequest
import com.pixels.enhancer.domain.repository.SavedImage
import com.pixels.enhancer.domain.validation.OutputValidator
import com.pixels.enhancer.domain.validation.ValidationMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates open → analyse → plan → process → validate → save. Contains no image maths; it
 * only sequences the components, times them, logs decisions and converts failures into
 * [OperationResult.Failure]. All work runs on [dispatcher], never the caller's thread.
 */
class EnhanceImageUseCase(
    private val imageRepository: ImageRepository,
    private val analyzer: ImageAnalyzer,
    private val planner: EnhancementPlanner,
    private val processor: ImageProcessor,
    private val validator: OutputValidator,
    private val saver: ImageSaver,
    private val logger: EnhancerLogger = NoOpLogger,
    private val idGenerator: ProcessingIdGenerator = ProcessingIdGenerator(),
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val stageIds: List<String> get() = processor.stageIds

    suspend fun open(sourceId: String, preset: QualityPreset = QualityPreset.NATURAL): OperationResult<EnhancementSession> =
        withContext(dispatcher) {
            val source = when (val result = runControlled(ErrorCode.IMAGE_DECODE_FAILED) { imageRepository.readSource(sourceId) }) {
                is OperationResult.Failure -> return@withContext logFailure("OPEN_FAILED", null, result)
                is OperationResult.Success -> result.value
            }
            checkSource(source)?.let { return@withContext logFailure("OPEN_FAILED", null, it) }
            logger.event(
                "SOURCE_OPENED",
                mapOf(
                    "mimeType" to source.mimeType,
                    "width" to source.width,
                    "height" to source.height,
                    "rotation" to source.rotationDegrees,
                ),
            )

            val decoded = runControlled(ErrorCode.IMAGE_DECODE_FAILED) {
                clock.measure { imageRepository.loadWorkingImage(source, preset.maxWorkingLongEdge) }
            }
            if (decoded is OperationResult.Failure) return@withContext logFailure("OPEN_FAILED", null, decoded)
            val working = (decoded as OperationResult.Success).value

            val analyzed = runControlled(ErrorCode.ANALYSIS_FAILED) { clock.measure { analyzer.analyze(working.value) } }
            if (analyzed is OperationResult.Failure) return@withContext logFailure("OPEN_FAILED", null, analyzed)
            val analysis = (analyzed as OperationResult.Success).value
            logAnalysis(analysis.value)

            OperationResult.Success(
                EnhancementSession(
                    source = source,
                    original = working.value,
                    preview = PixelResampler.downscaleToFit(working.value, PREVIEW_LONG_EDGE),
                    analysis = analysis.value,
                    preset = preset,
                    loadTimings = TimingReport(
                        listOf(StageTiming("Decode", working.durationMs), StageTiming("Analyze", analysis.durationMs)),
                    ),
                ),
            )
        }

    suspend fun enhance(
        session: EnhancementSession,
        request: EnhanceRequest,
        listener: ProcessingListener? = null,
    ): OperationResult<EnhancementOutcome> = withContext(dispatcher) {
        val processingId = idGenerator.next()
        logger.event(
            "PROCESS_START",
            mapOf(
                "processingId" to processingId,
                "width" to session.original.width,
                "height" to session.original.height,
                "preset" to session.preset.id,
                "strength" to request.strength,
                "algorithmVersion" to ENHANCEMENT_ALGORITHM_VERSION,
            ),
        )

        val planned = clock.measure {
            ManualAdjustmentMerger.merge(planner.createPlan(session.analysis, request.strength, session.preset), request.manual)
        }
        logPlan(processingId, planned.value)

        val source = sourceFor(session, request.target)
        val context = ProcessingContext(
            analysis = analysisFor(session, source),
            plan = planned.value,
            qualityPreset = session.preset,
            debugEnabled = request.debugEnabled,
            processingId = processingId,
            stageConfigs = request.stageConfigs,
        )
        val processed = when (
            val result = runControlled(ErrorCode.PROCESSING_FAILED) {
                processor.process(source, context, listener, request.runUntilStageId)
            }
        ) {
            is OperationResult.Failure -> return@withContext logFailure("PROCESS_FAILED", processingId, result)
            is OperationResult.Success -> result.value
        }

        val validationMode = if (request.manual.isNeutral) ValidationMode.NATURAL else ValidationMode.STRUCTURAL
        val validated = clock.measure { validator.validate(source, processed.image, validationMode) }
        val validation = validated.value
        logger.event(
            "VALIDATION_COMPLETE",
            mapOf("processingId" to processingId, "passed" to validation.passed, "failures" to validation.failures.joinToString { it.name }),
        )
        if (!validation.passed) {
            val failure = OperationResult.Failure(
                ErrorCode.VALIDATION_FAILED,
                "Output failed validation: ${validation.failures.joinToString { "${it.name} (${it.detail})" }}",
            )
            return@withContext logFailure("PROCESS_FAILED", processingId, failure)
        }

        val timings = session.loadTimings +
            TimingReport(listOf(StageTiming("Plan", planned.durationMs))) +
            processed.stageTimings +
            TimingReport(listOf(StageTiming("Validation", validated.durationMs)))
        logger.event("PROCESS_COMPLETE", mapOf("processingId" to processingId, "totalMs" to timings.totalMs))
        OperationResult.Success(EnhancementOutcome(processingId, request, planned.value, processed, validation, timings))
    }

    /**
     * Renders [request] at full working resolution (the preview is never saved) and stores it as a
     * new file. The original is never overwritten.
     */
    suspend fun save(
        session: EnhancementSession,
        request: EnhanceRequest,
        listener: ProcessingListener? = null,
    ): OperationResult<SavedImage> = withContext(dispatcher) {
        val fullRequest = request.copy(target = RenderTarget.FULL, runUntilStageId = null)
        val outcome = when (val rendered = enhance(session, fullRequest, listener)) {
            is OperationResult.Failure -> return@withContext rendered
            is OperationResult.Success -> rendered.value
        }
        val saveRequest = SaveRequest(displayName = OutputNaming.enhancedName(session.source.displayName))
        when (val result = runControlled(ErrorCode.SAVE_FAILED) { clock.measure { saver.save(outcome.processed.image, saveRequest) } }) {
            is OperationResult.Failure -> logFailure("SAVE_FAILED", outcome.processingId, result)
            is OperationResult.Success -> {
                logger.event("SAVE_COMPLETE", mapOf("processingId" to outcome.processingId, "durationMs" to result.value.durationMs))
                OperationResult.Success(result.value.value)
            }
        }
    }

    /** Renders [request] at full resolution for sharing, without saving. */
    suspend fun render(session: EnhancementSession, request: EnhanceRequest): OperationResult<EnhancementOutcome> =
        enhance(session, request.copy(target = RenderTarget.FULL, runUntilStageId = null))

    private fun sourceFor(session: EnhancementSession, target: RenderTarget) = when (target) {
        RenderTarget.PREVIEW -> session.preview
        RenderTarget.FULL -> session.original
    }

    /**
     * Noise was measured on the working image; area-downscaling to the preview averages it away
     * by roughly the scale factor, so stages that key off noise sigma must use the smaller value.
     */
    private fun analysisFor(session: EnhancementSession, source: PixelBuffer): ImageAnalysis {
        if (source === session.original) return session.analysis
        val scale = source.width.toFloat() / session.original.width
        return session.analysis.copy(noiseSigma = session.analysis.noiseSigma * scale)
    }

    private fun checkSource(source: ImageSource): OperationResult.Failure? = when {
        !SupportedFormats.isSupported(source.mimeType) ->
            OperationResult.Failure(ErrorCode.IMAGE_UNSUPPORTED, "Unsupported image type: ${source.mimeType}")
        source.width <= 0 || source.height <= 0 ->
            OperationResult.Failure(ErrorCode.IMAGE_DECODE_FAILED, "Image has no readable dimensions")
        source.width.toLong() * source.height > SupportedFormats.MAX_SOURCE_PIXELS ->
            OperationResult.Failure(ErrorCode.IMAGE_TOO_LARGE, "Image is ${source.width}x${source.height}")
        else -> null
    }

    private fun logAnalysis(analysis: ImageAnalysis) {
        logger.event(
            "ANALYSIS_COMPLETE",
            mapOf(
                "exposure" to analysis.exposureScore,
                "contrast" to analysis.contrastScore,
                "saturation" to analysis.saturationScore,
                "noise" to analysis.noiseScore,
                "sharpness" to analysis.sharpnessScore,
                "cast" to analysis.colorCastScore,
                "highlightClip" to analysis.highlightClipping,
                "shadowClip" to analysis.shadowClipping,
            ),
        )
    }

    private fun logPlan(processingId: String, plan: EnhancementPlan) {
        val fields = linkedMapOf<String, Any?>("processingId" to processingId)
        plan.entries().forEach { (kind, adjustment) -> fields[kind.name.lowercase()] = adjustment.amount }
        logger.event("PLAN_CREATED", fields)
    }

    private fun logFailure(event: String, processingId: String?, failure: OperationResult.Failure): OperationResult.Failure {
        logger.error(event, mapOf("processingId" to processingId, "code" to failure.code), failure.cause)
        return failure
    }

    private companion object {
        /** Long edge of the live-preview image; small enough that a slider move re-renders quickly on a phone. */
        const val PREVIEW_LONG_EDGE = 1280
    }
}
