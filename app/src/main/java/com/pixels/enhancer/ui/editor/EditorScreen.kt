package com.pixels.enhancer.ui.editor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.presets.SettingsGroup
import com.pixels.enhancer.ui.ErrorMessages
import com.pixels.enhancer.ui.compare.CompareMode
import com.pixels.enhancer.ui.compare.CompareView
import com.pixels.enhancer.ui.compare.SplitOrientation
import com.pixels.enhancer.ui.components.StateIconToggle
import com.pixels.enhancer.ui.crop.CropEditor
import com.pixels.enhancer.ui.export.ExportDialog
import com.pixels.enhancer.ui.local.BrushMode
import com.pixels.enhancer.ui.local.BrushSettings
import com.pixels.enhancer.ui.local.MaskCanvas
import com.pixels.enhancer.ui.local.MaskingPanel
import com.pixels.enhancer.ui.panels.AutoPanel
import com.pixels.enhancer.ui.panels.ColorPanel
import com.pixels.enhancer.ui.panels.CropPanel
import com.pixels.enhancer.ui.panels.DetailPanel
import com.pixels.enhancer.ui.panels.EffectsPanel
import com.pixels.enhancer.ui.panels.GeometryPanel
import com.pixels.enhancer.ui.panels.HistoryPanel
import com.pixels.enhancer.ui.panels.LightPanel
import com.pixels.enhancer.ui.panels.OpticsPanel
import com.pixels.enhancer.ui.panels.PanelControls
import com.pixels.enhancer.ui.panels.PresetsPanel
import com.pixels.enhancer.ui.retouch.HealCanvas
import com.pixels.enhancer.ui.retouch.HealingPanel
import com.pixels.enhancer.ui.theme.OnPhotoCanvas
import com.pixels.enhancer.ui.theme.PhotoCanvas
import com.pixels.enhancer.ui.theme.PhotoLabelBacking
import kotlin.math.min

/** Fixed panel height keeps the photo the same size whichever tool is open (portrait layout). */
private val PANEL_HEIGHT = 270.dp

/** Width of the side column holding the panel and tools in landscape. */
private val SIDE_PANEL_WIDTH = 360.dp

private enum class NamePrompt { PRESET, VERSION }

/** Which settings-group dialog is open: paste onto this photo, or apply to other photos. */
private enum class GroupsDialog { PASTE, BATCH }

/**
 * The editor. Portrait: photo on top, the open tool's panel below it, the tool strip at the
 * bottom. Landscape (phones on their side, tablets): photo on the left, panel and tools on the
 * right, so the photo never shrinks to a sliver. Press and hold the photo to see the original.
 */
