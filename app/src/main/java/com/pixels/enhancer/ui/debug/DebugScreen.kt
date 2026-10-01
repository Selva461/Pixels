package com.pixels.enhancer.ui.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.debug.DebugSection
import com.pixels.enhancer.ui.editor.DebugInfo

/**
 * Developer-build screen: analysis, plan with reasons, pipeline, stage timings and validation,
 * plus per-stage toggles and "stop after stage" to isolate which stage causes a problem.
 */
@Composable
fun DebugScreen(
    debug: DebugInfo,
    onBack: () -> Unit,
    onStageToggled: (String, Boolean) -> Unit,
    onRunUntilSelected: (String?) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.debug_back)) }
            Text(stringResource(R.string.debug_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onExport) { Text(stringResource(R.string.debug_export)) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            StageControls(debug, onStageToggled, onRunUntilSelected)
            debug.sections.forEach { ReportSection(it) }
        }
    }
}

@Composable
private fun StageControls(debug: DebugInfo, onStageToggled: (String, Boolean) -> Unit, onRunUntilSelected: (String?) -> Unit) {
    Text(stringResource(R.string.debug_stages), style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = debug.runUntilStageId == null, onClick = { onRunUntilSelected(null) })
        Text(stringResource(R.string.debug_run_all))
    }
    debug.stageIds.forEach { stageId ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = stageId !in debug.disabledStages, onCheckedChange = { onStageToggled(stageId, it) })
            Text(stageId, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.debug_run_until), style = MaterialTheme.typography.bodySmall)
            RadioButton(selected = debug.runUntilStageId == stageId, onClick = { onRunUntilSelected(stageId) })
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
}

@Composable
private fun ReportSection(section: DebugSection) {
    Text("[${section.title}]", style = MaterialTheme.typography.titleSmall)
    Text(
        section.lines.joinToString("\n"),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
    )
    Spacer(Modifier.height(12.dp))
}
