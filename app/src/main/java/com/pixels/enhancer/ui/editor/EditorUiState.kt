package com.pixels.enhancer.ui.editor

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.domain.debug.DebugSection

/** Single state model for the whole flow — no independent isLoading / hasError flags. */
sealed interface EditorUiState {

    data object Idle : EditorUiState

    data object Loading : EditorUiState

    /** First enhancement of a newly opened photo. */
    data class Processing(val stageName: String, val progress: Float) : EditorUiState

    data class Success(
        val original: ImageBitmap,
        val enhanced: ImageBitmap,
        val strength: Float,
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
    data class Reprocessing(val stageName: String, val progress: Float) : EditorActivity
    data object Saving : EditorActivity
    data class Saved(val displayName: String) : EditorActivity
    data class Failed(val code: ErrorCode) : EditorActivity
}

data class DebugInfo(
    val sections: List<DebugSection>,
    val stageIds: List<String>,
    val disabledStages: Set<String>,
    val runUntilStageId: String?,
)

sealed interface EditorEvent {
    data class ShareImage(val uri: Uri) : EditorEvent
    data class ShareText(val text: String) : EditorEvent
}