@Composable
fun EditorScreen(
    state: EditorUiState.Success,
    actions: EditorActions,
    onOpenDebug: (() -> Unit)?,
    onPickBatchPhotos: () -> Unit,
    modifier: Modifier = Modifier,
    initialTool: EditorTool = EditorTool.PRESETS,
) {
    var tool by rememberSaveable { mutableStateOf(initialTool) }
    var compare by rememberSaveable { mutableStateOf(false) }
    var viewResetKey by rememberSaveable { mutableIntStateOf(0) }
    var brush by remember { mutableStateOf(BrushSettings()) }
    var namePrompt by remember { mutableStateOf<NamePrompt?>(null) }
    var groupsDialog by remember { mutableStateOf<GroupsDialog?>(null) }
    var pickingWhiteBalance by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(onBack = actions::onCloseRequested)
    ResultSnackbar(state.activity, snackbarHostState, actions::onViewSaved)
    LaunchedEffect(tool) {
        actions.onCropModeChanged(tool == EditorTool.CROP)
        if (tool != EditorTool.MASKING) brush = brush.copy(mode = BrushMode.OFF)
        if (tool != EditorTool.COLOR) pickingWhiteBalance = false
    }
    Dialogs(state, actions, namePrompt, onNameDone = { namePrompt = null })
    groupsDialog?.let { which ->
        SettingsGroupsDialog(
            title = stringResource(if (which == GroupsDialog.PASTE) R.string.menu_paste_settings else R.string.batch_title),
            message = if (which == GroupsDialog.BATCH) stringResource(R.string.batch_message) else null,
            confirmLabel = stringResource(if (which == GroupsDialog.PASTE) R.string.paste else R.string.batch_choose_photos),
            onConfirm = { groups ->
                groupsDialog = null
                if (which == GroupsDialog.PASTE) {
                    actions.onPasteSettings(groups)
                } else {
                    actions.onBatchGroupsChosen(groups)
                    onPickBatchPhotos()
                }
            },
            onDismiss = { groupsDialog = null },
        )
    }
    state.batch?.let { BatchDialog(it, actions) }

    val topBar: @Composable () -> Unit = {
        TopBar(
            state = state,
            actions = actions,
            compare = compare,
            onCompareChange = {
                compare = it
                viewResetKey++
            },
            onSavePreset = { namePrompt = NamePrompt.PRESET },
            onPaste = { groupsDialog = GroupsDialog.PASTE },
            onBatch = { groupsDialog = GroupsDialog.BATCH },
            onOpenDebug = onOpenDebug,
        )
    }
    val photo: @Composable (Modifier) -> Unit = { photoModifier ->
        if (pickingWhiteBalance) {
            TapToPick(
                image = state.enhanced,
                hint = stringResource(R.string.color_pick_wb_hint),
                onPick = { x, y ->
                    actions.onPickWhiteBalance(x, y)
                    pickingWhiteBalance = false
                },
                modifier = photoModifier,
            )
        } else {
            PhotoArea(state, tool, compare, viewResetKey, brush, actions, photoModifier)
        }
    }
    val panel: @Composable (Modifier) -> Unit = { panelModifier ->
        Box(panelModifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
            ToolPanel(
                state, tool, brush, onBrushChanged = { brush = it }, actions,
                onSavePreset = { namePrompt = NamePrompt.PRESET },
                onSaveVersion = { namePrompt = NamePrompt.VERSION },
                pickingWhiteBalance = pickingWhiteBalance,
                onPickWhiteBalance = { pickingWhiteBalance = !pickingWhiteBalance },
            )
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    topBar()
                    photo(Modifier.weight(1f).fillMaxWidth())
                    ActivityLine(state.activity, actions::onCancelExport)
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.width(SIDE_PANEL_WIDTH).fillMaxHeight()) {
                    panel(Modifier.weight(1f).fillMaxWidth())
                    ToolStrip(tool, state) { tool = it }
                }
            }
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomStart).padding(16.dp))
        } else {
            Column(Modifier.fillMaxSize()) {
                topBar()
                photo(Modifier.weight(1f).fillMaxWidth())
                ActivityLine(state.activity, actions::onCancelExport)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                panel(Modifier.fillMaxWidth().height(PANEL_HEIGHT))
                ToolStrip(tool, state) { tool = it }
            }
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(bottom = PANEL_HEIGHT, start = 16.dp, end = 16.dp))
        }
    }
}

