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
import com.pixels.enhancer.domain.analysis.FaceLocator
import com.pixels.enhancer.domain.analysis.FaceMetrics
import com.pixels.enhancer.domain.analysis.ImageAnalysis
import com.pixels.enhancer.domain.analysis.ImageAnalyzer
import com.pixels.enhancer.domain.analysis.SceneClassifier
import com.pixels.enhancer.domain.export.BorderOps
import com.pixels.enhancer.domain.export.ExportDecorator
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.geometry.GeometryOps
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.PixelResampler
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.domain.local.LocalAdjustmentRenderer
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.model.ImageSource
import com.pixels.enhancer.domain.model.OutputNaming
import com.pixels.enhancer.domain.model.SupportedFormats
import com.pixels.enhancer.domain.planning.Adjustment
import com.pixels.enhancer.domain.planning.EnhancementPlan
import com.pixels.enhancer.domain.planning.EnhancementPlanner
import com.pixels.enhancer.domain.planning.ManualAdjustmentMerger
import com.pixels.enhancer.domain.planning.QualityPreset
import com.pixels.enhancer.domain.processing.ImageProcessor
import com.pixels.enhancer.domain.processing.ProcessingContext
import com.pixels.enhancer.domain.processing.ProcessingListener
import com.pixels.enhancer.domain.regions.SmartEdit
import com.pixels.enhancer.domain.regions.SubjectSegmenter
import com.pixels.enhancer.domain.repository.ImageRepository
import com.pixels.enhancer.domain.repository.ImageSaver
import com.pixels.enhancer.domain.repository.SaveRequest
import com.pixels.enhancer.domain.repository.SavedImage
import com.pixels.enhancer.domain.retouch.RetouchRenderer
import com.pixels.enhancer.domain.validation.OutputValidator
import com.pixels.enhancer.domain.validation.ValidationMode
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
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
    /** Platform face detection for portrait exposure; none by default. */
    private val faceLocator: FaceLocator = FaceLocator.NONE,
    /** Platform drawing on finished exports (watermark text); none by default. */
    private val exportDecorator: ExportDecorator = ExportDecorator.NONE,
    /** Platform people segmentation for Smart edit; none by default (rules find the subject). */
    private val subjectSegmenter: SubjectSegmenter = SubjectSegmenter.NONE,
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
            val measured = (analyzed as OperationResult.Success).value
            val faces = runControlled(ErrorCode.ANALYSIS_FAILED) { faceLocator.locate(working.value) }
                .let { if (it is OperationResult.Success) it.value else emptyList() }
            val analysis = measured.copy(
                value = measured.value.copy(faces = faces, faceLuma = FaceMetrics.meanLuma(working.value, faces)),
            )
            logAnalysis(analysis.value)
            // Optional and best effort: without it Smart edit finds the subject by rules.
            val people = clock.measure {
                runControlled(ErrorCode.ANALYSIS_FAILED) { subjectSegmenter.segment(working.value) }
                    .let { if (it is OperationResult.Success) it.value else null }
            }
            val scene = clock.measure { SceneClassifier.classify(working.value, analysis.value) }
            logger.event("SCENE_DETECTED", mapOf("scene" to scene.value.scene, "confidence" to scene.value.confidence))

            OperationResult.Success(
                EnhancementSession(
                    source = source,
                    original = working.value,
                    preview = PixelResampler.downscaleToFit(working.value, PREVIEW_LONG_EDGE),
                    analysis = analysis.value,
                    scene = scene.value,
                    preset = preset,
                    loadTimings = TimingReport(
                        listOf(
                            StageTiming("Decode", working.durationMs),
                            StageTiming("Analyze", analysis.durationMs),
                            StageTiming("People", people.durationMs),
                            StageTiming("Scene", scene.durationMs),
                        ),
                    ),
                    subjectHint = people.value,
                ),
            )
        }

    suspend fun enhance(
        session: EnhancementSession,
        request: EnhanceRequest,
        listener: ProcessingListener? = null,
    ): OperationResult<EnhancementOutcome> = withContext(dispatcher) {
        render(session, request, sourceFor(session, request.target), listener)
    }

    /** Plans, processes, validates and applies geometry to [source], which may be preview, working or full resolution. */
    private suspend fun render(
        session: EnhancementSession,
        request: EnhanceRequest,
        source: PixelBuffer,
        listener: ProcessingListener?,
    ): OperationResult<EnhancementOutcome> {
        val processingId = idGenerator.next()
        logger.event(
            "PROCESS_START",
            mapOf(
                "processingId" to processingId,
                "width" to source.width,
                "height" to source.height,
                "preset" to session.preset.id,
                "scene" to session.sceneFor(request),
                "strength" to request.strength,
                "algorithmVersion" to ENHANCEMENT_ALGORITHM_VERSION,
            ),
        )

        val planned = clock.measure {
            ManualAdjustmentMerger.merge(planner.createPlan(session.analysis, request.strength, presetFor(session, request)), request.manual)
                .copy(colorMixer = request.colorMixer, toneCurves = request.toneCurves, colorGrading = request.colorGrading, calibration = request.calibration)
                .let { plan -> if (request.autoWhiteBalance) plan else plan.copy(whiteBalance = Adjustment.none(AS_SHOT_REASON)) }
        }
        logPlan(processingId, planned.value)

        val context = ProcessingContext(
            analysis = analysisFor(session, source),
            plan = planned.value,
            qualityPreset = presetFor(session, request),
            debugEnabled = request.debugEnabled,
            processingId = processingId,
            stageConfigs = request.stageConfigs,
        )
        val processed = when (
            val result = runControlled(ErrorCode.PROCESSING_FAILED) {
                processor.process(source, context, listener, request.runUntilStageId)
            }
        ) {
            is OperationResult.Failure -> return logFailure("PROCESS_FAILED", processingId, result)
            is OperationResult.Success -> result.value
        }

        val validationMode = if (request.hasManualEdits) ValidationMode.STRUCTURAL else ValidationMode.NATURAL
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
            return logFailure("PROCESS_FAILED", processingId, failure)
        }

        // Geometry runs after validation: validation compares same-sized images pixel for pixel.
        val geometry = clock.measure {
            // Retouch spots and local masks are placed on the photo as the user sees it, so they apply
            // after geometry: first repairs, then masked adjustments on the repaired picture.
            // Subject and sky masks are detected on the unedited view, so they don't shift as sliders move.
            val shaped = GeometryOps.apply(processed.image, request.geometry)
            val originalView = GeometryOps.apply(source, request.geometry)
            val hint = session.subjectHint?.takeIf { it.hasSubject }?.transformed(source.width, source.height, request.geometry)
            Triple(LocalAdjustmentRenderer.apply(RetouchRenderer.apply(shaped, request.retouch), request.localAdjustments, originalView, hint), originalView, hint)
        }
        val (output, originalView, hint) = geometry.value

        val timings = session.loadTimings +
            TimingReport(listOf(StageTiming("Plan", planned.durationMs))) +
            processed.stageTimings +
            TimingReport(listOf(StageTiming("Validation", validated.durationMs), StageTiming("Geometry", geometry.durationMs)))
        logger.event(
            "PROCESS_COMPLETE",
            mapOf("processingId" to processingId, "outputWidth" to output.width, "outputHeight" to output.height, "totalMs" to timings.totalMs),
        )
        return OperationResult.Success(EnhancementOutcome(processingId, request, planned.value, processed, validation, output, originalView, timings, hint))
    }

    /**
     * Smart edit: renders [request] at preview size without masks, finds the subject, sky and
     * background, and returns masks with slider values set for each (see [SmartEdit]).
     */
    suspend fun suggestSmartEdit(session: EnhancementSession, request: EnhanceRequest): OperationResult<List<LocalAdjustment>> =
        withContext(dispatcher) {
            val base = request.copy(
                target = RenderTarget.PREVIEW,
                localAdjustments = LocalAdjustments.NONE,
                runUntilStageId = null,
                stageConfigs = emptyMap(),
            )
            when (val rendered = runControlled(ErrorCode.PROCESSING_FAILED) { render(session, base, session.preview, null) }) {
                is OperationResult.Failure -> logFailure("SMART_EDIT_FAILED", null, rendered)
                is OperationResult.Success -> when (val outcome = rendered.value) {
                    is OperationResult.Failure -> outcome
                    is OperationResult.Success -> runControlled(ErrorCode.ANALYSIS_FAILED) {
                        SmartEdit.suggest(outcome.value.output, outcome.value.originalView, outcome.value.subjectHint).also { masks ->
                            logger.event("SMART_EDIT", mapOf("processingId" to outcome.value.processingId, "masks" to masks.joinToString { it.name }))
                        }
                    }
                }
            }
        }

    /**
     * Full export: decodes the source at the resolution [options] needs (up to full size), renders
     * in tiles, applies geometry, resizes to the requested size and saves a new verified file.
     * The original is never overwritten; the editing session is untouched whatever happens.
     */
    suspend fun export(
        session: EnhancementSession,
        request: EnhanceRequest,
        options: ExportOptions,
        listener: ProcessingListener? = null,
    ): OperationResult<ExportResult> = withContext(dispatcher) {
        val fullRequest = request.copy(target = RenderTarget.FULL, runUntilStageId = null, stageConfigs = emptyMap())
        val decodeLongEdge = exportDecodeLongEdge(session, request, options)
        val source = if (decodeLongEdge <= maxOf(session.original.width, session.original.height)) {
            session.original
        } else {
            when (val decoded = runControlled(ErrorCode.IMAGE_DECODE_FAILED) { imageRepository.loadWorkingImage(session.source, decodeLongEdge) }) {
                is OperationResult.Failure -> return@withContext logFailure("EXPORT_FAILED", null, decoded)
                is OperationResult.Success -> decoded.value
            }
        }
        val outcome = when (val rendered = runControlled(ErrorCode.PROCESSING_FAILED) { render(session, fullRequest, source, listener) }) {
            is OperationResult.Failure -> return@withContext logFailure("EXPORT_FAILED", null, rendered)
            is OperationResult.Success -> when (val inner = rendered.value) {
                is OperationResult.Failure -> return@withContext inner
                is OperationResult.Success -> inner.value
            }
        }
        val image = when (val finished = runControlled(ErrorCode.PROCESSING_FAILED) { finishExport(outcome.output, options) }) {
            is OperationResult.Failure -> return@withContext logFailure("EXPORT_FAILED", outcome.processingId, finished)
            is OperationResult.Success -> finished.value
        }
        val saveRequest = SaveRequest(
            displayName = OutputNaming.enhancedName(session.source.displayName, options.format.extension),
            mimeType = options.format.mimeType,
            quality = options.quality,
            metadata = options.metadata,
            metadataSourceId = session.source.id,
        )
        when (val result = runControlled(ErrorCode.SAVE_FAILED) { clock.measure { saver.save(image, saveRequest) } }) {
            is OperationResult.Failure -> logFailure("EXPORT_FAILED", outcome.processingId, result)
            is OperationResult.Success -> {
                logger.event(
                    "EXPORT_COMPLETE",
                    mapOf(
                        "processingId" to outcome.processingId,
                        "width" to image.width,
                        "height" to image.height,
                        "format" to options.format,
                        "durationMs" to result.value.durationMs,
                    ),
                )
                OperationResult.Success(ExportResult(result.value.value, image.width, image.height))
            }
        }
    }

    /**
     * Size, frame and decoration, in that order: a sized export is scaled so photo + border reach
     * exactly the requested long edge; the watermark is drawn last so its size is relative to the
     * final picture.
     */
    private fun finishExport(output: PixelBuffer, options: ExportOptions): PixelBuffer {
        val border = options.border.clamped()
        val sized = options.size.longEdge?.let { PixelResampler.downscaleToFit(output, BorderOps.contentLongEdge(it, border)) } ?: output
        val framed = BorderOps.apply(sized, border)
        val watermark = options.watermark.clamped()
        return if (watermark.isNone) framed else exportDecorator.decorate(framed, options.copy(border = border, watermark = watermark))
    }

    /** Output dimensions [export] will produce, without rendering — shown in the export dialog. */
    fun estimateExportSize(session: EnhancementSession, request: EnhanceRequest, options: ExportOptions): Pair<Int, Int> {
        val working = session.original
        val workingLongEdge = maxOf(working.width, working.height)
        val decodeLongEdge = maxOf(workingLongEdge, exportDecodeLongEdge(session, request, options))
        val scale = decodeLongEdge.toDouble() / workingLongEdge
        val (width, height) = GeometryOps.outputSize(
            (working.width * scale).roundToInt(),
            (working.height * scale).roundToInt(),
            request.geometry,
        )
        val border = options.border.clamped()
        val (contentWidth, contentHeight) = options.size.longEdge
            ?.let { PixelResampler.fitWithin(BorderOps.contentLongEdge(it, border), width, height) }
            ?: (width to height)
        val frame = 2 * BorderOps.widthFor(maxOf(contentWidth, contentHeight), border)
        return (contentWidth + frame) to (contentHeight + frame)
    }

    /** Saves at full resolution with default options. */
    suspend fun save(
        session: EnhancementSession,
        request: EnhanceRequest,
        listener: ProcessingListener? = null,
    ): OperationResult<SavedImage> = when (val exported = export(session, request, ExportOptions(), listener)) {
        is OperationResult.Failure -> exported
        is OperationResult.Success -> OperationResult.Success(exported.value.saved)
    }

    /**
     * Long edge to decode the source at: enough that, after the user's crop, the output reaches
     * the requested size — never more than the source, and never past the memory cap.
     */
    private fun exportDecodeLongEdge(session: EnhancementSession, request: EnhanceRequest, options: ExportOptions): Int {
        val source = session.source
        val sourceLongEdge = maxOf(source.orientedWidth, source.orientedHeight)
        val pixelCap = sqrt(ExportOptions.MAX_EXPORT_PIXELS.toDouble() / (source.width.toLong() * source.height))
        val cappedLongEdge = if (pixelCap < 1.0) (sourceLongEdge * pixelCap).toInt() else sourceLongEdge
        val requested = options.size.longEdge ?: return cappedLongEdge
        val working = session.original
        val (outWidth, outHeight) = GeometryOps.outputSize(working.width, working.height, request.geometry)
        val scaleNeeded = requested.toDouble() / maxOf(outWidth, outHeight)
        return minOf(cappedLongEdge, ceil(maxOf(working.width, working.height) * scaleNeeded).toInt())
    }

    /** Renders [request] at working resolution for sharing, without saving. */
    suspend fun renderForShare(session: EnhancementSession, request: EnhanceRequest): OperationResult<EnhancementOutcome> =
        enhance(session, request.copy(target = RenderTarget.FULL, runUntilStageId = null, stageConfigs = emptyMap()))

    private fun presetFor(session: EnhancementSession, request: EnhanceRequest) =
        QualityPreset.forScene(session.sceneFor(request), session.preset)

    private fun sourceFor(session: EnhancementSession, target: RenderTarget) = when (target) {
        RenderTarget.PREVIEW -> session.preview
        RenderTarget.FULL -> session.original
    }

    /**
     * Noise was measured on the working image; area-downscaling to the preview averages it away
     * by roughly the scale factor, so stages that key off noise sigma must use the smaller value.
     */
    private fun analysisFor(session: EnhancementSession, source: PixelBuffer): ImageAnalysis {
        // Works both ways: a full-resolution export has more visible noise than the working image.
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
                "faces" to analysis.faces.size,
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
        const val AS_SHOT_REASON = "As shot: automatic white balance is off"
    }
}
