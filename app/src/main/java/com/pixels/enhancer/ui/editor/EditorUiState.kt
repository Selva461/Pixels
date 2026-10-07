package com.pixels.enhancer.ui.editor

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.domain.analysis.Histogram
import com.pixels.enhancer.domain.analysis.SceneType
import com.pixels.enhancer.domain.debug.DebugSection
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.presets.Preset
import com.pixels.enhancer.domain.project.EditVersion
import com.pixels.enhancer.domain.retouch.RetouchMode
import com.pixels.enhancer.domain.retouch.RetouchSpot

/** Single state model for the whole flow — no independent isLoading / hasError flags. */
sealed interface EditorUiState {

    /** Home screen, with recent projects to resume. */
    data class Idle(val recent: List<RecentProject> = emptyList(), val recentLoaded: Boolean = false) : EditorUiState

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
        val canRedo: Boolean,
        val cropMode: Boolean,
        val cropAspect: CropAspect,
        /** Non-null while the export dialog is open. */
        val exportDialog: ExportDialogState? = null,
        /** True while asking whether to leave with edits that were never exported. */
        val confirmLeave: Boolean = false,
        /** Scene Auto Enhance detected; the override (if any) is in [edit]. */
        val detectedScene: SceneType = SceneType.GENERAL,
        /** Histogram of the current preview result. */
        val histogram: Histogram? = null,
        val activity: EditorActivity,
        /** Present only in developer builds. */
        val debug: DebugInfo?,
        val userPresets: List<Preset> = emptyList(),
        /** Preset applied last, with its amount slider (0..2). */
        val appliedPreset: AppliedPreset? = null,
        val versions: List<EditVersion> = emptyList(),
        /** True once settings were copied and can be pasted. */
        val canPaste: Boolean = false,
        val selectedMaskId: Int? = null,
        /** Red overlay showing where the selected mask applies, when switched on. */
        val maskOverlay: ImageBitmap? = null,
        val showMaskOverlay: Boolean = false,
        val selectedSpotId: Int? = null,
        val healSettings: HealSettings = HealSettings(),
        /** Clipped highlights/shadows are marked on [enhanced]. */
        val showClipping: Boolean = false,
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

data class AppliedPreset(val preset: Preset, val amount: Float)

/** Brush for new heal/clone spots. */
data class HealSettings(val mode: RetouchMode = RetouchMode.HEAL, val radius: Float = RetouchSpot.DEFAULT_RADIUS, val feather: Float = RetouchSpot.DEFAULT_FEATHER)

/** Kinds of mask the user can add. */
enum class MaskKind { BRUSH, LINEAR, RADIAL, LUMINANCE, COLOR }

data class ExportDialogState(val options: ExportOptions, val width: Int, val height: Int)

data class RecentProject(val id: String, val name: String, val modifiedAtMillis: Long, val thumbnail: ImageBitmap?)

/** Crop aspect presets; [ratio] is width / height in pixels, null = free. */
enum class CropAspect(val label: String, val ratio: Float?) {
    FREE("Free", null),
    ORIGINAL("Original", null),
    SQUARE("1:1", 1f),
    LANDSCAPE_4_3("4:3", 4f / 3f),
    LANDSCAPE_3_2("3:2", 3f / 2f),
    WIDE_16_9("16:9", 16f / 9f),
    PORTRAIT_4_5("4:5", 4f / 5f),
    TALL_9_16("9:16", 9f / 16f),
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