@Composable
private fun PhotoArea(
    state: EditorUiState.Success,
    tool: EditorTool,
    compare: Boolean,
    viewResetKey: Int,
    brush: BrushSettings,
    actions: EditorActions,
    modifier: Modifier,
) {
    when {
        tool == EditorTool.CROP && state.cropMode -> CropEditor(
            image = state.enhanced,
            crop = state.edit.geometry.crop,
            pixelRatio = actions.aspectRatioFor(state.cropAspect),
            onCropChanged = actions::onCropChanged,
            onCropFinished = actions::onEditFinished,
            modifier = modifier,
        )
        tool == EditorTool.MASKING -> MaskCanvas(
            image = state.enhanced,
            overlay = state.maskOverlay,
            selected = state.edit.localAdjustments.byId(state.selectedMaskId ?: -1),
            brush = brush,
            actions = actions,
            modifier = modifier,
        )
        tool == EditorTool.HEALING -> HealCanvas(state.enhanced, state.edit.retouch, state.selectedSpotId, actions, modifier)
        else -> Box(modifier) {
            CompareView(
                original = state.original,
                enhanced = state.enhanced,
                mode = if (compare) CompareMode.COMPARE else CompareMode.ENHANCED,
                split = SplitOrientation.HORIZONTAL,
                beforeLabel = stringResource(R.string.editor_before),
                afterLabel = stringResource(R.string.editor_after),
                resetKey = viewResetKey,
                description = stringResource(if (compare) R.string.editor_photo_compare_description else R.string.editor_photo_description),
                dividerDescription = stringResource(R.string.compare_divider),
                modifier = Modifier.fillMaxSize(),
            )
            if (tool == EditorTool.GEOMETRY || tool == EditorTool.OPTICS) GridOverlay(Modifier.fillMaxSize())
        }
    }
}

