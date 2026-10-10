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
import com.pixels.enhancer.R
import com.pixels.enhancer.core.error.EnhancerException
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.core.error.runControlled
import com.pixels.enhancer.data.decoder.BitmapConversions
import com.pixels.enhancer.data.storage.AppStorage
import com.pixels.enhancer.data.storage.IncomingImages
import com.pixels.enhancer.data.storage.ShareCache
import com.pixels.enhancer.data.storage.ThumbnailStore
import com.pixels.enhancer.domain.analysis.Histogram
import com.pixels.enhancer.domain.analysis.SceneType
import com.pixels.enhancer.domain.debug.DebugReport
import com.pixels.enhancer.domain.editing.EditDiff
import com.pixels.enhancer.domain.editing.EditHistory
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.geometry.AutoGeometry
import com.pixels.enhancer.domain.geometry.CropMath
import com.pixels.enhancer.domain.geometry.CropRect
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.geometry.GeometryOps
import com.pixels.enhancer.domain.geometry.LensCorrection
import com.pixels.enhancer.domain.geometry.OpticsWarp
import com.pixels.enhancer.domain.geometry.Perspective
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.PixelResampler
import com.pixels.enhancer.domain.local.BrushStroke
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.domain.local.LocalAdjustmentRenderer
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.local.RangeMask
import com.pixels.enhancer.domain.model.OutputNaming
import com.pixels.enhancer.domain.planning.Calibration
import com.pixels.enhancer.domain.planning.ColorGrading
import com.pixels.enhancer.domain.planning.ColorMixer
import com.pixels.enhancer.domain.planning.CurveChannel
import com.pixels.enhancer.domain.planning.CurvePoints
import com.pixels.enhancer.domain.planning.CurvePreset
import com.pixels.enhancer.domain.planning.EnhancementStrength
import com.pixels.enhancer.domain.planning.HslShift
import com.pixels.enhancer.domain.planning.HueBand
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.planning.QualityPreset
import com.pixels.enhancer.domain.presets.Preset
import com.pixels.enhancer.domain.presets.PresetMath
import com.pixels.enhancer.domain.presets.PresetStore
import com.pixels.enhancer.domain.presets.SettingsGroup
import com.pixels.enhancer.domain.processing.ProcessingListener
import com.pixels.enhancer.domain.processing.ProcessingStage
import com.pixels.enhancer.domain.processing.StageConfig
import com.pixels.enhancer.domain.processing.ops.WhiteBalanceGains
import com.pixels.enhancer.domain.project.EditVersion
import com.pixels.enhancer.domain.project.Project
import com.pixels.enhancer.domain.project.ProjectManager
import com.pixels.enhancer.domain.regions.RegionKind
import com.pixels.enhancer.domain.regions.SmartEdit
import com.pixels.enhancer.domain.repository.EnhancerSettings
import com.pixels.enhancer.domain.repository.SettingsRepository
import com.pixels.enhancer.domain.retouch.RetouchMode
import com.pixels.enhancer.domain.retouch.RetouchSourceFinder
import com.pixels.enhancer.domain.retouch.RetouchSpot
import com.pixels.enhancer.domain.usecase.BatchExportUseCase
import com.pixels.enhancer.domain.usecase.BatchItemResult
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.usecase.EnhanceRequest
import com.pixels.enhancer.domain.usecase.EnhancementOutcome
import com.pixels.enhancer.domain.usecase.EnhancementSession
import com.pixels.enhancer.domain.usecase.RenderTarget
import com.pixels.enhancer.domain.usecase.toRequest
import com.pixels.enhancer.ui.panels.PanelReset
import java.util.UUID
import kotlinx.coroutines.CancellationException
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
    private val presetStore: PresetStore,
    private val batchExport: BatchExportUseCase,
    private val incomingImages: IncomingImages,
    private val appStorage: AppStorage,
    private val isDebugBuild: Boolean,
) : ViewModel(), EditorActions {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Idle())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events: Flow<EditorEvent> = _events.receiveAsFlow()

    private var settings = settingsRepository.load()

    private val _settingsState = MutableStateFlow(settings)

    /** Current settings, for the Settings screen and app-wide preferences (haptics). */
    val settingsState: StateFlow<EnhancerSettings> = _settingsState.asStateFlow()

    private val _storageUsed = MutableStateFlow<Long?>(null)

    /** Bytes Pixels stores on the device; null until measured. */
    val storageUsed: StateFlow<Long?> = _storageUsed.asStateFlow()

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

    private var userPresets: List<Preset> = emptyList()

    /** The edit before the last preset was applied, so its amount slider can re-apply from scratch. */
    private var presetBase: EditState? = null
    private var appliedPreset: AppliedPreset? = null
    private var copiedSettings: EditState? = null
    private var selectedMaskId: Int? = null
    private var showMaskOverlay = false
    private var maskOverlay: ImageBitmap? = null
    private var selectedSpotId: Int? = null
    private var healSettings = HealSettings()
    private var showClipping = false

    /** History labels, recomputed only when the history changes (never per slider frame). */
    private var historyLabelCache: List<String> = emptyList()

    /** The edit a preset (or its amount slider) produced; any other change ends the amount slider. */
    private var lastPresetResult: EditState? = null
    private var batch: BatchProgress? = null
    private var batchJob: Job? = null
    private var pendingBatchGroups: Set<SettingsGroup> = SettingsGroup.ALL

    private var disabledStages: Set<String> = emptySet()
    private var runUntilStageId: String? = null
    private var openJob: Job? = null
    private var importJob: Job? = null
    private var exportJob: Job? = null

    /** Every edit becomes a preview request; newer requests cancel older renders. */
    private val previewRequests = MutableStateFlow<EnhanceRequest?>(null)

    init {
        startPreviewRenderer()
        refreshRecent()
        viewModelScope.launch { userPresets = attempt { presetStore.list() }.getOrDefault(emptyList()) }
    }

    fun onImagePicked(uri: Uri) = openSource(uri.toString(), projectId = null)

    /**
     * A photo shared or sent to Pixels by another app: validated and copied into app storage first
     * (see [IncomingImages.importShared]), so the project can be reopened after a restart.
     */
    fun onImportShared(uri: Uri) {
        importJob?.cancel()
        openJob?.cancel()
        clearSession()
        _uiState.value = EditorUiState.Loading
        importJob = viewModelScope.launch {
            // Another app chose this URI, so any failure at all ends on the error screen, never a crash.
            val local = attempt { incomingImages.importShared(uri) }.getOrElse { error ->
                _uiState.value = EditorUiState.Error((error as? EnhancerException)?.code ?: ErrorCode.IMAGE_NOT_FOUND)
                return@launch
            }
            openSource(local.toString(), projectId = null)
        }
    }

    /** Camera capture finished; the photo is already in app storage. */
    fun onCaptured(uri: Uri) = openSource(uri.toString(), projectId = null)

    fun onCaptureCancelled(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) { incomingImages.discardCapture(uri) }
    }

    // --- Home: project list management ---

    fun onDuplicateProject(id: String) {
        viewModelScope.launch {
            val copy = attempt { projectManager.duplicate(id) }.getOrNull() ?: return@launch
            attempt { thumbnails.copy(id, copy.id) }
            refreshRecent()
        }
    }

    fun onRenameProject(id: String, name: String) {
        viewModelScope.launch {
            attempt { projectManager.rename(id, name) }
            refreshRecent()
        }
    }

    /** Called when Home is shown again (e.g. back from Settings) so the list is current. */
    fun onHomeShown() = refreshRecent()

    // --- Settings ---

    fun onSettingsChanged(updated: EnhancerSettings) = saveSettings(updated)

    private fun saveSettings(updated: EnhancerSettings) {
        settings = updated
        settingsRepository.save(updated)
        _settingsState.value = updated
    }

    /** Deletes every project, preview, imported and captured photo. Gallery photos are never touched. */
    fun onDeleteAllEdits() {
        viewModelScope.launch {
            attempt { projectManager.recent().forEach { projectManager.delete(it.id) } }
            attempt { thumbnails.deleteAll() }
            attempt { incomingImages.deleteAll() }
            refreshRecent()
            measureStorage()
        }
    }

    fun onDeleteAllPresets() {
        viewModelScope.launch {
            attempt { presetStore.list().forEach { presetStore.delete(it.id) } }
            userPresets = emptyList()
            measureStorage()
        }
    }

    fun onClearTemporaryFiles() {
        viewModelScope.launch {
            attempt { shareCache.clear() }
            measureStorage()
        }
    }

    /** Re-measures [storageUsed] (the Settings screen asks when it opens). */
    fun onRefreshStorage() {
        viewModelScope.launch { measureStorage() }
    }

    private suspend fun measureStorage() {
        _storageUsed.value = attempt { appStorage.bytesUsed() }.getOrNull()
    }

    fun onOpenProject(id: String) {
        viewModelScope.launch {
            val saved = attempt { projectManager.load(id) }.getOrNull() ?: return@launch refreshRecent()
            openSource(saved.sourceId, saved.id)
        }
    }

    /** Removes the project from Recent; the original photo is never touched. */
    fun onDeleteProject(id: String) {
        viewModelScope.launch {
            attempt { projectManager.delete(id) }
            attempt { thumbnails.delete(id) }
            refreshRecent()
        }
    }

    private fun openSource(sourceId: String, projectId: String?) {
        openJob?.cancel()
        clearSession()
        // Settings may have changed on the Settings screen since the last photo.
        settings = settingsRepository.load()
        openJob = viewModelScope.launch {
            _uiState.value = EditorUiState.Loading
            when (val opened = enhanceImage.open(sourceId, QualityPreset.byId(settings.presetId))) {
                is OperationResult.Failure -> _uiState.value = EditorUiState.Error(opened.code)
                is OperationResult.Success -> {
                    session = opened.value
                    // Editing still works if the project file can't be read or written (e.g. storage
                    // full); only autosave and Recent are lost, and both recover on the next open.
                    val resumed = attempt {
                        projectId?.let { projectManager.load(it) }
                            ?: projectManager.startOrResume(opened.value.source, EditState(settings.strength), settings.export)
                    }.getOrNull()
                    project = resumed
                    history = resumed?.let(projectManager::historyOf) ?: EditHistory(EditState(settings.strength))
                    current = history.current
                    refreshHistoryLabels()
                    requestPreview()
                    // A photo opened for the first time gets Smart edit; a resumed edit is left as it was.
                    if (settings.smartEditNewPhotos && history.timeline.size == 1 && current.localAdjustments.items.isEmpty()) runSmartEdit(announce = false)
                }
            }
        }
    }

    private fun refreshRecent() {
        viewModelScope.launch {
            val recent = attempt { projectManager.recent() }.getOrDefault(emptyList()).map { saved ->
                val thumbnail = attempt { thumbnails.load(saved.id) }.getOrNull()
                RecentProject(saved.id, saved.displayName ?: saved.id.take(8), saved.modifiedAtMillis, thumbnail)
            }
            _uiState.update { state -> if (state is EditorUiState.Idle) EditorUiState.Idle(recent, recentLoaded = true) else state }
        }
    }

    override fun onStrengthChanged(value: Float) = edit(current.copy(strength = value))

    override fun onControlChanged(control: ManualControl, value: Float) = edit(current.copy(manual = current.manual.with(control, value)))

    /** Called when a slider drag ends: records an undo step and remembers the strength. */
    override fun onEditFinished() {
        if (appliedPreset != null && current != lastPresetResult) {
            appliedPreset = null
            presetBase = null
        }
        if (!history.commit(current)) return
        autosave()
        if (settings.strength != current.strength) saveSettings(settings.copy(strength = current.strength))
        refreshHistoryLabels()
        publishEdit()
    }

    override fun onRotateClockwise() = commitGeometry(current.geometry.rotatedClockwise())

    override fun onRotateCounterClockwise() = commitGeometry(current.geometry.rotatedCounterClockwise())

    override fun onFlip() = commitGeometry(current.geometry.flipped())

    override fun onFlipVertical() = commitGeometry(current.geometry.flippedVertically())

    /** Live while dragging; [onEditFinished] records the undo step. */
    override fun onStraightenChanged(degrees: Float) = edit(current.copy(geometry = current.geometry.straightened(degrees)))

    /**
     * The crop frame moves on top of an uncropped preview, so dragging it needs no re-render;
     * the crop is applied when the user leaves the Crop tab.
     */
    override fun onCropChanged(crop: CropRect) = edit(current.copy(geometry = current.geometry.copy(crop = crop)), render = !cropMode)

    override fun onCropAspectSelected(aspect: CropAspect) {
        cropAspect = aspect
        val ratio = aspectRatioFor(aspect)
        if (ratio != null) onCropChanged(CropMath.largestCentered(current.geometry.crop, ratio, frameAspect()))
        onEditFinished()
        _uiState.update { state -> if (state is EditorUiState.Success) state.copy(cropAspect = aspect) else state }
    }

    override fun onResetGeometry() {
        cropAspect = CropAspect.FREE
        commitGeometry(Geometry.NONE)
    }

    override fun onCropModeChanged(enabled: Boolean) {
        if (cropMode == enabled) return
        cropMode = enabled
        requestPreview()
    }

    /** Pixel width/height ratio a [CropAspect] asks for; ORIGINAL means the unrotated photo's shape. */
    override fun aspectRatioFor(aspect: CropAspect): Float? {
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
    override fun onColorMixerChanged(band: HueBand, shift: HslShift) = edit(current.copy(colorMixer = current.colorMixer.with(band, shift)))

    /** Live while dragging a curve point; [onEditFinished] records the undo step. */
    override fun onCurveChanged(channel: CurveChannel, points: CurvePoints) =
        edit(current.copy(toneCurves = current.toneCurves.with(channel, points)))

    override fun onResetCurve(channel: CurveChannel) {
        edit(current.copy(toneCurves = current.toneCurves.with(channel, CurvePoints.IDENTITY)))
        onEditFinished()
    }

    /** Live while dragging a mask handle or slider; [onEditFinished] records the undo step. */
    override fun onLocalChanged(item: LocalAdjustment) = edit(current.copy(localAdjustments = current.localAdjustments.with(item)))

    private fun onLocalRemoved(id: Int) {
        edit(current.copy(localAdjustments = current.localAdjustments.without(id)))
        onEditFinished()
    }

    // --- Presets, copy/paste, versions ---

    override fun onPresetApplied(preset: Preset) {
        val base = if (appliedPreset != null) presetBase ?: current else current
        presetBase = base
        appliedPreset = AppliedPreset(preset, 1f)
        val result = PresetMath.apply(base, preset, 1f)
        lastPresetResult = result
        edit(result)
        onEditFinished()
    }

    /** Live while dragging the preset amount; [onEditFinished] records the undo step. */
    override fun onPresetAmountChanged(amount: Float) {
        val applied = appliedPreset ?: return
        val base = presetBase ?: return
        appliedPreset = applied.copy(amount = amount)
        val result = PresetMath.apply(base, applied.preset, amount)
        lastPresetResult = result
        edit(result)
    }

    override fun onSavePreset(name: String) {
        val trimmed = name.trim().ifEmpty { return }
        val preset = Preset("user-${UUID.randomUUID()}", trimmed, USER_PRESET_CATEGORY, PresetMath.settingsOf(current), builtIn = false)
        viewModelScope.launch {
            attempt { presetStore.save(preset) }
                .onSuccess {
                    userPresets = attempt { presetStore.list() }.getOrDefault(userPresets + preset)
                    publishEdit()
                }
                .onFailure { setActivity(EditorActivity.Failed(ErrorCode.SAVE_FAILED)) }
        }
    }

    override fun onDeletePreset(preset: Preset) {
        if (preset.builtIn) return
        viewModelScope.launch {
            attempt { presetStore.delete(preset.id) }
            userPresets = attempt { presetStore.list() }.getOrDefault(userPresets - preset)
            publishEdit()
        }
    }

    override fun onCopySettings() {
        copiedSettings = current
        publishEdit()
    }

    override fun onPasteSettings(groups: Set<SettingsGroup>) {
        val copied = copiedSettings ?: return
        edit(PresetMath.paste(copied, current, groups))
        onEditFinished()
    }

    override fun onSaveVersion(name: String) {
        val saved = project ?: return
        val version = EditVersion(name.trim().ifEmpty { "Version ${saved.versions.size + 1}" }, System.currentTimeMillis(), current)
        updateVersions(saved, saved.versions + version)
    }

    override fun onApplyVersion(version: EditVersion) {
        edit(version.edit)
        onEditFinished()
    }

    override fun onDeleteVersion(version: EditVersion) {
        val saved = project ?: return
        updateVersions(saved, saved.versions - version)
    }

    private fun updateVersions(saved: Project, versions: List<EditVersion>) {
        project = saved.copy(versions = versions)
        publishEdit()
        viewModelScope.launch {
            saveMutex.withLock {
                attempt { projectManager.recordVersions(project ?: saved, versions) }.onSuccess { updated -> if (project?.id == updated.id) project = updated }
            }
        }
    }

    // --- Colour grading, optics, geometry ---

    /** Live while dragging; [onEditFinished] records the undo step. */
    override fun onGradingChanged(grading: ColorGrading) = edit(current.copy(colorGrading = grading.clamped()))

    override fun onMonochromeChanged(enabled: Boolean) {
        edit(current.copy(colorGrading = current.colorGrading.copy(monochrome = enabled)))
        onEditFinished()
    }

    override fun onResetGrading() {
        edit(current.copy(colorGrading = ColorGrading.NONE.copy(monochrome = current.colorGrading.monochrome)))
        onEditFinished()
    }

    override fun onLensChanged(lens: LensCorrection) = edit(current.copy(geometry = current.geometry.withLens(lens)))

    override fun onPerspectiveChanged(perspective: Perspective) = edit(current.copy(geometry = current.geometry.withPerspective(perspective)))

    override fun onResetLens() = commitGeometry(current.geometry.withLens(LensCorrection.NONE))

    override fun onResetPerspective() = commitGeometry(current.geometry.withPerspective(Perspective.NONE))

    // --- Masks ---

    override fun onAddMask(kind: MaskKind) {
        if (current.localAdjustments.items.size >= LocalAdjustments.MAX_ITEMS) return
        val output = outcome?.output
        val aspect = if (output == null) 1f else output.width.toFloat() / output.height
        val id = current.localAdjustments.nextId()
        val item = when (kind) {
            MaskKind.BRUSH -> LocalAdjustment.brush(id)
            MaskKind.LINEAR -> LocalAdjustment.linearTop(id)
            MaskKind.RADIAL -> LocalAdjustment.radialAt(id, 0.5f, 0.5f, aspect)
            MaskKind.LUMINANCE -> LocalAdjustment.luminanceRange(id)
            // Starts from the photo's centre colour; tapping the photo picks another.
            MaskKind.COLOR -> sampleColor(0.5f, 0.5f).let { (r, g, b) -> LocalAdjustment.colorRange(id, r, g, b) }
            MaskKind.SUBJECT -> LocalAdjustment.region(id, RegionKind.SUBJECT)
            MaskKind.SKY -> LocalAdjustment.region(id, RegionKind.SKY)
            MaskKind.BACKGROUND -> LocalAdjustment.region(id, RegionKind.BACKGROUND)
        }
        selectedMaskId = id
        edit(current.copy(localAdjustments = current.localAdjustments.with(item)))
        onEditFinished()
        refreshMaskOverlay()
    }

    override fun onSelectMask(id: Int?) {
        selectedMaskId = id
        publishEdit()
        refreshMaskOverlay()
    }

    override fun onMaskOverlayChanged(show: Boolean) {
        showMaskOverlay = show
        publishEdit()
        refreshMaskOverlay()
    }

    /** One finished brush stroke on the selected mask (one undo step). */
    override fun onBrushStroke(stroke: BrushStroke) {
        val item = current.localAdjustments.byId(selectedMaskId ?: return) ?: return
        edit(current.copy(localAdjustments = current.localAdjustments.with(item.withStroke(stroke))))
        onEditFinished()
    }

    /** Colour-range masks pick their colour from where the user taps on the photo. */
    override fun onSampleMaskColor(x: Float, y: Float) {
        val item = current.localAdjustments.byId(selectedMaskId ?: return) ?: return
        val range = item.range as? RangeMask.Color ?: return
        val (r, g, b) = sampleColor(x, y)
        edit(current.copy(localAdjustments = current.localAdjustments.with(item.copy(range = range.copy(red = r, green = g, blue = b)))))
        onEditFinished()
    }

    override fun onMaskRemoved(id: Int) {
        if (selectedMaskId == id) selectedMaskId = null
        onLocalRemoved(id)
    }

    private fun sampleColor(x: Float, y: Float): Triple<Int, Int, Int> {
        val output = outcome?.output ?: return Triple(128, 128, 128)
        val px = (x * output.width).toInt().coerceIn(0, output.width - 1)
        val py = (y * output.height).toInt().coerceIn(0, output.height - 1)
        // Average a small neighbourhood so noise doesn't decide the colour.
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (yy in (py - 2).coerceAtLeast(0)..(py + 2).coerceAtMost(output.height - 1)) {
            for (xx in (px - 2).coerceAtLeast(0)..(px + 2).coerceAtMost(output.width - 1)) {
                val c = output.pixels[yy * output.width + xx]
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
            }
        }
        return Triple(r / n, g / n, b / n)
    }

    private var overlayJob: Job? = null
    private var smartEditJob: Job? = null
    private var smartEditRunning = false

    private fun refreshMaskOverlay() {
        overlayJob?.cancel()
        val output = outcome?.output
        val item = selectedMaskId?.let { current.localAdjustments.byId(it) }
        if (!showMaskOverlay || output == null || item == null) {
            if (maskOverlay != null) {
                maskOverlay = null
                publishEdit()
            }
            return
        }
        overlayJob = viewModelScope.launch {
            maskOverlay = withContext(Dispatchers.Default) {
                // Subject and sky are found on the unedited view, as when rendering.
                val weights = LocalAdjustmentRenderer.maskOf(output, item, outcome?.originalView ?: output, outcome?.subjectHint)
                val pixels = IntArray(weights.size) { i -> ((weights[i] * OVERLAY_ALPHA).toInt() shl 24) or OVERLAY_RGB }
                BitmapConversions.toBitmap(PixelBuffer(output.width, output.height, pixels)).asImageBitmap()
            }
            publishEdit()
        }
    }

    // --- Automatic tools and clipping ---

    override fun onSmartEdit() = runSmartEdit(announce = true)

    /**
     * Finds the subject, sky and background and replaces earlier Smart edit masks with new ones
     * (the user's own masks are kept). One undo step. [announce] reports when nothing was found.
     */
    private fun runSmartEdit(announce: Boolean) {
        val currentSession = session ?: return
        smartEditJob?.cancel()
        smartEditJob = viewModelScope.launch {
            smartEditRunning = true
            publishEdit()
            val result = enhanceImage.suggestSmartEdit(currentSession, request(RenderTarget.PREVIEW))
            smartEditRunning = false
            when {
                result is OperationResult.Failure -> {
                    publishEdit()
                    setActivity(EditorActivity.Failed(result.code))
                }
                result is OperationResult.Success && result.value.isNotEmpty() -> {
                    edit(current.copy(localAdjustments = SmartEdit.merge(current.localAdjustments, result.value)))
                    onEditFinished()
                }
                else -> {
                    publishEdit()
                    if (announce) setActivity(EditorActivity.Notice(R.string.smart_edit_nothing))
                }
            }
        }
    }

    /** Sets temperature and tint so the tapped spot (normalised x, y on the edited photo) turns neutral grey. */
    override fun onPickWhiteBalance(x: Float, y: Float) {
        val (r, g, b) = sampleColor(x, y)
        val (temperature, tint) = WhiteBalanceGains.neutralizingShift(r, g, b) ?: run {
            setActivity(EditorActivity.Failed(ErrorCode.ANALYSIS_FAILED))
            return
        }
        val manual = current.manual
            .with(ManualControl.TEMPERATURE, (current.manual[ManualControl.TEMPERATURE] + temperature).coerceIn(-1f, 1f))
            .with(ManualControl.TINT, (current.manual[ManualControl.TINT] + tint).coerceIn(-1f, 1f))
        edit(current.copy(manual = manual))
        onEditFinished()
    }

    /** Levels the photo from its own straight edges (after turns, flips and lens correction). */
    override fun onAutoStraighten() {
        val source = session?.original ?: return
        val geometry = current.geometry
        viewModelScope.launch {
            val degrees = withContext(Dispatchers.Default) {
                val small = PixelResampler.downscaleToFit(source, AUTO_ANALYSIS_EDGE)
                AutoGeometry.levelDegrees(GeometryOps.apply(small, geometry.copy(straightenDegrees = 0f, crop = CropRect.FULL, perspective = Perspective.NONE)))
            }
            commitGeometry(current.geometry.straightened(degrees))
        }
    }

    /** Level plus vertical and horizontal perspective, found from the photo's straight edges. */
    override fun onAutoUpright() {
        val source = session?.original ?: return
        val geometry = current.geometry
        viewModelScope.launch {
            val perspective = withContext(Dispatchers.Default) {
                val small = PixelResampler.downscaleToFit(source, AUTO_ANALYSIS_EDGE)
                val base = GeometryOps.apply(small, geometry.copy(straightenDegrees = 0f, crop = CropRect.FULL, lens = LensCorrection.NONE, perspective = Perspective.NONE))
                val rotate = AutoGeometry.levelDegrees(OpticsWarp.apply(base, geometry.lens, Perspective.NONE))
                    .coerceIn(-Perspective.MAX_ROTATE_DEGREES, Perspective.MAX_ROTATE_DEGREES)
                AutoGeometry.upright(base, geometry.lens, rotate)
            }
            // Upright includes levelling, so a manual straighten would rotate twice.
            commitGeometry(current.geometry.withPerspective(perspective).straightened(0f))
        }
    }

    override fun onShowClippingChanged(show: Boolean) {
        showClipping = show
        publishEdit()
        val output = outcome?.output ?: return
        viewModelScope.launch {
            val bitmap = displayBitmap(output)
            _uiState.update { state -> if (state is EditorUiState.Success) state.copy(enhanced = bitmap).withExtras() else state }
        }
    }

    /** The preview as shown: with clipped highlights red and clipped shadows blue when clipping is on. */
    private suspend fun displayBitmap(output: PixelBuffer): ImageBitmap {
        if (!showClipping) return toImageBitmap(output)
        val marked = withContext(Dispatchers.Default) {
            val pixels = IntArray(output.pixelCount) { i ->
                val c = output.pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                when {
                    r >= CLIP_HIGH || g >= CLIP_HIGH || b >= CLIP_HIGH -> CLIP_HIGH_COLOR
                    r <= CLIP_LOW && g <= CLIP_LOW && b <= CLIP_LOW -> CLIP_LOW_COLOR
                    else -> c
                }
            }
            PixelBuffer(output.width, output.height, pixels)
        }
        return toImageBitmap(marked)
    }

    // --- Healing ---

    /** Tap on the photo: a new spot there, with a source chosen automatically. */
    override fun onAddSpot(x: Float, y: Float) {
        val output = outcome?.output ?: return
        // Red-eye works in place; heal and clone need a source.
        val (sx, sy) = if (healSettings.mode == RetouchMode.RED_EYE) x to y else RetouchSourceFinder.find(output, x, y, healSettings.radius)
        val id = current.retouch.nextId()
        val spot = RetouchSpot(id, x, y, sx, sy, healSettings.radius, healSettings.feather, mode = healSettings.mode)
        selectedSpotId = id
        edit(current.copy(retouch = current.retouch.with(spot)))
        onEditFinished()
    }

    /** Live while dragging a spot's handles or sliders; [onEditFinished] records the undo step. */
    override fun onSpotChanged(spot: RetouchSpot) = edit(current.copy(retouch = current.retouch.with(spot)))

    override fun onSelectSpot(id: Int?) {
        selectedSpotId = id
        publishEdit()
    }

    override fun onSpotRemoved(id: Int) {
        if (selectedSpotId == id) selectedSpotId = null
        edit(current.copy(retouch = current.retouch.without(id)))
        onEditFinished()
    }

    override fun onHealSettingsChanged(settings: HealSettings) {
        healSettings = settings
        // Size and mode also apply to the selected spot, as in other editors.
        val spot = current.retouch.spots.firstOrNull { it.id == selectedSpotId }
        if (spot != null) {
            edit(current.copy(retouch = current.retouch.with(spot.copy(radius = settings.radius, feather = settings.feather, mode = settings.mode))))
        } else {
            publishEdit()
        }
    }

    // --- Calibration, white balance mode, curve presets, panel resets ---

    /** Live while dragging; [onEditFinished] records the undo step. */
    override fun onCalibrationChanged(calibration: Calibration) = edit(current.copy(calibration = calibration.clamped()))

    override fun onResetCalibration() {
        edit(current.copy(calibration = Calibration.NONE))
        onEditFinished()
    }

    override fun onAutoWhiteBalanceChanged(enabled: Boolean) {
        edit(current.copy(autoWhiteBalance = enabled))
        onEditFinished()
    }

    override fun onCurvePresetSelected(channel: CurveChannel, preset: CurvePreset) {
        edit(current.copy(toneCurves = current.toneCurves.with(channel, preset.points)))
        onEditFinished()
    }

    override fun onResetPanel(panel: PanelReset) {
        edit(panel.reset(current))
        onEditFinished()
    }

    // --- History ---

    override fun onJumpToHistory(index: Int) {
        if (index !in history.timeline.indices || index == history.position) return
        restore(history.jumpTo(index))
    }

    // --- Mask management ---

    override fun onDuplicateMask(id: Int) {
        val duplicated = current.localAdjustments.duplicate(id)
        if (duplicated == current.localAdjustments) return
        val index = duplicated.items.indexOfFirst { it.id == id }
        selectedMaskId = duplicated.items[index + 1].id
        edit(current.copy(localAdjustments = duplicated))
        onEditFinished()
        refreshMaskOverlay()
    }

    override fun onRenameMask(id: Int, name: String) {
        edit(current.copy(localAdjustments = current.localAdjustments.renamed(id, name)))
        onEditFinished()
    }

    // --- Batch: apply these settings to other photos ---

    override fun onBatchGroupsChosen(groups: Set<SettingsGroup>) {
        pendingBatchGroups = groups
    }

    /** Photos picked for a batch: each gets the chosen settings and is saved as a new file. */
    fun onBatchPhotosPicked(uris: List<Uri>) {
        if (uris.isEmpty() || batch?.running == true) return
        val ids = uris.map(Uri::toString).distinct().take(BatchExportUseCase.MAX_ITEMS)
        val settingsSnapshot = current
        val groups = pendingBatchGroups
        val options = project?.exportOptions ?: settings.export
        batch = BatchProgress(total = ids.size, done = 0, saved = 0, failed = 0, running = true, skipped = uris.size - ids.size)
        publishEdit()
        batchJob = viewModelScope.launch {
            val results = batchExport.run(ids, settingsSnapshot, groups, options, QualityPreset.byId(settings.presetId)) { done, total ->
                batch = batch?.copy(done = done, total = total)
                publishEdit()
            }
            batch = batch?.copy(
                done = results.size,
                saved = results.count { it is BatchItemResult.Saved },
                failed = results.count { it is BatchItemResult.Failed },
                running = false,
            )
            publishEdit()
        }
    }

    override fun onCancelBatch() {
        batchJob?.cancel()
        batchJob = null
        batch = batch?.copy(running = false, cancelled = true)
        publishEdit()
    }

    override fun onDismissBatch() {
        if (batch?.running == true) return
        batch = null
        publishEdit()
    }

    override fun onResetColorMixer() {
        edit(current.copy(colorMixer = ColorMixer.NONE))
        onEditFinished()
    }

    /** null returns to the detected scene. */
    override fun onSceneSelected(scene: SceneType?) {
        edit(current.copy(sceneOverride = scene))
        onEditFinished()
    }

    override fun onResetControl(control: ManualControl) {
        edit(current.copy(manual = current.manual.with(control, 0f)))
        onEditFinished()
    }

    /** Back to the automatic correction at the default strength. */
    override fun onResetAll() {
        appliedPreset = null
        presetBase = null
        disabledStages = emptySet()
        runUntilStageId = null
        edit(EditState(EnhancementStrength.DEFAULT))
        onEditFinished()
    }

    /** The true zero state: no enhancement, no adjustments, no crop — the original appearance. */
    override fun onShowOriginalEdit() {
        edit(EditState.ORIGINAL)
        onEditFinished()
    }

    override fun onUndo() = restore(history.undo())

    override fun onRedo() = restore(history.redo())

    private fun restore(state: EditState?) {
        current = state ?: return
        refreshHistoryLabels()
        appliedPreset = null
        presetBase = null
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
                attempt { projectManager.recordHistory(latest, snapshot) }
                    .onSuccess { updated -> if (project?.id == updated.id) project = updated }
                outcome?.output?.let { attempt { thumbnails.save(saved.id, it) } }
            }
        }
    }

    /** Save opens the export dialog with the last-used options. */
    override fun onSave() {
        val state = _uiState.value as? EditorUiState.Success ?: return
        if (state.activity is EditorActivity.Saving) return
        showExportDialog(project?.exportOptions ?: settings.export)
    }

    override fun onExportOptionsChanged(options: ExportOptions) = showExportDialog(options)

    override fun onExportDismissed() {
        _uiState.update { state -> if (state is EditorUiState.Success) state.copy(exportDialog = null) else state }
    }

    override fun onExportConfirmed() {
        val currentSession = session ?: return
        val options = (_uiState.value as? EditorUiState.Success)?.exportDialog?.options ?: return
        saveSettings(settings.copy(export = options))
        _uiState.update { state -> if (state is EditorUiState.Success) state.copy(exportDialog = null, activity = EditorActivity.Saving(0f)) else state }
        exportJob = viewModelScope.launch {
            val listener = progressListener { setActivity(EditorActivity.Saving(it)) }
            when (val exported = enhanceImage.export(currentSession, fullRequest(), options, listener)) {
                is OperationResult.Failure -> setActivity(EditorActivity.Failed(exported.code))
                is OperationResult.Success -> {
                    val result = exported.value
                    setActivity(EditorActivity.Saved(result.saved.displayName, Uri.parse(result.saved.id), result.width, result.height))
                    project?.let { saved ->
                        attempt { projectManager.recordExport(saved, options, current) }.onSuccess { project = it }
                    }
                }
            }
        }
    }

    /** The saver deletes any partial file when cancelled, so nothing half-written reaches the gallery. */
    override fun onCancelExport() {
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

    override fun onViewSaved(uri: Uri) {
        viewModelScope.launch { _events.send(EditorEvent.ViewImage(uri)) }
    }

    override fun onShare() {
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
    override fun onCloseRequested() {
        if (settings.confirmBeforeLeaving && project?.hasUnexportedChanges == true) {
            _uiState.update { state -> if (state is EditorUiState.Success) state.copy(confirmLeave = true) else state }
        } else {
            onClose()
        }
    }

    override fun onLeaveDismissed() {
        _uiState.update { state -> if (state is EditorUiState.Success) state.copy(confirmLeave = false) else state }
    }

    override fun onClose() {
        openJob?.cancel()
        exportJob?.cancel()
        batchJob?.cancel()
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
            if (state is EditorUiState.Success) state.copy(edit = current, canUndo = history.canUndo, canRedo = history.canRedo).withExtras() else state
        }
    }

    /** Editor-only state that lives in the view model, copied into every published state. */
    private fun EditorUiState.Success.withExtras() = copy(
        userPresets = userPresets,
        appliedPreset = appliedPreset,
        versions = project?.versions.orEmpty(),
        canPaste = copiedSettings != null,
        selectedMaskId = selectedMaskId?.takeIf { id -> current.localAdjustments.byId(id) != null },
        maskOverlay = if (showMaskOverlay) maskOverlay else null,
        showMaskOverlay = showMaskOverlay,
        selectedSpotId = selectedSpotId?.takeIf { id -> current.retouch.spots.any { it.id == id } },
        healSettings = healSettings,
        showClipping = showClipping,
        smartEditRunning = smartEditRunning,
        historyLabels = historyLabelCache,
        historyPosition = history.position,
        batch = batch,
    )

    /** One label per history step, oldest first: what that step changed. */
    private fun refreshHistoryLabels() {
        val timeline = history.timeline
        historyLabelCache = timeline.mapIndexed { index, state -> if (index == 0) HISTORY_START else EditDiff.describe(timeline[index - 1], state) }
    }

    private fun requestPreview() {
        if (session == null) return
        previewRequests.value = request(RenderTarget.PREVIEW)
    }

    /** Every edit field goes through [toRequest]; only preview-specific overrides are added here. */
    private fun request(target: RenderTarget) = current.toRequest(target).copy(
        geometry = if (cropMode && target == RenderTarget.PREVIEW) current.geometry.withoutCrop() else current.geometry,
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
                val enhanced = displayBitmap(result.value.output)
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
                    ).withExtras()
                }
                refreshMaskOverlay()
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
        presetBase = null
        appliedPreset = null
        selectedMaskId = null
        showMaskOverlay = false
        maskOverlay = null
        selectedSpotId = null
        lastPresetResult = null
        historyLabelCache = emptyList()
        disabledStages = emptySet()
        runUntilStageId = null
    }

    /**
     * Like runCatching, but never swallows coroutine cancellation (which would keep a cancelled
     * job running). Used for storage work whose failure must not break editing.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    companion object {
        /** Coalesces rapid slider movement into one render. */
        private const val PREVIEW_DEBOUNCE_MS = 60L
        private const val USER_PRESET_CATEGORY = "Yours"
        private const val HISTORY_START = "Opened"
        private const val OVERLAY_ALPHA = 150f
        private const val OVERLAY_RGB = 0xE5484D
        private const val AUTO_ANALYSIS_EDGE = 800
        private const val CLIP_HIGH = 254
        private const val CLIP_LOW = 1
        private const val CLIP_HIGH_COLOR = 0xFFFF2D2D.toInt()
        private const val CLIP_LOW_COLOR = 0xFF2D6BFF.toInt()

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                EditorViewModel(
                    enhanceImage = container.enhanceImageUseCase,
                    settingsRepository = container.settingsRepository,
                    shareCache = container.shareCache,
                    projectManager = container.projectManager,
                    thumbnails = container.thumbnails,
                    presetStore = container.presetStore,
                    batchExport = container.batchExport,
                    incomingImages = container.incomingImages,
                    appStorage = container.appStorage,
                    isDebugBuild = container.isDebugBuild,
                )
            }
        }
    }
}
