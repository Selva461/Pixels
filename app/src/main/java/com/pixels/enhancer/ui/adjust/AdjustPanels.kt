package com.pixels.enhancer.ui.adjust

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.analysis.Histogram
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.planning.HslShift
import com.pixels.enhancer.domain.planning.HueBand
import com.pixels.enhancer.ui.components.ProSlider
import com.pixels.enhancer.ui.components.Tracks
import com.pixels.enhancer.ui.components.percentText

/** Swatch colour for each mixer band. */
fun HueBand.swatch(): Color = Color.hsv(centerDegrees % 360f, 0.75f, 0.9f)

/** HSL colour mixer: pick a band (colour dots), then shift its hue, saturation and luminance. */
@Composable
fun ColorMixerPanel(
    edit: EditState,
    onShiftChanged: (HueBand, HslShift) -> Unit,
    onEditFinished: () -> Unit,
    onResetAll: () -> Unit,
) {
    var band by rememberSaveable { mutableStateOf(HueBand.RED) }
    val shift = edit.colorMixer[band]
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            HueBand.entries.forEach { option ->
                val selected = option == band
                val edited = !edit.colorMixer[option].isNeutral
                Box(
                    Modifier
                        .size(34.dp)
                        .semantics {
                            contentDescription = option.label
                            this.selected = selected
                        }
                        .border(2.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                        .padding(5.dp)
                        .background(option.swatch(), CircleShape)
                        .clickable { band = option },
                    contentAlignment = Alignment.Center,
                ) {
                    if (edited) Box(Modifier.size(6.dp).background(Color.White, CircleShape))
                }
            }
        }
        ProSlider(stringResource(R.string.mixer_hue), shift.hue, percentText(shift.hue), { onShiftChanged(band, shift.copy(hue = it)) }, onEditFinished, track = Tracks.hue)
        ProSlider(stringResource(R.string.mixer_saturation), shift.saturation, percentText(shift.saturation), { onShiftChanged(band, shift.copy(saturation = it)) }, onEditFinished)
        ProSlider(stringResource(R.string.mixer_luminance), shift.luminance, percentText(shift.luminance), { onShiftChanged(band, shift.copy(luminance = it)) }, onEditFinished, track = Tracks.lightness)
        TextButton(onClick = onResetAll, enabled = !edit.colorMixer.isNeutral, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.mixer_reset))
        }
    }
}

/** Brightness histogram of the edited preview (not the source). One neutral tone keeps it readable. */
@Composable
fun HistogramView(histogram: Histogram, modifier: Modifier = Modifier) {
    val lumaColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Canvas(modifier) {
        val peak = histogram.luma.max().coerceAtLeast(1).toFloat()
        val barWidth = size.width / histogram.bins
        histogram.luma.forEachIndexed { index, count ->
            val barHeight = size.height * count / peak
            drawRect(lumaColor, Offset(index * barWidth, size.height - barHeight), Size(barWidth, barHeight))
        }
    }
}