/** The edited photo, fitted; one tap reports the normalised position on the photo. */
@Composable
private fun TapToPick(image: ImageBitmap, hint: String, onPick: (Float, Float) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.background(PhotoCanvas)) {
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        val scale = min(boxWidth / image.width, boxHeight / image.height)
        val left = (boxWidth - image.width * scale) / 2f
        val top = (boxHeight - image.height * scale) / 2f
        Image(
            image,
            contentDescription = hint,
            contentScale = ContentScale.Fit,
            // Keyed on the fitted frame too, so a resized window never maps taps with stale numbers.
            modifier = Modifier.fillMaxSize().pointerInput(image, scale, left, top) {
                detectTapGestures { position ->
                    val x = (position.x - left) / (image.width * scale)
                    val y = (position.y - top) / (image.height * scale)
                    if (x in 0f..1f && y in 0f..1f) onPick(x, y)
                }
            },
        )
        Text(
            hint,
            style = MaterialTheme.typography.labelLarge,
            color = OnPhotoCanvas,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                .background(PhotoLabelBacking, MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Thirds plus a fine grid, to judge straight lines while correcting perspective and lens distortion. */
@Composable
private fun GridOverlay(modifier: Modifier) {
    Box(
        modifier.drawWithContent {
            drawContent()
            val fine = Color.White.copy(alpha = 0.16f)
            val coarse = Color.White.copy(alpha = 0.35f)
            val steps = 12
            for (i in 1 until steps) {
                val x = size.width * i / steps
                val y = size.height * i / steps
                val color = if (i % 4 == 0) coarse else fine
                drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
        },
    )
}

@Suppress("LongParameterList")
@Composable
private fun ToolPanel(
    state: EditorUiState.Success,
    tool: EditorTool,
    brush: BrushSettings,
    onBrushChanged: (BrushSettings) -> Unit,
    actions: EditorActions,
    onSavePreset: () -> Unit,
    onSaveVersion: () -> Unit,
    pickingWhiteBalance: Boolean,
    onPickWhiteBalance: () -> Unit,
) {
    val edit = state.edit
    when (tool) {
        EditorTool.PRESETS -> PresetsPanel(state.userPresets, state.appliedPreset, actions, onSavePreset)
        EditorTool.AUTO -> AutoPanel(edit, state.detectedScene, actions)
        EditorTool.CROP -> CropPanel(edit, state.cropAspect, actions)
        EditorTool.LIGHT -> LightPanel(edit, state.histogram, state.showClipping, actions)
        EditorTool.COLOR -> ColorPanel(edit, pickingWhiteBalance, onPickWhiteBalance, actions)
        EditorTool.EFFECTS -> EffectsPanel(edit, actions)
        EditorTool.DETAIL -> DetailPanel(edit, actions)
        EditorTool.OPTICS -> OpticsPanel(edit, actions)
        EditorTool.GEOMETRY -> GeometryPanel(edit.geometry, actions)
        EditorTool.MASKING -> MaskingPanel(edit.localAdjustments, state.selectedMaskId, state.showMaskOverlay, brush, onBrushChanged, actions)
        EditorTool.HEALING -> HealingPanel(edit.retouch, state.selectedSpotId, state.healSettings, actions)
        EditorTool.HISTORY -> HistoryPanel(state.historyLabels, state.historyPosition, state.versions, actions, onSaveVersion)
    }
}

/** Test tag of a tool strip entry. */
fun toolTag(tool: EditorTool) = "tool:${tool.name}"

/** True when a tool has changes, shown as a dot under its icon. */
private fun EditorUiState.Success.isEdited(tool: EditorTool): Boolean {
    val e = edit
    val g = e.geometry
    return when (tool) {
        EditorTool.PRESETS -> appliedPreset != null
        EditorTool.AUTO -> e.sceneOverride != null
        EditorTool.CROP -> g.quarterTurns != 0 || g.flipHorizontal || g.straightenDegrees != 0f || !g.crop.isFull
        EditorTool.LIGHT -> PanelControls.edited(e, PanelControls.LIGHT) || !e.toneCurves.isIdentity
        EditorTool.COLOR -> PanelControls.edited(e, PanelControls.COLOR) || !e.colorMixer.isNeutral || !e.colorGrading.isNeutral ||
            !e.calibration.isNeutral || !e.autoWhiteBalance
        EditorTool.EFFECTS -> PanelControls.edited(e, PanelControls.EFFECTS)
        EditorTool.DETAIL -> PanelControls.edited(e, PanelControls.DETAIL)
        EditorTool.OPTICS -> !g.lens.isIdentity || PanelControls.edited(e, PanelControls.OPTICS)
        EditorTool.GEOMETRY -> !g.perspective.isIdentity
        EditorTool.MASKING -> e.localAdjustments.items.isNotEmpty()
        EditorTool.HEALING -> !e.retouch.isEmpty
        EditorTool.HISTORY -> versions.isNotEmpty()
    }
}

@Composable
private fun ToolStrip(selected: EditorTool, state: EditorUiState.Success, onSelect: (EditorTool) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        EditorTool.entries.forEach { tool ->
            val isSelected = tool == selected
            val edited = state.isEdited(tool)
            val tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            val label = stringResource(tool.labelRes)
            val spoken = if (edited) stringResource(R.string.a11y_edited_name, label) else label
            Column(
                Modifier
                    // Grows with large text instead of cutting the label; the strip scrolls.
                    .widthIn(min = 66.dp)
                    .testTag(toolTag(tool))
                    .semantics { this.selected = isSelected }
                    .clickable(role = Role.Tab) { onSelect(tool) }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(tool.icon, contentDescription = null, tint = tint)
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    fontWeight = if (isSelected) FontWeight.Bold else null,
                    maxLines = 1,
                    modifier = Modifier.semantics { contentDescription = spoken },
                )
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .size(4.dp)
                        .background(if (edited) tint else Color.Transparent, MaterialTheme.shapes.extraLarge),
                )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun TopBar(
    state: EditorUiState.Success,
    actions: EditorActions,
    compare: Boolean,
    onCompareChange: (Boolean) -> Unit,
    onSavePreset: () -> Unit,
    onPaste: () -> Unit,
    onBatch: () -> Unit,
    onOpenDebug: (() -> Unit)?,
) {
    val busy = state.activity is EditorActivity.Saving
    var menu by remember { mutableStateOf(false) }
    val saveLabel = stringResource(R.string.editor_save)
    val saveStyle = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Close, Undo, More and Save always show. When large text or a narrow screen leaves no room,
        // Share, then Compare, then Redo move into More instead of squeezing Save.
        val saveWidth = with(LocalDensity.current) { measurer.measure(saveLabel, saveStyle).size.width.toDp() }
        val room = maxWidth - BAR_PADDING * 2 - SAVE_END_PADDING - maxOf(SAVE_MIN_WIDTH, saveWidth + SAVE_CONTENT_PADDING) - BAR_ICON * 3
        val optional = (room / BAR_ICON).toInt().coerceIn(0, 3)
        val showRedo = optional >= 1
        val showCompare = optional >= 2
        val showShare = optional >= 3
        Row(Modifier.fillMaxWidth().padding(horizontal = BAR_PADDING, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions::onCloseRequested) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.editor_close)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = actions::onUndo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(R.string.editor_undo)) }
            if (showRedo) {
                IconButton(onClick = actions::onRedo, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = stringResource(R.string.editor_redo)) }
            }
            if (showCompare) StateIconToggle(compare, onCompareChange, Icons.Outlined.Compare, stringResource(R.string.editor_compare))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.editor_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    OverflowActions(
                        showRedo = showRedo,
                        showCompare = showCompare,
                        showShare = showShare,
                        state = state,
                        compare = compare,
                        onClose = { menu = false },
                        onRedo = actions::onRedo,
                        onCompareChange = onCompareChange,
                        onShare = actions::onShare,
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_copy_settings)) }, onClick = { menu = false; actions.onCopySettings() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_paste_settings)) }, enabled = state.canPaste, onClick = { menu = false; onPaste() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_apply_to_others)) }, enabled = state.batch?.running != true, onClick = { menu = false; onBatch() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_save_preset)) }, onClick = { menu = false; onSavePreset() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.editor_reset_all)) }, onClick = { menu = false; actions.onResetAll() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.editor_original_state)) }, onClick = { menu = false; actions.onShowOriginalEdit() })
                    if (onOpenDebug != null) DropdownMenuItem(text = { Text(stringResource(R.string.editor_debug)) }, onClick = { menu = false; onOpenDebug() })
                }
            }
            if (showShare) {
                IconButton(onClick = actions::onShare, enabled = !busy) { Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.editor_share)) }
            }
            Button(shape = MaterialTheme.shapes.small, onClick = actions::onSave, enabled = !busy, modifier = Modifier.padding(end = SAVE_END_PADDING)) {
                Text(saveLabel)
            }
        }
    }
}

