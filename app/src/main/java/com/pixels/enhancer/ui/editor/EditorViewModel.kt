package com.pixels.enhancer.ui.editor

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pixels.enhancer.AppContainer
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.core.error.runControlled
import com.pixels.enhancer.data.decoder.BitmapConversions
import com.pixels.enhancer.data.storage.ShareCache
import com.pixels.enhancer.domain.analysis.Histogram
import com.pixels.enhancer.domain.analysis.SceneType
import com.pixels.enhancer.domain.debug.DebugReport
import com.pixels.enhancer.data.storage.ThumbnailStore
import com.pixels.enhancer.domain.editing.EditHistory
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.project.Project
import com.pixels.enhancer.domain.project.ProjectManager
import com.pixels.enhancer.domain.geometry.CropMath
import com.pixels.enhancer.domain.geometry.CropRect
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.geometry.GeometryOps
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.model.OutputNaming
import com.pixels.enhancer.domain.planning.ColorMixer
import com.pixels.enhancer.domain.planning.EnhancementStrength
import com.pixels.enhancer.domain.planning.HslShift
import com.pixels.enhancer.domain.planning.HueBand
import com.pixels.enhancer.domain.planning.Look
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.planning.QualityPreset
import com.pixels.enhancer.domain.processing.ProcessingListener
import com.pixels.enhancer.domain.processing.ProcessingStage
import com.pixels.enhancer.domain.processing.StageConfig
import com.pixels.enhancer.domain.repository.SettingsRepository
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.usecase.EnhanceRequest
import com.pixels.enhancer.domain.usecase.EnhancementOutcome
import com.pixels.enhancer.domain.usecase.EnhancementSession
import com.pixels.enhancer.domain.usecase.RenderTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates user actions with [EnhanceImageUseCase] and publishes [EditorUiState]. Holds no
 * image-processing logic: it only sequences calls and converts results for display.
 *
 * Live edits render the small preview; Save and Share render full resolution.
 */
