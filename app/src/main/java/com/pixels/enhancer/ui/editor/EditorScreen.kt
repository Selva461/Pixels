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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.pixels.enhancer.domain.planning.Look
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.ui.ErrorMessages
import com.pixels.enhancer.ui.compare.CompareMode
import com.pixels.enhancer.ui.compare.CompareView
import com.pixels.enhancer.ui.compare.SplitOrientation
import kotlin.math.roundToInt

private const val PERCENT = 100

/** Fixed panel height keeps the photo the same size whichever tab is open. */
private val PANEL_HEIGHT = 230.dp

private enum class EditorTab(val titleRes: Int) {
    AUTO(R.string.editor_tab_auto),
    LOOKS(R.string.editor_tab_looks),
    ADJUST(R.string.editor_tab_adjust),
}

@Composable
fun EditorScreen(
    state: EditorUiState.Success,
    onClose: () -> Unit,
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
    onOpenDebug: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var mode by rememberSaveable { mutableStateOf(CompareMode.COMPARE) }
    var split by rememberSaveable { mutableStateOf(SplitOrientation.HORIZONTAL) }
    var tab by rememberSaveable { mutableStateOf(EditorTab.AUTO) }
    var viewResetKey by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    BackHandler(onBack = onClose)
    ResultSnackbar(state.activity, snackbarHostState, onViewSaved)

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state, onClose, onUndo, onShare, onSave, onOpenDebug)
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
            ActivityLine(state.activity)
            ModeSelector(mode, split, onModeChange = { mode = it }, onSplitChange = { split = it })
            TabRow(selectedTabIndex = tab.ordinal) {
                EditorTab.entries.forEach { entry ->
                    Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(stringResource(entry.titleRes)) })
                }
            }
            Box(Modifier.fillMaxWidth().height(PANEL_HEIGHT)) {
                when (tab) {
                    EditorTab.AUTO -> AutoPanel(state.edit.strength, onStrengthChanged, onEditFinished, onResetAll = {
                        viewResetKey++
                        onResetAll()
                    })
                    EditorTab.LOOKS -> LooksPanel(state.edit.lookId, onLookSelected)
                    EditorTab.ADJUST -> AdjustPanel(state.edit, onControlChanged, onEditFinished, onResetControl)
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
        TextButton(onClick = onShare, enabled = !busy) { Text(stringResource(R.string.editor_share)) }
        Button(onClick = onSave, enabled = !busy) { Text(stringResource(R.string.editor_save)) }
    }
}

@Composable
private fun ResultSnackbar(activity: EditorActivity, hostState: SnackbarHostState, onViewSaved: (Uri) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(activity) {
        when (activity) {
            is EditorActivity.Saved -> {
                val result = hostState.showSnackbar(
                    message = context.getString(R.string.editor_saved, activity.displayName),
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
private fun AutoPanel(strength: Float, onStrengthChanged: (Float) -> Unit, onEditFinished: () -> Unit, onResetAll: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.editor_strength, (strength * PERCENT).roundToInt()), style = MaterialTheme.typography.titleSmall)
        Slider(value = strength, onValueChange = onStrengthChanged, onValueChangeFinished = onEditFinished)
        Text(stringResource(R.string.editor_auto_hint), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onResetAll) { Text(stringResource(R.string.editor_reset_all)) }
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
private fun AdjustPanel(
    edit: EditState,
    onControlChanged: (ManualControl, Float) -> Unit,
    onEditFinished: () -> Unit,
    onResetControl: (ManualControl) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.editor_reset_control_hint), style = MaterialTheme.typography.bodySmall)
        ManualControl.entries.forEach { control ->
            val value = edit.manual[control]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(control.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onResetControl(control) }) { Text(formatValue(value)) }
            }
            Slider(
                value = value,
                onValueChange = { onControlChanged(control, it) },
                onValueChangeFinished = onEditFinished,
                valueRange = control.min..control.max,
            )
        }
    }
}

private fun formatValue(value: Float): String {
    val percent = (value * PERCENT).roundToInt()
    return if (percent > 0) "+$percent" else percent.toString()
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
private fun ActivityLine(activity: EditorActivity) {
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    when (activity) {
        is EditorActivity.Reprocessing -> Column(modifier) {
            LinearProgressIndicator(progress = { activity.progress }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.editor_updating), style = MaterialTheme.typography.bodySmall)
        }
        is EditorActivity.Saving -> Column(modifier) {
            LinearProgressIndicator(progress = { activity.progress }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.editor_saving), style = MaterialTheme.typography.bodySmall)
        }
        else -> Spacer(Modifier.height(4.dp))
    }
}