/** The top-bar actions that did not fit, at the top of the More menu. */
@Suppress("LongParameterList")
@Composable
private fun OverflowActions(
    showRedo: Boolean,
    showCompare: Boolean,
    showShare: Boolean,
    state: EditorUiState.Success,
    compare: Boolean,
    onClose: () -> Unit,
    onRedo: () -> Unit,
    onCompareChange: (Boolean) -> Unit,
    onShare: () -> Unit,
) {
    if (!showRedo) {
        DropdownMenuItem(text = { Text(stringResource(R.string.editor_redo)) }, enabled = state.canRedo, onClick = { onClose(); onRedo() })
    }
    if (!showCompare) {
        val onOff = stringResource(if (compare) R.string.a11y_on else R.string.a11y_off)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_compare)) },
            trailingIcon = if (compare) ({ Icon(Icons.Outlined.Check, contentDescription = null) }) else null,
            onClick = { onClose(); onCompareChange(!compare) },
            modifier = Modifier.semantics { stateDescription = onOff },
        )
    }
    if (!showShare) {
        DropdownMenuItem(text = { Text(stringResource(R.string.editor_share)) }, enabled = state.activity !is EditorActivity.Saving, onClick = { onClose(); onShare() })
    }
    if (!(showRedo && showCompare && showShare)) HorizontalDivider()
}

private val BAR_ICON = 48.dp
private val BAR_PADDING = 4.dp
private val SAVE_END_PADDING = 4.dp

/** Material's button minimum width and its horizontal content padding (24 dp each side). */
private val SAVE_MIN_WIDTH = 58.dp
private val SAVE_CONTENT_PADDING = 48.dp

