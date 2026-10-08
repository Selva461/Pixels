package com.pixels.enhancer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Editing slider in the style of desktop raw editors: label on the left, value on the right, a
 * thin track underneath. Dragging anywhere on the row moves the value relative to the finger (so
 * small changes are easy), double-tapping resets to zero, and the fill grows from zero — the centre
 * for two-sided controls. [track] draws a colour gradient instead (temperature, tint, hue).
 */
@Suppress("LongParameterList")
@Composable
fun ProSlider(
    label: String,
    value: Float,
    valueText: String,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = -1f..1f,
    resetValue: Float = 0f,
    track: Brush? = null,
    enabled: Boolean = true,
) {
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinished)
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled by rememberUpdatedState(LocalHapticsEnabled.current)
    fun tick() {
        if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val span = range.endInclusive - range.start
    val accent = MaterialTheme.colorScheme.primary
    val rail = MaterialTheme.colorScheme.outlineVariant
    val thumb = MaterialTheme.colorScheme.onSurface
    val changed = abs(value - resetValue) > 1e-4f
    Column(
        modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(value, range)
                if (enabled) {
                    setProgress { target ->
                        change(target.coerceIn(range))
                        finish()
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled, range) {
                if (!enabled) return@pointerInput
                detectTapGestures(onDoubleTap = {
                    change(resetValue)
                    tick()
                    finish()
                })
            }
            .pointerInput(enabled, range) {
                if (!enabled) return@pointerInput
                var dragged = currentValue
                detectHorizontalDragGestures(
                    onDragStart = { dragged = currentValue },
                    onDragEnd = { finish() },
                    onDragCancel = { finish() },
                ) { pointer, amount ->
                    pointer.consume()
                    // Full width = full range; relative so the value never jumps to the finger.
                    dragged = (dragged + amount / size.width * span).coerceIn(range)
                    val snapped = snap(dragged, resetValue, span)
                    // A light tick when the value lands on its reset point, like a detent.
                    if (snapped == resetValue && currentValue != resetValue) tick()
                    change(snapped)
                }
            }
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = if (changed) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val y = size.height / 2
            val railHeight = 3.dp.toPx()
            val position = ((value - range.start) / span).coerceIn(0f, 1f) * size.width
            val zero = ((resetValue - range.start) / span).coerceIn(0f, 1f) * size.width
            if (track != null) {
                drawRoundRect(track, Offset(0f, y - railHeight / 2), Size(size.width, railHeight), CornerRadius(railHeight))
            } else {
                drawRoundRect(rail, Offset(0f, y - railHeight / 2), Size(size.width, railHeight), CornerRadius(railHeight))
                val start = minOf(zero, position)
                drawRoundRect(accent, Offset(start, y - railHeight / 2), Size(abs(position - zero), railHeight), CornerRadius(railHeight))
            }
            if (zero > 0f && zero < size.width) drawCircle(rail, 2.dp.toPx(), Offset(zero, y))
            drawCircle(Color.Black.copy(alpha = 0.35f), 9.dp.toPx(), Offset(position, y + 1.dp.toPx()))
            drawCircle(thumb, 8.dp.toPx(), Offset(position, y))
        }
    }
}

/** Whether sliders vibrate lightly at their reset point (Settings > Haptic feedback). */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/** Sticks to the reset value within 1 % of the range so "exactly zero" is easy to reach. */
private fun snap(value: Float, resetValue: Float, span: Float): Float = if (abs(value - resetValue) < span * SNAP) resetValue else value

private const val SNAP = 0.01f

/** "+25", "−40", "0" for a −1..1 value. */
fun percentText(value: Float): String {
    val percent = (value * 100).roundToInt()
    return if (percent > 0) "+$percent" else percent.toString()
}

/** Section heading inside a panel. */
@Composable
fun PanelHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp),
    )
}

/** Gradient tracks for colour sliders. */
object Tracks {
    val temperature = Brush.horizontalGradient(listOf(Color(0xFF3D7BD9), Color(0xFFBDBDBD), Color(0xFFE2B33C)))
    val tint = Brush.horizontalGradient(listOf(Color(0xFF3FA34D), Color(0xFFBDBDBD), Color(0xFFC54AB8)))
    val hue = Brush.horizontalGradient(
        listOf(Color(0xFFE5484D), Color(0xFFE5C64D), Color(0xFF4DE57A), Color(0xFF4DC3E5), Color(0xFF5A4DE5), Color(0xFFE54DC9), Color(0xFFE5484D)),
    )
    val lightness = Brush.horizontalGradient(listOf(Color(0xFF202020), Color(0xFFEEEEEE)))
}
