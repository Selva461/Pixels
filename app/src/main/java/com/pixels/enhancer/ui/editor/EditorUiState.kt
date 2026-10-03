package com.pixels.enhancer.ui.editor

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.domain.debug.DebugSection
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.planning.Look
import com.pixels.enhancer.domain.planning.ManualAdjustments

/** Single state model for the whole flow — no independent isLoading / hasError flags. */
sealed interface EditorUiState {

    data object Idle : EditorUiState

    data object Loading : EditorUiState

    /** First enhancement of a newly opened photo. */
    data class Processing(val progress: Float) : EditorUiState

    data class Success(
        /** Source with the same geometry as [enhanced], so before/after line up. */
        val original: ImageBitmap,
        /** In crop mode this is the uncropped frame the crop rectangle is drawn on. */
        val enhanced: ImageBitmap,
        val edit: EditState,
        val canUndo: Boolean,
        val cropMode: Boolean,
        val cropAspect: CropAspect,
        /** Non-null while the export dialog is open. */
        val exportDialog: ExportDialogState? = null,
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
    data class Saved(val displayName: String, val uri: Uri, val width: Int, val height: Int) : EditorActivity
    data class Failed(val code: ErrorCode) : EditorActivity
}

data class ExportDialogState(val options: ExportOptions, val width: Int, val height: Int)

/** Everything the user controls. Undo restores a previous EditState. */
data class EditState(
    val strength: Float,
    val manual: ManualAdjustments = ManualAdjustments.NONE,
    val lookId: String = Look.NONE.id,
    val geometry: Geometry = Geometry.NONE,
)

/** Crop aspect presets; [ratio] is width / height in pixels, null = free. */
enum class CropAspect(val label: String, val ratio: Float?) {
    FREE("Free", null),
    ORIGINAL("Original", null),
    SQUARE("1:1", 1f),
    PORTRAIT_4_5("4:5", 4f / 5f),
    LANDSCAPE_3_2("3:2", 3f / 2f),
    WIDE_16_9("16:9", 16f / 9f),
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
    data class ViewImage(val uri: Uri) : EditorEvent
}