@Composable
private fun Dialogs(
    state: EditorUiState.Success,
    actions: EditorActions,
    namePrompt: NamePrompt?,
    onNameDone: () -> Unit,
) {
    state.exportDialog?.let { dialog ->
        ExportDialog(dialog, actions::onExportOptionsChanged, actions::onExportConfirmed, actions::onExportDismissed)
    }
    if (state.confirmLeave) {
        AlertDialog(
            onDismissRequest = actions::onLeaveDismissed,
            title = { Text(stringResource(R.string.leave_title)) },
            text = { Text(stringResource(R.string.leave_message)) },
            confirmButton = { TextButton(onClick = actions::onClose) { Text(stringResource(R.string.leave_confirm)) } },
            dismissButton = { Button(shape = MaterialTheme.shapes.small, onClick = actions::onLeaveDismissed) { Text(stringResource(R.string.leave_stay)) } },
        )
    }
    namePrompt?.let { prompt ->
        var name by remember(prompt) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onNameDone,
            title = { Text(stringResource(if (prompt == NamePrompt.PRESET) R.string.menu_save_preset else R.string.versions_save)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.name_label)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = prompt == NamePrompt.VERSION || name.isNotBlank(),
                    onClick = {
                        if (prompt == NamePrompt.PRESET) actions.onSavePreset(name) else actions.onSaveVersion(name)
                        onNameDone()
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = onNameDone) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Ticks for the settings groups to copy (paste, or apply to other photos). */
@Composable
private fun SettingsGroupsDialog(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: (Set<SettingsGroup>) -> Unit,
    onDismiss: () -> Unit,
) {
    var groups by remember { mutableStateOf(SettingsGroup.ALL) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Scrolls on small screens, so every row keeps its full 48 dp height.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (message != null) Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                SettingsGroup.entries.forEach { group ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(value = group in groups, role = Role.Checkbox) { checked -> groups = if (checked) groups + group else groups - group },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = group in groups, onCheckedChange = null)
                        Text(group.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = groups.isNotEmpty(), onClick = { onConfirm(groups) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Progress of "apply to other photos", then a summary. */
@Composable
private fun BatchDialog(batch: BatchProgress, actions: EditorActions) {
    AlertDialog(
        onDismissRequest = { if (!batch.running) actions.onDismissBatch() },
        title = { Text(stringResource(if (batch.running) R.string.batch_running_title else R.string.batch_done_title)) },
        text = {
            Column {
                if (batch.running) {
                    LinearProgressIndicator(progress = { if (batch.total == 0) 0f else batch.done / batch.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text(pluralStringResource(R.plurals.batch_progress, batch.total, batch.done, batch.total), modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text(stringResource(R.string.batch_summary, batch.saved, batch.failed))
                    if (batch.cancelled) Text(stringResource(R.string.batch_cancelled))
                    if (batch.skipped > 0) Text(pluralStringResource(R.plurals.batch_skipped, batch.skipped, batch.skipped))
                    Text(stringResource(R.string.batch_where), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            if (batch.running) {
                TextButton(onClick = actions::onCancelBatch) { Text(stringResource(R.string.cancel)) }
            } else {
                TextButton(onClick = actions::onDismissBatch) { Text(stringResource(R.string.batch_ok)) }
            }
        },
    )
}

private const val MAX_NAME = 40

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
private fun ActivityLine(activity: EditorActivity, onCancelExport: () -> Unit) {
    when (activity) {
        is EditorActivity.Reprocessing -> LinearProgressIndicator(progress = { activity.progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
        is EditorActivity.Saving -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(progress = { activity.progress }, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.editor_saving), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
            TextButton(onClick = onCancelExport) { Text(stringResource(R.string.editor_cancel_export)) }
        }
        else -> Spacer(Modifier.height(2.dp))
    }
}