class EditorViewModel(
    private val enhanceImage: EnhanceImageUseCase,
    private val settingsRepository: SettingsRepository,
    private val shareCache: ShareCache,
    private val projectManager: ProjectManager,
    private val thumbnails: ThumbnailStore,
    private val isDebugBuild: Boolean,
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Idle())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events: Flow<EditorEvent> = _events.receiveAsFlow()

    private var settings = settingsRepository.load()
    private var session: EnhancementSession? = null
    private var outcome: EnhancementOutcome? = null
    private var cropMode = false
    private var cropAspect = CropAspect.FREE

    /** What the sliders show right now (may be mid-drag). */
    private var current = EditState(settings.strength)

    /** Committed edits; one finished slider drag is one step. */
    private var history = EditHistory(current)

    /** The open project; every committed edit is autosaved to it. */
    private var project: Project? = null

    /** Autosaves run one at a time so two quick edits never race on the same file. */
    private val saveMutex = Mutex()

    private var disabledStages: Set<String> = emptySet()
    private var runUntilStageId: String? = null
    private var openJob: Job? = null
    private var exportJob: Job? = null

    /** Every edit becomes a preview request; newer requests cancel older renders. */
    private val previewRequests = MutableStateFlow<EnhanceRequest?>(null)

    init {
        startPreviewRenderer()
        refreshRecent()
    }

    fun onImagePicked(uri: Uri) = openSource(uri.toString(), projectId = null)

    fun onOpenProject(id: String) {
        viewModelScope.launch {
            val saved = projectManager.load(id) ?: return@launch refreshRecent()
            openSource(saved.sourceId, saved.id)
        }
    }

    /** Removes the project from Recent; the original photo is never touched. */
    fun onDeleteProject(id: String) {
        viewModelScope.launch {
            projectManager.delete(id)
            thumbnails.delete(id)
            refreshRecent()
        }
    }

    private fun openSource(sourceId: String, projectId: String?) {
        openJob?.cancel()
        clearSession()
        openJob = viewModelScope.launch {
            _uiState.value = EditorUiState.Loading
            when (val opened = enhanceImage.open(sourceId, QualityPreset.byId(settings.presetId))) {
                is OperationResult.Failure -> _uiState.value = EditorUiState.Error(opened.code)
                is OperationResult.Success -> {
                    session = opened.value
                    val resumed = projectId?.let { projectManager.load(it) }
                        ?: projectManager.startOrResume(opened.value.source, EditState(settings.strength), settings.export)
                    project = resumed
                    history = projectManager.historyOf(resumed)
                    current = history.current
                    requestPreview()
                }
            }
        }
    }

    private fun refreshRecent() {
        viewModelScope.launch {
            val recent = projectManager.recent().map { saved ->
                RecentProject(saved.id, saved.displayName ?: saved.id.take(8), saved.modifiedAtMillis, thumbnails.load(saved.id))
            }
            _uiState.update { state -> if (state is EditorUiState.Idle) EditorUiState.Idle(recent, recentLoaded = true) else state }
        }
    }

    fun onStrengthChanged(value: Float) = edit(current.copy(strength = value))

    fun onControlChanged(control: ManualControl, value: Float) = edit(current.copy(manual = current.manual.with(control, value)))

    /** Called when a slider drag ends: records an undo step and remembers the strength. */
    fun onEditFinished() {
        if (!history.commit(current)) return
        autosave()
        if (settings.strength != current.strength) {
            settings = settings.copy(strength = current.strength)
            settingsRepository.save(settings)
        }
        publishEdit()
    }

    fun onRotateClockwise() = commitGeometry(current.geometry.rotatedClockwise())

    fun onRotateCounterClockwise() = commitGeometry(current.geometry.rotatedCounterClockwise())

    fun onFlip() = commitGeometry(current.geometry.flipped())

    /** Live while dragging; [onEditFinished] records the undo step. */
    fun onStraightenChanged(degrees: Float) = edit(current.copy(geometry = current.geometry.straightened(degrees)))

    /**
     * The crop frame moves on top of an uncropped preview, so dragging it needs no re-render;
     * the crop is applied when the user leaves the Crop tab.
     */
    fun onCropChanged(crop: CropRect) = edit(current.copy(geometry = current.geometry.copy(crop = crop)), render = !cropMode)

    fun onCropAspectSelected(aspect: CropAspect) {
        cropAspect = aspect
        val ratio = aspectRatioFor(aspect)
        if (ratio != null) onCropChanged(CropMath.largestCentered(current.geometry.crop, ratio, frameAspect()))
        onEditFinished()
        _uiState.update { state -> if (state is EditorUiState.Success) state.copy(cropAspect = aspect) else state }
    }

    fun onResetGeometry() {
        cropAspect = CropAspect.FREE
        commitGeometry(Geometry.NONE)
    }

    fun onCropModeChanged(enabled: Boolean) {
        if (cropMode == enabled) return
        cropMode = enabled
        requestPreview()
    }

    /** Pixel width/height ratio a [CropAspect] asks for; ORIGINAL means the unrotated photo's shape. */
    fun aspectRatioFor(aspect: CropAspect): Float? {
        if (aspect != CropAspect.ORIGINAL) return aspect.ratio
        val source = session?.original ?: return null
        val ratio = source.width.toFloat() / source.height
        return if (current.geometry.swapsAxes) 1f / ratio else ratio
    }

    /** Width/height of the uncropped frame the crop rectangle lives in. */
    private fun frameAspect(): Float {
        val source = session?.original ?: return 1f
        val (width, height) = GeometryOps.outputSize(source.width, source.height, current.geometry.withoutCrop())
        return width.toFloat() / height
    }

    private fun commitGeometry(geometry: Geometry) {
        edit(current.copy(geometry = geometry))
        onEditFinished()
    }

    /** Live while dragging a colour-mixer slider; [onEditFinished] records the undo step. */
    fun onColorMixerChanged(band: HueBand, shift: HslShift) = edit(current.copy(colorMixer = current.colorMixer.with(band, shift)))

    fun onResetColorMixer() {
        edit(current.copy(colorMixer = ColorMixer.NONE))
        onEditFinished()
    }

    /** null returns to the detected scene. */
    fun onSceneSelected(scene: SceneType?) {
        edit(current.copy(sceneOverride = scene))
        onEditFinished()
    }

    fun onLookSelected(look: Look) {
        edit(current.copy(manual = look.adjustments, lookId = look.id))
        onEditFinished()
    }

    fun onResetControl(control: ManualControl) {
        edit(current.copy(manual = current.manual.with(control, 0f)))
        onEditFinished()
    }

    /** Back to the automatic correction at the default strength. */
    fun onResetAll() {
        disabledStages = emptySet()
        runUntilStageId = null
        edit(EditState(EnhancementStrength.DEFAULT))
        onEditFinished()
    }

    /** The true zero state: no enhancement, no adjustments, no crop — the original appearance. */
    fun onShowOriginalEdit() {
        edit(EditState.ORIGINAL)
        onEditFinished()
    }

    fun onUndo() = restore(history.undo())

    fun onRedo() = restore(history.redo())

    private fun restore(state: EditState?) {
        current = state ?: return
        autosave()
        publishEdit()
        requestPreview()
    }

    /** Persists the edit and history so the project survives restarts; failures are logged, never fatal to editing. */
    private fun autosave() {
        val saved = project ?: return
        val snapshot = history
        viewModelScope.launch {
            saveMutex.withLock {
                val latest = project?.takeIf { it.id == saved.id } ?: saved
                runCatching { projectManager.recordHistory(latest, snapshot) }
                    .onSuccess { updated -> if (project?.id == updated.id) project = updated }
                outcome?.output?.let { runCatching { thumbnails.save(saved.id, it) } }
            }
        }
    }

    /** Save opens the export dialog with the last-used options. */
    fun onSave() {
        val state = _uiState.value as? EditorUiState.Success ?: return
        if (state.activity is EditorActivity.Saving) return
        showExportDialog(project?.exportOptions ?: settings.export)
    }

    fun onExportOptionsChanged(options: ExportOptions) = showExportDialog(options)

    fun onExportDismissed() {
        _uiState.update { state -> if (state is EditorUiState.Success) state.copy(exportDialog = null) else state }
    }

    fun onExportConfirmed() {
        val currentSession = session ?: return
        val options = (_uiState.value as? EditorUiState.Success)?.exportDialog?.options ?: return
        settings = settings.copy(export = options)
        settingsRepository.save(settings)
        _uiState.update { state -> if (state is EditorUiState.Success) state.copy(exportDialog = null, activity = EditorActivity.Saving(0f)) else state }
        exportJob = viewModelScope.launch {
            val listener = progressListener { setActivity(EditorActivity.Saving(it)) }
            when (val exported = enhanceImage.export(currentSession, fullRequest(), options, listener)) {
                is OperationResult.Failure -> setActivity(EditorActivity.Failed(exported.code))
                is OperationResult.Success -> {
                    val result = exported.value
                    setActivity(EditorActivity.Saved(result.saved.displayName, Uri.parse(result.saved.id), result.width, result.height))
                    project?.let { saved ->
                        runCatching { projectManager.recordExport(saved, options, current) }.onSuccess { project = it }
                    }
                }
            }
        }
    }

    /** The saver deletes any partial file when cancelled, so nothing half-written reaches the gallery. */
    fun onCancelExport() {
        exportJob?.cancel()
        exportJob = null
        setActivity(EditorActivity.None)
    }

    private fun showExportDialog(options: ExportOptions) {
        val currentSession = session ?: return
        val (width, height) = enhanceImage.estimateExportSize(currentSession, fullRequest(), options)
        _uiState.update { state ->
            if (state is EditorUiState.Success) state.copy(exportDialog = ExportDialogState(options, width, height)) else state
        }
    }

    fun onViewSaved(uri: Uri) {
        viewModelScope.launch { _events.send(EditorEvent.ViewImage(uri)) }
    }

    fun onShare() {
        val currentSession = session ?: return
        setActivity(EditorActivity.Saving(0f))
        exportJob = viewModelScope.launch {
            val rendered = when (val result = enhanceImage.renderForShare(currentSession, fullRequest())) {
                is OperationResult.Failure -> return@launch setActivity(EditorActivity.Failed(result.code))
                is OperationResult.Success -> result.value
            }
            val name = OutputNaming.enhancedName(currentSession.source.displayName)
            when (val written = runControlled(ErrorCode.OUTPUT_ENCODE_FAILED) { shareCache.write(rendered.output, name) }) {
                is OperationResult.Failure -> setActivity(EditorActivity.Failed(written.code))
                is OperationResult.Success -> {
                    setActivity(EditorActivity.None)
                    _events.send(EditorEvent.ShareImage(written.value))
                }
            }
        }
    }

    fun onStageToggled(stageId: String, enabled: Boolean) {
        disabledStages = if (enabled) disabledStages - stageId else disabledStages + stageId
        requestPreview()
    }

    fun onRunUntilSelected(stageId: String?) {
        runUntilStageId = stageId
        requestPreview()
    }

    fun onExportDebugReport() {
        val currentSession = session ?: return
        viewModelScope.launch { _events.send(EditorEvent.ShareText(DebugReport.format(currentSession, outcome))) }
    }

    /** Close asks first when the current edit has never been exported. */
    fun onCloseRequested() {
        if (project?.hasUnexportedChanges == true) {
            _uiState.update { state -> if (state is EditorUiState.Success) state.copy(confirmLeave = true) else state }
        } else {
            onClose()
        }
    }

    fun onLeaveDismissed() {
        _uiState.update { state -> if (state is EditorUiState.Success) state.copy(confirmLeave = false) else state }
    }

    fun onClose() {
        openJob?.cancel()
        exportJob?.cancel()
        clearSession()
        _uiState.value = EditorUiState.Idle()
        refreshRecent()
    }

    private fun edit(next: EditState, render: Boolean = true) {
        current = next
        publishEdit()
        if (render) requestPreview()
    }

    /** Moves the sliders immediately; the image follows when the preview render finishes. */
    private fun publishEdit() {
        _uiState.update { state ->
            if (state is EditorUiState.Success) state.copy(edit = current, canUndo = history.canUndo, canRedo = history.canRedo) else state
        }
    }

    private fun requestPreview() {
        if (session == null) return
        previewRequests.value = request(RenderTarget.PREVIEW)
    }

    private fun request(target: RenderTarget) = EnhanceRequest(
        strength = current.strength,
        manual = current.manual,
        geometry = if (cropMode && target == RenderTarget.PREVIEW) current.geometry.withoutCrop() else current.geometry,
        colorMixer = current.colorMixer,
        sceneOverride = current.sceneOverride,
        target = target,
        debugEnabled = isDebugBuild,
        stageConfigs = disabledStages.associateWith { StageConfig(enabled = false) },
        runUntilStageId = runUntilStageId,
    )

    /** Save/share always use the full pipeline, whatever the debug screen is inspecting. */
    private fun fullRequest() = request(RenderTarget.FULL).copy(stageConfigs = emptyMap(), runUntilStageId = null)

    @OptIn(FlowPreview::class)
    private fun startPreviewRenderer() {
        viewModelScope.launch {
            previewRequests.filterNotNull().debounce(PREVIEW_DEBOUNCE_MS).collectLatest { request -> renderPreview(request) }
        }
    }

    private suspend fun renderPreview(request: EnhanceRequest) {
        val currentSession = session ?: return
        val listener = progressListener { progress -> showProgress(progress) }
        when (val result = enhanceImage.enhance(currentSession, request, listener)) {
            is OperationResult.Failure -> showFailure(result.code)
            is OperationResult.Success -> {
                outcome = result.value
                val enhanced = toImageBitmap(result.value.output)
                val original = toImageBitmap(result.value.originalView)
                val histogram = withContext(Dispatchers.Default) { Histogram.compute(result.value.output) }
                _uiState.update { state ->
                    EditorUiState.Success(
                        original = original,
                        enhanced = enhanced,
                        edit = current,
                        canUndo = history.canUndo,
                        canRedo = history.canRedo,
                        cropMode = request.geometry.crop.isFull && cropMode,
                        cropAspect = cropAspect,
                        exportDialog = (state as? EditorUiState.Success)?.exportDialog,
                        confirmLeave = (state as? EditorUiState.Success)?.confirmLeave ?: false,
                        detectedScene = currentSession.scene.scene,
                        histogram = histogram,
                        // A save in progress (or just finished) outlives preview renders; stale progress/errors do not.
                        activity = (state as? EditorUiState.Success)?.activity
                            ?.takeIf { it is EditorActivity.Saving || it is EditorActivity.Saved }
                            ?: EditorActivity.None,
                        debug = debugInfo(currentSession, result.value),
                    )
                }
            }
        }
    }

    private fun showProgress(progress: Float) {
        _uiState.update { state ->
            when (state) {
                is EditorUiState.Success ->
                    if (state.activity is EditorActivity.Saving) state else state.copy(activity = EditorActivity.Reprocessing(progress))
                is EditorUiState.Loading, is EditorUiState.Processing -> EditorUiState.Processing(progress)
                else -> state
            }
        }
    }

    /** A failed re-render keeps the last good result on screen; a failed first render shows the error screen. */
    private fun showFailure(code: ErrorCode) {
        _uiState.update { state ->
            if (state is EditorUiState.Success) state.copy(activity = EditorActivity.Failed(code)) else EditorUiState.Error(code)
        }
    }

    private fun progressListener(onProgress: (Float) -> Unit) = object : ProcessingListener {
        override fun onStageStarted(stage: ProcessingStage, index: Int, total: Int) = onProgress(index / total.toFloat())
        override fun onStageCompleted(stage: ProcessingStage, durationMs: Long) = Unit
    }

    private fun debugInfo(session: EnhancementSession, outcome: EnhancementOutcome): DebugInfo? {
        if (!isDebugBuild) return null
        return DebugInfo(DebugReport.sections(session, outcome), enhanceImage.stageIds, disabledStages, runUntilStageId)
    }

    private fun setActivity(activity: EditorActivity) {
        _uiState.update { state -> if (state is EditorUiState.Success) state.copy(activity = activity) else state }
    }

    private suspend fun toImageBitmap(buffer: PixelBuffer): ImageBitmap =
        withContext(Dispatchers.Default) { BitmapConversions.toBitmap(buffer).asImageBitmap() }

    private fun clearSession() {
        previewRequests.value = null
        session = null
        outcome = null
        cropMode = false
        cropAspect = CropAspect.FREE
        current = EditState(settings.strength)
        history = EditHistory(current)
        project = null
        disabledStages = emptySet()
        runUntilStageId = null
    }

    companion object {
        /** Coalesces rapid slider movement into one render. */
        private const val PREVIEW_DEBOUNCE_MS = 60L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                EditorViewModel(
                    enhanceImage = container.enhanceImageUseCase,
                    settingsRepository = container.settingsRepository,
                    shareCache = container.shareCache,
                    projectManager = container.projectManager,
                    thumbnails = container.thumbnails,
                    isDebugBuild = container.isDebugBuild,
                )
            }
        }
    }
}
