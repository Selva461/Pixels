package com.pixels.enhancer.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.ui.ErrorMessages
import com.pixels.enhancer.ui.compare.CompareMode
import com.pixels.enhancer.ui.compare.CompareView
import com.pixels.enhancer.ui.compare.SplitOrientation
import kotlin.math.roundToInt

private const val PERCENT = 100

@Composable
fun EditorScreen(
    state: EditorUiState.Success,
    onClose: () -> Unit,
    onStrengthChanged: (Float) -> Unit,
    onStrengthChangeFinished: () -> Unit,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onOpenDebug: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var mode by rememberSaveable { mutableStateOf(CompareMode.COMPARE) }
    var split by rememberSaveable { mutableStateOf(SplitOrientation.HORIZONTAL) }
    var viewResetKey by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(onBack = onClose)

    Column(modifier.fillMaxSize()) {
        TopBar(onClose, onOpenDebug)
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
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ModeSelector(mode, split, onModeChange = { mode = it }, onSplitChange = { split = it })
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.editor_strength, (state.strength * PERCENT).roundToInt()), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = state.strength,
                onValueChange = onStrengthChanged,
                onValueChangeFinished = onStrengthChangeFinished,
                enabled = state.activity !is EditorActivity.Saving,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUndo, enabled = state.canUndo) { Text(stringResource(R.string.editor_undo)) }
                OutlinedButton(onClick = {
                    viewResetKey++
                    onReset()
                }) { Text(stringResource(R.string.editor_reset)) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onShare) { Text(stringResource(R.string.editor_share)) }
                Button(onClick = onSave, enabled = state.activity !is EditorActivity.Saving) { Text(stringResource(R.string.editor_save)) }
            }
        }
    }
}

@Composable
private fun TopBar(onClose: () -> Unit, onOpenDebug: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClose) { Text(stringResource(R.string.editor_close)) }
        Spacer(Modifier.weight(1f))
        if (onOpenDebug != null) TextButton(onClick = onOpenDebug) { Text(stringResource(R.string.editor_debug)) }
    }
}

@Composable
private fun ModeSelector(
    mode: CompareMode,
    split: SplitOrientation,
    onModeChange: (CompareMode) -> Unit,
    onSplitChange: (SplitOrientation) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(mode == CompareMode.ORIGINAL, { onModeChange(CompareMode.ORIGINAL) }, { Text(stringResource(R.string.editor_mode_original)) })
        FilterChip(mode == CompareMode.ENHANCED, { onModeChange(CompareMode.ENHANCED) }, { Text(stringResource(R.string.editor_mode_enhanced)) })
        FilterChip(mode == CompareMode.COMPARE, { onModeChange(CompareMode.COMPARE) }, { Text(stringResource(R.string.editor_mode_compare)) })
    }
    if (mode == CompareMode.COMPARE) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        EditorActivity.None -> Spacer(Modifier.height(4.dp))
        is EditorActivity.Reprocessing -> Column(modifier) {
            LinearProgressIndicator(progress = { activity.progress }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.editor_updating, activity.stageName), style = MaterialTheme.typography.bodySmall)
        }
        EditorActivity.Saving -> Column(modifier) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.editor_saving), style = MaterialTheme.typography.bodySmall)
        }
        is EditorActivity.Saved -> Text(
            stringResource(R.string.editor_saved, activity.displayName),
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
        is EditorActivity.Failed -> Text(
            stringResource(ErrorMessages.forCode(activity.code)),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
    }
}
