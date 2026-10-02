package com.pixels.enhancer.ui.editor

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.domain.debug.DebugSection
import com.pixels.enhancer.domain.planning.Look
import com.pixels.enhancer.domain.planning.ManualAdjustments

/** Single state model for the whole flow — no independent isLoading / hasError flags. */
sealed interface EditorUiState {

    data object Idle : EditorUiState

    data object Loading : EditorUiState

    /** First enhancement of a newly opened photo. */
    data class Processing(val progress: Float) : EditorUiState

    data class Success(
        val original: ImageBitmap,
        val enhanced: ImageBitmap,
        val edit: EditState,
        val canUndo: Boolean,
        val activity: EditorActivity,
        /** Present only in developer builds. */
        val debug: DebugInfo?,
    ) : EditorUiState

    data class Error(val code: ErrorCode) : EditorUiState
}

/** What the editor is doing on top of showing a result. */
sealed interface EditorActivity {
    data object None : EditorActivity
    data class Reprocessing(val progress: Float) : EditorActivity
    data class Saving(val progress: Float) : EditorActivity
    data class Saved(val displayName: String, val uri: Uri) : EditorActivity
    data class Failed(val code: ErrorCode) : EditorActivity
}

/** Everything the user controls. Undo restores a previous EditState. */
data class EditState(
    val strength: Float,
    val manual: ManualAdjustments = ManualAdjustments.NONE,
    val lookId: String = Look.NONE.id,
)

data class DebugInfo(
    val sections: List<DebugSection>,
    val stageIds: List<String>,
    val disabledStages: Set<String>,
    val runUntilStageId: String?,
)

sealed interface EditorEvent {
    data class ShareImage(val uri: Uri) : EditorEvent
    data class ShareText(val text: String) : EditorEvent
    data class ViewImage(val uri: Uri) : EditorEvent
}
