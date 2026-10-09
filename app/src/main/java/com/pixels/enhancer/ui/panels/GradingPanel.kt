package com.pixels.enhancer.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.planning.ColorGrading
import com.pixels.enhancer.domain.planning.GradeRange
import com.pixels.enhancer.domain.planning.GradeWheel
import com.pixels.enhancer.ui.components.ProSlider
import com.pixels.enhancer.ui.components.Tracks
import com.pixels.enhancer.ui.components.percentText
import com.pixels.enhancer.ui.editor.EditorActions
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Colour grading: one wheel at a time (shadows, midtones, highlights, global) plus blending and balance. */
@Composable
fun GradingPanel(grading: ColorGrading, actions: EditorActions) {
    var range by rememberSaveable { mutableStateOf(GradeRange.MIDTONES) }
    val wheel = grading[range]
    PanelColumn {
        SegmentRow(GradeRange.entries, range, { it.label }) { range = it }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ColorWheel(
                wheel = wheel,
                onChange = { actions.onGradingChanged(grading.with(range, it)) },
                onFinished = actions::onEditFinished,
                modifier = Modifier.size(150.dp),
            )
            Column(Modifier.weight(1f)) {
                Text("${wheel.hue.roundToInt()}°", style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.grading_saturation, (wheel.saturation * 100).roundToInt()), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.grading_wheel_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ProSlider(
            stringResource(R.string.grading_luminance), wheel.luminance, percentText(wheel.luminance),
            { actions.onGradingChanged(grading.with(range, wheel.copy(luminance = it))) }, actions::onEditFinished, track = Tracks.lightness,
        )
        ProSlider(
            stringResource(R.string.grading_blending), grading.blending, percentText(grading.blending),
            { actions.onGradingChanged(grading.copy(blending = it)) }, actions::onEditFinished, range = 0f..1f, resetValue = ColorGrading.DEFAULT_BLENDING,
        )
        ProSlider(
            stringResource(R.string.grading_balance), grading.balance, percentText(grading.balance),
            { actions.onGradingChanged(grading.copy(balance = it)) }, actions::onEditFinished,
        )
        TextButton(onClick = actions::onResetGrading, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.reset)) }
    }
}

/**
 * Hue/saturation wheel: angle is hue (0° red at three o'clock, clockwise), distance from the
 * centre is saturation. Drag the dot; double-tap to clear.
 */
@Composable
fun ColorWheel(wheel: GradeWheel, onChange: (GradeWheel) -> Unit, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(wheel)
    val change by rememberUpdatedState(onChange)
    val ring = MaterialTheme.colorScheme.outlineVariant
    val name = stringResource(R.string.grading_wheel)
    val state = stringResource(R.string.grading_wheel_state, wheel.hue.roundToInt(), (wheel.saturation * 100).roundToInt())
    Canvas(
        modifier
            .semantics {
                contentDescription = name
                stateDescription = state
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    change(current.copy(hue = 0f, saturation = 0f))
                    onFinished()
                })
            }
            .pointerInput(Unit) {
                fun update(position: Offset) {
                    val radius = min(size.width, size.height) / 2f
                    val dx = position.x - size.width / 2f
                    val dy = position.y - size.height / 2f
                    val hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360.0) % 360.0).toFloat()
                    val saturation = (sqrt(dx * dx + dy * dy) / radius).coerceIn(0f, 1f)
                    change(current.copy(hue = hue, saturation = saturation))
                }
                detectDragGestures(onDragStart = ::update, onDragEnd = onFinished, onDragCancel = onFinished) { pointer, _ ->
                    pointer.consume()
                    update(pointer.position)
                }
            },
    ) {
        val radius = min(size.width, size.height) / 2f
        val hues = listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it % 360f, 0.7f, 0.85f) }
        drawCircle(Brush.sweepGradient(hues, center), radius)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF8A8A8A), Color(0x008A8A8A)), center, radius), radius)
        drawCircle(ring, radius, style = Stroke(1.dp.toPx()))
        val angle = Math.toRadians(wheel.hue.toDouble())
        val dot = center + Offset((cos(angle) * radius * wheel.saturation).toFloat(), (sin(angle) * radius * wheel.saturation).toFloat())
        drawCircle(Color.Black.copy(alpha = 0.4f), 9.dp.toPx(), dot)
        drawCircle(Color.White, 7.dp.toPx(), dot, style = Stroke(2.5.dp.toPx()))
    }
}
