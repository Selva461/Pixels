package com.pixels.enhancer.ui.export

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.export.Border
import com.pixels.enhancer.domain.export.ExportFormat
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.export.ExportSize
import com.pixels.enhancer.domain.export.MetadataPolicy
import com.pixels.enhancer.domain.export.Watermark
import com.pixels.enhancer.domain.export.WatermarkPosition
import com.pixels.enhancer.ui.components.ChoiceChip
import com.pixels.enhancer.ui.editor.ExportDialogState
import kotlin.math.roundToInt

private enum class BorderWidth(val labelRes: Int, val fraction: Float) {
    NONE(R.string.export_border_none, 0f),
    THIN(R.string.export_border_thin, Border.THIN),
    MEDIUM(R.string.export_border_medium, Border.MEDIUM),
    THICK(R.string.export_border_thick, Border.THICK),
}

private fun WatermarkPosition.labelRes() = when (this) {
    WatermarkPosition.TOP_LEFT -> R.string.export_watermark_top_left
    WatermarkPosition.TOP_RIGHT -> R.string.export_watermark_top_right
    WatermarkPosition.BOTTOM_LEFT -> R.string.export_watermark_bottom_left
    WatermarkPosition.BOTTOM_RIGHT -> R.string.export_watermark_bottom_right
    WatermarkPosition.CENTER -> R.string.export_watermark_center
}

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
                ExportSettingsSections(options, onOptionsChanged)
                Text(stringResource(R.string.export_dimensions, state.width, state.height), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.export_original_kept), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(shape = MaterialTheme.shapes.small, onClick = onConfirm) { Text(stringResource(R.string.export_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.export_cancel)) } },
    )
}

/** Every export choice; shared by the export dialog and the Settings screen's export defaults. */
@Composable
fun ExportSettingsSections(options: ExportOptions, onOptionsChanged: (ExportOptions) -> Unit) {
    Section(stringResource(R.string.export_format)) {
        ExportFormat.entries.forEach { format -> ChoiceChip(options.format == format, { onOptionsChanged(options.copy(format = format)) }, format.label) }
    }
    if (options.format.lossy) {
        Text(stringResource(R.string.export_quality, options.quality), style = MaterialTheme.typography.labelLarge)
        val qualityName = stringResource(R.string.export_quality_name)
        Slider(
            value = options.quality.toFloat(),
            onValueChange = { onOptionsChanged(options.copy(quality = it.roundToInt())) },
            valueRange = ExportOptions.MIN_QUALITY.toFloat()..ExportOptions.MAX_QUALITY.toFloat(),
            modifier = Modifier.semantics { contentDescription = qualityName },
        )
    }
    Section(stringResource(R.string.export_size)) {
        ExportSize.entries.forEach { size -> ChoiceChip(options.size == size, { onOptionsChanged(options.copy(size = size)) }, size.label) }
    }
    Section(stringResource(R.string.export_metadata)) {
        MetadataPolicy.entries.forEach { policy -> ChoiceChip(options.metadata == policy, { onOptionsChanged(options.copy(metadata = policy)) }, policy.label) }
    }
    Section(stringResource(R.string.export_border)) {
        BorderWidth.entries.forEach { width ->
            ChoiceChip(
                selected = options.border.widthFraction == width.fraction,
                onClick = { onOptionsChanged(options.copy(border = options.border.copy(widthFraction = width.fraction))) },
                label = stringResource(width.labelRes),
            )
        }
    }
    if (!options.border.isNone) {
        Section(stringResource(R.string.export_border_colour)) {
            ChoiceChip(options.border.color == Border.WHITE, { onOptionsChanged(options.copy(border = options.border.copy(color = Border.WHITE))) }, stringResource(R.string.export_border_white))
            ChoiceChip(options.border.color == Border.BLACK, { onOptionsChanged(options.copy(border = options.border.copy(color = Border.BLACK))) }, stringResource(R.string.export_border_black))
        }
    }
    // A real label (not just a placeholder), so the field keeps its name once text is typed.
    OutlinedTextField(
        value = options.watermark.text,
        onValueChange = { onOptionsChanged(options.copy(watermark = options.watermark.copy(text = it.take(Watermark.MAX_LENGTH)))) },
        singleLine = true,
        label = { Text(stringResource(R.string.export_watermark)) },
        placeholder = { Text(stringResource(R.string.export_watermark_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
    )
    if (!options.watermark.isNone) {
        Spacer(Modifier.height(4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WatermarkPosition.entries.forEach { position ->
                ChoiceChip(
                    selected = options.watermark.position == position,
                    onClick = { onOptionsChanged(options.copy(watermark = options.watermark.copy(position = position))) },
                    label = stringResource(position.labelRes()),
                )
            }
        }
        Text(stringResource(R.string.export_watermark_size, (options.watermark.size * 1000).roundToInt() / 10f), style = MaterialTheme.typography.bodySmall)
        val sizeName = stringResource(R.string.export_watermark_size_name)
        Slider(
            value = options.watermark.size,
            onValueChange = { onOptionsChanged(options.copy(watermark = options.watermark.copy(size = it))) },
            valueRange = Watermark.MIN_SIZE..Watermark.MAX_SIZE,
            modifier = Modifier.semantics { contentDescription = sizeName },
        )
        Text(stringResource(R.string.export_watermark_opacity, (options.watermark.opacity * 100).roundToInt()), style = MaterialTheme.typography.bodySmall)
        val opacityName = stringResource(R.string.export_watermark_opacity_name)
        Slider(
            value = options.watermark.opacity,
            onValueChange = { onOptionsChanged(options.copy(watermark = options.watermark.copy(opacity = it))) },
            valueRange = Watermark.MIN_OPACITY..1f,
            modifier = Modifier.semantics { contentDescription = opacityName },
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Section(title: String, chips: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    Spacer(Modifier.height(8.dp))
}
