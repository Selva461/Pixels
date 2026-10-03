package com.pixels.enhancer.ui.export

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.export.ExportFormat
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.export.ExportSize
import com.pixels.enhancer.domain.export.MetadataPolicy
import com.pixels.enhancer.ui.editor.ExportDialogState
import kotlin.math.roundToInt

@Composable
fun ExportDialog(
    state: ExportDialogState,
    onOptionsChanged: (ExportOptions) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val options = state.options
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Section(stringResource(R.string.export_format)) {
                    ExportFormat.entries.forEach { format ->
                        FilterChip(options.format == format, { onOptionsChanged(options.copy(format = format)) }, { Text(format.name) })
                    }
                }
                if (options.format == ExportFormat.JPEG) {
                    Text(stringResource(R.string.export_quality, options.quality), style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = options.quality.toFloat(),
                        onValueChange = { onOptionsChanged(options.copy(quality = it.roundToInt())) },
                        valueRange = ExportOptions.MIN_QUALITY.toFloat()..ExportOptions.MAX_QUALITY.toFloat(),
                    )
                }
                Section(stringResource(R.string.export_size)) {
                    ExportSize.entries.forEach { size ->
                        FilterChip(options.size == size, { onOptionsChanged(options.copy(size = size)) }, { Text(size.label) })
                    }
                }
                Text(stringResource(R.string.export_dimensions, state.width, state.height), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Section(stringResource(R.string.export_metadata)) {
                    MetadataPolicy.entries.forEach { policy ->
                        FilterChip(options.metadata == policy, { onOptionsChanged(options.copy(metadata = policy)) }, { Text(policy.label) })
                    }
                }
                Text(stringResource(R.string.export_original_kept), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(shape = MaterialTheme.shapes.small, onClick = onConfirm) { Text(stringResource(R.string.export_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.export_cancel)) } },
    )
}

@Composable
private fun Section(title: String, chips: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    Spacer(Modifier.height(8.dp))
}
