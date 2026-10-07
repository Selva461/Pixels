package com.pixels.enhancer.ui.editor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.analysis.SceneType
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.planning.HslShift
import com.pixels.enhancer.domain.planning.HueBand
import com.pixels.enhancer.ui.adjust.AdjustPanel
import com.pixels.enhancer.ui.adjust.ColorMixerPanel
import com.pixels.enhancer.ui.adjust.CurvePanel
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.ui.local.LocalActions
import com.pixels.enhancer.ui.local.LocalMaskEditor
import com.pixels.enhancer.ui.local.LocalPanel
import com.pixels.enhancer.domain.planning.CurveChannel
import com.pixels.enhancer.domain.planning.CurvePoints
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.geometry.CropRect
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.planning.Look
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.ui.ErrorMessages
import com.pixels.enhancer.ui.compare.CompareMode
import com.pixels.enhancer.ui.compare.CompareView
import com.pixels.enhancer.ui.compare.SplitOrientation
import com.pixels.enhancer.ui.crop.CropEditor
import com.pixels.enhancer.ui.export.ExportDialog
import java.util.Locale
import kotlin.math.roundToInt

private const val PERCENT = 100

/** Fixed panel height keeps the photo the same size whichever tab is open. */
private val PANEL_HEIGHT = 230.dp

private enum class EditorTab(val titleRes: Int) {
    AUTO(R.string.editor_tab_auto),
    LOOKS(R.string.editor_tab_looks),
    ADJUST(R.string.editor_tab_adjust),
    COLOR(R.string.editor_tab_color),
    CURVE(R.string.editor_tab_curve),
    LOCAL(R.string.editor_tab_local),
    CROP(R.string.editor_tab_crop),
}

/** Callbacks for the export dialog and running export. */
class ExportActions(
    val onOptionsChanged: (ExportOptions) -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
    val onCancel: () -> Unit,
)

/** Callbacks for the Local tab; onAdd returns the new mask's id so it can be selected. */
class LocalEditorActions(
    val onAdd: (radial: Boolean) -> Int,
    val onChanged: (LocalAdjustment) -> Unit,
    val onRemove: (Int) -> Unit,
)

/** Callbacks for scene choice and the colour mixer. */
class ColorActions(
    val onShiftChanged: (HueBand, HslShift) -> Unit,
    val onResetAll: () -> Unit,
    val onSceneSelected: (SceneType?) -> Unit,
    val onCurveChanged: (CurveChannel, CurvePoints) -> Unit,
    val onCurveReset: (CurveChannel) -> Unit,
)

/** Callbacks for the Crop tab, grouped to keep EditorScreen's signature readable. */
class CropActions(
    val onRotateClockwise: () -> Unit,
    val onRotateCounterClockwise: () -> Unit,
    val onFlip: () -> Unit,
    val onFlipVertical: () -> Unit,
    val onStraightenChanged: (Float) -> Unit,
    val onCropChanged: (CropRect) -> Unit,
    val onAspectSelected: (CropAspect) -> Unit,
    val onReset: () -> Unit,
    val onCropModeChanged: (Boolean) -> Unit,
    val ratioFor: (CropAspect) -> Float?,
)

