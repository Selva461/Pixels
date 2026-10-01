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
import com.pixels.enhancer.domain.debug.DebugReport
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.model.OutputNaming
import com.pixels.enhancer.domain.planning.EnhancementStrength
import com.pixels.enhancer.domain.planning.QualityPreset
import com.pixels.enhancer.domain.processing.ProcessingListener
import com.pixels.enhancer.domain.processing.ProcessingStage
import com.pixels.enhancer.domain.processing.StageConfig
import com.pixels.enhancer.domain.repository.SettingsRepository
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.usecase.EnhanceRequest
import com.pixels.enhancer.domain.usecase.EnhancementOutcome
import com.pixels.enhancer.domain.usecase.EnhancementSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates user actions with [EnhanceImageUseCase] and publishes [EditorUiState]. Holds no
 * image-processing logic: it only sequences calls and converts results for display.
 */
class EditorViewModel(
    private val enhanceImage: EnhanceImageUseCase,
    private val settingsRepository: SettingsRepository,
    private val shareCache: ShareCache,
    private val isDebugBuild: Boolean,
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Idle)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events: Flow<EditorEvent> = _events.receiveAsFlow()

    private var settings = settingsRepository.load()
    private var session: EnhancementSession? = null
    private var outcome: EnhancementOutcome? = null
    private var originalBitmap: ImageBitmap? = null
    private var previousStrength: Float? = null
    private var disabledStages: Set<String> = emptySet()
    private var runUntilStageId: String? = null
    private var work: Job? = null

    fun onImagePicked(uri: Uri) {
        work?.cancel()
        clearSession()
        work = viewModelScope.launch {
            _uiState.value = EditorUiState.Loading
            when (val opened = enhanceImage.open(uri.toString(), QualityPreset.byId(settings.presetId))) {
                is OperationResult.Failure -> _uiState.value = EditorUiState.Error(opened.code)
                is OperationResult.Success -> {
                    session = opened.value
                    originalBitmap = toImageBitmap(opened.value.original)
                    enhance()
                }
            }
        }
    }

    /** Moves the slider without reprocessing; processing happens when the drag ends. */
    fun onStrengthChanged(value: Float) {
        _uiState.update { state -> if (state is EditorUiState.Success) state.copy(strength = value) else state }
    }

    fun onStrengthChangeFinished() {
        val requested = (_uiState.value as? EditorUiState.Success)?.strength ?: return
        if (requested == settings.strength) return
        applyStrength(requested, rememberForUndo = true)
    }

    fun onUndo() {
        val target = previousStrength ?: return
        previousStrength = null
        applyStrength(target, rememberForUndo = false)
    }

    fun onReset() {
        disabledStages = emptySet()
        runUntilStageId = null
        applyStrength(EnhancementStrength.DEFAULT, rememberForUndo = true)
    }

    fun onSave() {
        val currentSession = session ?: return
        val currentOutcome = outcome ?: return
        setActivity(EditorActivity.Saving)
        viewModelScope.launch {
            when (val saved = enhanceImage.save(currentSession, currentOutcome)) {
                is OperationResult.Failure -> setActivity(EditorActivity.Failed(saved.code))
                is OperationResult.Success -> setActivity(EditorActivity.Saved(saved.value.displayName))
            }
        }
    }

    fun onShare() {
        val currentSession = session ?: return
        val currentOutcome = outcome ?: return
        viewModelScope.launch {
            val name = OutputNaming.enhancedName(currentSession.source.displayName)
            when (val written = runControlled(ErrorCode.OUTPUT_ENCODE_FAILED) { shareCache.write(currentOutcome.processed.image, name) }) {
                is OperationResult.Failure -> setActivity(EditorActivity.Failed(written.code))
                is OperationResult.Success -> _events.send(EditorEvent.ShareImage(written.value))
            }
        }
    }

    fun onStageToggled(stageId: String, enabled: Boolean) {
        disabledStages = if (enabled) disabledStages - stageId else disabledStages + stageId
        reprocess()
    }

    fun onRunUntilSelected(stageId: String?) {
        runUntilStageId = stageId
        reprocess()
    }

    fun onExportDebugReport() {
        val currentSession = session ?: return
        viewModelScope.launch { _events.send(EditorEvent.ShareText(DebugReport.format(currentSession, outcome))) }
    }

    fun onClose() {
        work?.cancel()
        clearSession()
        _uiState.value = EditorUiState.Idle
    }

    private fun applyStrength(strength: Float, rememberForUndo: Boolean) {
        if (rememberForUndo) previousStrength = settings.strength
        settings = settings.copy(strength = strength)
        settingsRepository.save(settings)
        reprocess()
    }

    private fun reprocess() {
        if (session == null) return
        work?.cancel()
        work = viewModelScope.launch { enhance() }
    }

    private suspend fun enhance() {
        val currentSession = session ?: return
        val original = originalBitmap ?: return
        val request = EnhanceRequest(
            strength = settings.strength,
            debugEnabled = isDebugBuild,
            stageConfigs = disabledStages.associateWith { StageConfig(enabled = false) },
            runUntilStageId = runUntilStageId,
        )
        when (val result = enhanceImage.enhance(currentSession, request, progressListener())) {
            is OperationResult.Failure -> showFailure(result.code)
            is OperationResult.Success -> {
                outcome = result.value
                _uiState.value = EditorUiState.Success(
                    original = original,
                    enhanced = toImageBitmap(result.value.processed.image),
                    strength = settings.strength,
                    canUndo = previousStrength != null,
                    activity = EditorActivity.None,
                    debug = debugInfo(currentSession, result.value),
                )
            }
        }
    }

    /** A failed re-run keeps the last good result on screen; a failed first run shows the error screen. */
    private fun showFailure(code: ErrorCode) {
        _uiState.update { state ->
            if (state is EditorUiState.Success) state.copy(activity = EditorActivity.Failed(code)) else EditorUiState.Error(code)
        }
    }

    private fun progressListener() = object : ProcessingListener {
        override fun onStageStarted(stage: ProcessingStage, index: Int, total: Int) {
            val progress = index / total.toFloat()
            _uiState.update { state ->
                when (state) {
                    is EditorUiState.Success -> state.copy(activity = EditorActivity.Reprocessing(stage.displayName, progress))
                    is EditorUiState.Loading, is EditorUiState.Processing -> EditorUiState.Processing(stage.displayName, progress)
                    else -> state
                }
            }
        }

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
        session = null
        outcome = null
        originalBitmap = null
        previousStrength = null
        disabledStages = emptySet()
        runUntilStageId = null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                EditorViewModel(
                    enhanceImage = container.enhanceImageUseCase,
                    settingsRepository = container.settingsRepository,
                    shareCache = container.shareCache,
                    isDebugBuild = container.isDebugBuild,
                )
            }
        }
    }
}

