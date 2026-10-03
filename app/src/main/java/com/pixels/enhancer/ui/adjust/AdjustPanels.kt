package com.pixels.enhancer.ui.adjust

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.analysis.Histogram
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.planning.ControlGroup
import com.pixels.enhancer.domain.planning.HslShift
import com.pixels.enhancer.domain.planning.HueBand
import com.pixels.enhancer.domain.planning.ManualControl
import kotlin.math.roundToInt

private const val PERCENT = 100

/** Grouped manual sliders with a short description each; tap the value to reset one slider. */
@Composable
fun AdjustPanel(
    edit: EditState,
    histogram: Histogram?,
    onControlChanged: (ManualControl, Float) -> Unit,
    onEditFinished: () -> Unit,
    onResetControl: (ManualControl) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        histogram?.let { HistogramView(it, Modifier.fillMaxWidth().height(56.dp)) }
        Text(stringResource(R.string.editor_reset_control_hint), style = MaterialTheme.typography.bodySmall)
        ControlGroup.entries.forEach { group ->
            Text(group.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            ManualControl.entries.filter { it.group == group }.forEach { control ->
                LabelledSlider(
                    label = control.label,
                    description = control.description,
                    value = edit.manual[control],
                    range = control.min..control.max,
                    onChange = { onControlChanged(control, it) },
                    onFinished = onEditFinished,
                    onReset = { onResetControl(control) },
                )
            }
        }
    }
}

/** HSL colour mixer: pick a band, then shift its hue, saturation and luminance. */
@Composable
fun ColorMixerPanel(
    edit: EditState,
    onShiftChanged: (HueBand, HslShift) -> Unit,
    onEditFinished: () -> Unit,
    onResetAll: () -> Unit,
) {
    var band by rememberSaveable { mutableStateOf(HueBand.RED) }
    val shift = edit.colorMixer[band]
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HueBand.entries.forEach { option ->
                val edited = !edit.colorMixer[option].isNeutral
                FilterChip(band == option, { band = option }, { Text(if (edited) "${option.label} •" else option.label) })
            }
        }
        LabelledSlider(stringResource(R.string.mixer_hue), null, shift.hue, -1f..1f, { onShiftChanged(band, shift.copy(hue = it)) }, onEditFinished) {
            onShiftChanged(band, shift.copy(hue = 0f))
            onEditFinished()
        }
        LabelledSlider(stringResource(R.string.mixer_saturation), null, shift.saturation, -1f..1f, { onShiftChanged(band, shift.copy(saturation = it)) }, onEditFinished) {
            onShiftChanged(band, shift.copy(saturation = 0f))
            onEditFinished()
        }
        LabelledSlider(stringResource(R.string.mixer_luminance), null, shift.luminance, -1f..1f, { onShiftChanged(band, shift.copy(luminance = it)) }, onEditFinished) {
            onShiftChanged(band, shift.copy(luminance = 0f))
            onEditFinished()
        }
        TextButton(onClick = onResetAll, enabled = !edit.colorMixer.isNeutral) { Text(stringResource(R.string.mixer_reset)) }
    }
}

@Suppress("LongParameterList")
@Composable
private fun LabelledSlider(
    label: String,
    description: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    onReset: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onReset) { Text(formatValue(value)) }
    }
    Slider(value = value, onValueChange = onChange, onValueChangeFinished = onFinished, valueRange = range)
}

/** Luma histogram with faint RGB channels behind it; shows the edited preview, not the source. */
@Composable
fun HistogramView(histogram: Histogram, modifier: Modifier = Modifier) {
    val lumaColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Canvas(modifier) {
        val peak = histogram.peak.coerceAtLeast(1).toFloat()
        val barWidth = size.width / histogram.bins
        fun drawChannel(values: IntArray, color: Color) {
            values.forEachIndexed { index, count ->
                val barHeight = size.height * count / peak
                drawRect(color, Offset(index * barWidth, size.height - barHeight), Size(barWidth, barHeight))
            }
        }
        drawChannel(histogram.red, Color.Red.copy(alpha = 0.25f))
        drawChannel(histogram.green, Color.Green.copy(alpha = 0.25f))
        drawChannel(histogram.blue, Color.Blue.copy(alpha = 0.25f))
        drawChannel(histogram.luma, lumaColor)
    }
}

private fun formatValue(value: Float): String {
    val percent = (value * PERCENT).roundToInt()
    return if (percent > 0) "+$percent" else percent.toString()
}