@Composable
fun EditorScreen(
    state: EditorUiState.Success,
    onClose: () -> Unit,
    onConfirmLeave: () -> Unit,
    onDismissLeave: () -> Unit,
    onRedo: () -> Unit,
    onShowOriginalEdit: () -> Unit,
    onStrengthChanged: (Float) -> Unit,
    onControlChanged: (ManualControl, Float) -> Unit,
    onEditFinished: () -> Unit,
    onLookSelected: (Look) -> Unit,
    onResetControl: (ManualControl) -> Unit,
    onResetAll: () -> Unit,
    onUndo: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onViewSaved: (Uri) -> Unit,
    crop: CropActions,
    export: ExportActions,
    color: ColorActions,
    local: LocalEditorActions,
    onOpenDebug: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var mode by rememberSaveable { mutableStateOf(CompareMode.COMPARE) }
    var split by rememberSaveable { mutableStateOf(SplitOrientation.HORIZONTAL) }
    var tab by rememberSaveable { mutableStateOf(EditorTab.AUTO) }
    var viewResetKey by rememberSaveable { mutableIntStateOf(0) }
    var selectedLocalId by rememberSaveable { mutableStateOf<Int?>(null) }
    val selectedLocal = state.edit.localAdjustments.items.firstOrNull { it.id == selectedLocalId }
    val snackbarHostState = remember { SnackbarHostState() }
    BackHandler(onBack = onClose)
    ResultSnackbar(state.activity, snackbarHostState, onViewSaved)
    LaunchedEffect(tab) { crop.onCropModeChanged(tab == EditorTab.CROP) }
    state.exportDialog?.let { dialog ->
        ExportDialog(dialog, export.onOptionsChanged, export.onConfirm, export.onDismiss)
    }
    if (state.confirmLeave) {
        AlertDialog(
            onDismissRequest = onDismissLeave,
            title = { Text(stringResource(R.string.leave_title)) },
            text = { Text(stringResource(R.string.leave_message)) },
            confirmButton = { TextButton(onClick = onConfirmLeave) { Text(stringResource(R.string.leave_confirm)) } },
            dismissButton = { Button(shape = MaterialTheme.shapes.small, onClick = onDismissLeave) { Text(stringResource(R.string.leave_stay)) } },
        )
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state, onClose, onUndo, onRedo, onShare, onSave, onOpenDebug)
            if (tab == EditorTab.LOCAL) {
                LocalMaskEditor(
                    image = state.enhanced,
                    selected = selectedLocal,
                    onShapeChanged = { shape -> selectedLocal?.let { local.onChanged(it.copy(shape = shape)) } },
                    onFinished = onEditFinished,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (tab == EditorTab.CROP && state.cropMode) {
                CropEditor(
                    image = state.enhanced,
                    crop = state.edit.geometry.crop,
                    pixelRatio = crop.ratioFor(state.cropAspect),
                    onCropChanged = crop.onCropChanged,
                    onCropFinished = onEditFinished,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                CompareView(
                    original = state.original,
                    enhanced = state.enhanced,
                    mode = mode,
                    split = split,
                    beforeLabel = stringResource(R.string.editor_before),
                    afterLabel = stringResource(R.string.editor_after),
                    resetKey = viewResetKey,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            ActivityLine(state.activity, export.onCancel)
            if (tab != EditorTab.CROP && tab != EditorTab.LOCAL) ModeSelector(mode, split, onModeChange = { mode = it }, onSplitChange = { split = it })
            ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                EditorTab.entries.forEach { entry ->
                    Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(stringResource(entry.titleRes), maxLines = 1) })
                }
            }
            Box(Modifier.fillMaxWidth().height(PANEL_HEIGHT)) {
                when (tab) {
                    EditorTab.AUTO -> AutoPanel(state, color.onSceneSelected, onStrengthChanged, onEditFinished, onShowOriginalEdit, onResetAll = {
                        viewResetKey++
                        onResetAll()
                    })
                    EditorTab.LOOKS -> LooksPanel(state.edit.lookId, onLookSelected)
                    EditorTab.ADJUST -> AdjustPanel(state.edit, state.histogram, onControlChanged, onEditFinished, onResetControl)
                    EditorTab.COLOR -> ColorMixerPanel(state.edit, color.onShiftChanged, onEditFinished, color.onResetAll)
                    EditorTab.CURVE -> CurvePanel(state.edit, state.histogram, color.onCurveChanged, onEditFinished, color.onCurveReset)
                    EditorTab.LOCAL -> LocalPanel(
                        adjustments = state.edit.localAdjustments,
                        selectedId = selectedLocalId,
                        actions = LocalActions(
                            onAddRadial = { selectedLocalId = local.onAdd(true) },
                            onAddLinear = { selectedLocalId = local.onAdd(false) },
                            onChanged = local.onChanged,
                            onRemove = { id ->
                                local.onRemove(id)
                                selectedLocalId = null
                            },
                            onSelect = { selectedLocalId = it },
                        ),
                        onEditFinished = onEditFinished,
                    )
                    EditorTab.CROP -> CropPanel(state.edit, state.cropAspect, crop, onEditFinished)
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun TopBar(
    state: EditorUiState.Success,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onOpenDebug: (() -> Unit)?,
) {
    val busy = state.activity is EditorActivity.Saving
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClose) { Text(stringResource(R.string.editor_close)) }
        if (onOpenDebug != null) TextButton(onClick = onOpenDebug) { Text(stringResource(R.string.editor_debug)) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onUndo, enabled = state.canUndo) { Text(stringResource(R.string.editor_undo)) }
        TextButton(onClick = onRedo, enabled = state.canRedo) { Text(stringResource(R.string.editor_redo)) }
        TextButton(onClick = onShare, enabled = !busy) { Text(stringResource(R.string.editor_share)) }
        Button(shape = MaterialTheme.shapes.small, onClick = onSave, enabled = !busy) { Text(stringResource(R.string.editor_save)) }
    }
}

@Composable
private fun ResultSnackbar(activity: EditorActivity, hostState: SnackbarHostState, onViewSaved: (Uri) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(activity) {
        when (activity) {
            is EditorActivity.Saved -> {
                val result = hostState.showSnackbar(
                    message = context.getString(R.string.editor_saved, activity.displayName, activity.width, activity.height),
                    actionLabel = context.getString(R.string.editor_view),
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) onViewSaved(activity.uri)
            }
            is EditorActivity.Failed -> hostState.showSnackbar(context.getString(ErrorMessages.forCode(activity.code)))
            else -> Unit
        }
    }
}

@Composable
private fun AutoPanel(
    state: EditorUiState.Success,
    onSceneSelected: (SceneType?) -> Unit,
    onStrengthChanged: (Float) -> Unit,
    onEditFinished: () -> Unit,
    onShowOriginalEdit: () -> Unit,
    onResetAll: () -> Unit,
) {
    val strength = state.edit.strength
    val override = state.edit.sceneOverride
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.editor_strength, (strength * PERCENT).roundToInt()), style = MaterialTheme.typography.titleSmall)
        Slider(value = strength, onValueChange = onStrengthChanged, onValueChangeFinished = onEditFinished)
        Text(stringResource(R.string.editor_auto_hint), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.editor_hold_hint), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(R.string.editor_scene, (override ?: state.detectedScene).label, state.detectedScene.label),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(override == null, { onSceneSelected(null) }, { Text(stringResource(R.string.editor_scene_auto)) })
            SceneType.entries.forEach { scene ->
                FilterChip(override == scene, { onSceneSelected(scene) }, { Text(scene.label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onResetAll) { Text(stringResource(R.string.editor_reset_all)) }
            TextButton(onClick = onShowOriginalEdit) { Text(stringResource(R.string.editor_original_state)) }
        }
    }
}

@Composable
private fun LooksPanel(selectedLookId: String, onLookSelected: (Look) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Look.ALL.forEach { look ->
            FilterChip(selected = look.id == selectedLookId, onClick = { onLookSelected(look) }, label = { Text(look.name) })
        }
    }
}

@Composable
private fun CropPanel(edit: EditState, aspect: CropAspect, crop: CropActions, onEditFinished: () -> Unit) {
    val straighten = edit.geometry.straightenDegrees
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = crop.onRotateCounterClockwise) { Text(stringResource(R.string.crop_rotate_left), maxLines = 1) }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = crop.onRotateClockwise) { Text(stringResource(R.string.crop_rotate_right), maxLines = 1) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = crop.onFlip) { Text(stringResource(R.string.crop_flip), maxLines = 1) }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = crop.onFlipVertical) { Text(stringResource(R.string.crop_flip_vertical), maxLines = 1) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.crop_straighten, String.format(Locale.ROOT, "%.1f", straighten)),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = crop.onReset) { Text(stringResource(R.string.crop_reset)) }
        }
        Slider(
            value = straighten,
            onValueChange = crop.onStraightenChanged,
            onValueChangeFinished = onEditFinished,
            valueRange = -Geometry.MAX_STRAIGHTEN_DEGREES..Geometry.MAX_STRAIGHTEN_DEGREES,
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CropAspect.entries.forEach { option ->
                FilterChip(selected = option == aspect, onClick = { crop.onAspectSelected(option) }, label = { Text(option.label) })
            }
        }
    }
}


@Composable
private fun ModeSelector(
    mode: CompareMode,
    split: SplitOrientation,
    onModeChange: (CompareMode) -> Unit,
    onSplitChange: (SplitOrientation) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(mode == CompareMode.ORIGINAL, { onModeChange(CompareMode.ORIGINAL) }, { Text(stringResource(R.string.editor_mode_original)) })
        FilterChip(mode == CompareMode.ENHANCED, { onModeChange(CompareMode.ENHANCED) }, { Text(stringResource(R.string.editor_mode_enhanced)) })
        FilterChip(mode == CompareMode.COMPARE, { onModeChange(CompareMode.COMPARE) }, { Text(stringResource(R.string.editor_mode_compare)) })
        if (mode == CompareMode.COMPARE) {
            FilterChip(
                split == SplitOrientation.HORIZONTAL,
                { onSplitChange(SplitOrientation.HORIZONTAL) },
                { Text(stringResource(R.string.editor_split_horizontal)) },
            )
            FilterChip(
                split == SplitOrientation.VERTICAL,
                { onSplitChange(SplitOrientation.VERTICAL) },
                { Text(stringResource(R.string.editor_split_vertical)) },
            )
        }
    }
}

@Composable
private fun ActivityLine(activity: EditorActivity, onCancelExport: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    when (activity) {
        is EditorActivity.Reprocessing -> Column(modifier) {
            LinearProgressIndicator(progress = { activity.progress }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.editor_updating), style = MaterialTheme.typography.bodySmall)
        }
        is EditorActivity.Saving -> Column(modifier) {
            LinearProgressIndicator(progress = { activity.progress }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.editor_saving), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancelExport) { Text(stringResource(R.string.editor_cancel_export)) }
            }
        }
        else -> Spacer(Modifier.height(4.dp))
    }
}
