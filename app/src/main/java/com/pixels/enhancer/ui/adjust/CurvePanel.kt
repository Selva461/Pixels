package com.pixels.enhancer.ui.adjust

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.analysis.Histogram
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.planning.CurveChannel
import com.pixels.enhancer.domain.planning.CurvePoint
import com.pixels.enhancer.domain.planning.CurvePoints
import com.pixels.enhancer.domain.planning.CurvePreset
import com.pixels.enhancer.ui.components.ChoiceChip
import com.pixels.enhancer.ui.theme.CurveInk

private const val CURVE_SAMPLES = 64

/**
 * Tone-curve editor over the histogram of the edited preview. Tap to add a point, drag to move,
 * double-tap a point to remove it. The end points move vertically only. Point validity (order,
 * spacing, limits) is enforced by [CurvePoints.of], so any gesture yields a valid curve.
 */
@Composable
fun CurvePanel(
    edit: EditState,
    histogram: Histogram?,
    onCurveChanged: (CurveChannel, CurvePoints) -> Unit,
    onEditFinished: () -> Unit,
    onResetChannel: (CurveChannel) -> Unit,
    onPreset: (CurveChannel, CurvePreset) -> Unit,
) {
    var channel by rememberSaveable { mutableStateOf(CurveChannel.MASTER) }
    val curve = edit.toneCurves[channel]
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // A square as tall as the panel, but never more than half the width, so the controls
        // beside it keep room on narrow screens and with large text.
        val side = minOf(maxHeight, maxWidth / 2)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CurveCanvas(
                curve = curve,
                channel = channel,
                histogram = histogram,
                onChange = { onCurveChanged(channel, it) },
                onFinished = onEditFinished,
                modifier = Modifier.size(side),
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CurveChannel.entries.forEach { option ->
                        ChoiceChip(channel == option, { channel = option }, option.label, edited = !edit.toneCurves[option].isIdentity)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CurvePreset.entries.forEach { preset -> ChoiceChip(curve == preset.points, { onPreset(channel, preset) }, preset.label) }
                }
                Text(stringResource(R.string.curve_hint), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onResetChannel(channel) }, enabled = !curve.isIdentity) { Text(stringResource(R.string.curve_reset)) }
            }
        }
    }
}

@Composable
private fun CurveCanvas(
    curve: CurvePoints,
    channel: CurveChannel,
    histogram: Histogram?,
    onChange: (CurvePoints) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier,
) {
    val currentCurve by rememberUpdatedState(curve)
    val slop = with(LocalDensity.current) { 24.dp.toPx() }
    var dragging by remember { mutableStateOf<Int?>(null) }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val histogramColor = MaterialTheme.colorScheme.surfaceVariant
    val lineColor = when (channel) {
        CurveChannel.MASTER -> MaterialTheme.colorScheme.onSurface
        CurveChannel.RED -> CurveInk.Red
        CurveChannel.GREEN -> CurveInk.Green
        CurveChannel.BLUE -> CurveInk.Blue
    }
    val description = stringResource(R.string.curve_description, channel.label)
    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(channel) {
                detectTapGestures(
                    onTap = { position ->
                        val point = toCurve(position, size.width.toFloat(), size.height.toFloat())
                        if (nearest(currentCurve, position, size.width.toFloat(), size.height.toFloat(), slop) == null) {
                            onChange(CurvePoints.of(currentCurve.points + point))
                            onFinished()
                        }
                    },
                    onDoubleTap = { position ->
                        val index = nearest(currentCurve, position, size.width.toFloat(), size.height.toFloat(), slop)
                        if (index != null && index != 0 && index != currentCurve.points.lastIndex) {
                            onChange(CurvePoints.of(currentCurve.points.filterIndexed { i, _ -> i != index }))
                            onFinished()
                        }
                    },
                )
            }
            .pointerInput(channel) {
                detectDragGestures(
                    onDragStart = { position -> dragging = nearest(currentCurve, position, size.width.toFloat(), size.height.toFloat(), slop) },
                    onDragEnd = {
                        dragging = null
                        onFinished()
                    },
                    onDragCancel = { dragging = null },
                ) { change, _ ->
                    val index = dragging ?: return@detectDragGestures
                    change.consume()
                    val target = toCurve(change.position, size.width.toFloat(), size.height.toFloat())
                    val points = currentCurve.points.toMutableList()
                    val isEnd = index == 0 || index == points.lastIndex
                    val x = if (isEnd) points[index].x else target.x.coerceIn(points[index - 1].x + CurvePoints.MIN_GAP, points[index + 1].x - CurvePoints.MIN_GAP)
                    points[index] = CurvePoint(x, target.y)
                    onChange(CurvePoints.of(points))
                }
            },
    ) {
        histogram?.let { drawHistogram(it.luma, histogramColor) }
        for (i in 1..3) {
            val p = size.width * i / 4f
            drawLine(gridColor, Offset(p, 0f), Offset(p, size.height))
            drawLine(gridColor, Offset(0f, size.height * i / 4f), Offset(size.width, size.height * i / 4f))
        }
        drawLine(gridColor, Offset(0f, size.height), Offset(size.width, 0f))
        val path = Path()
        for (step in 0..CURVE_SAMPLES) {
            val x = step / CURVE_SAMPLES.toFloat()
            val point = Offset(x * size.width, (1f - curve.valueAt(x)) * size.height)
            if (step == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
        curve.points.forEach { point ->
            drawCircle(lineColor, radius = 5.dp.toPx(), center = Offset(point.x * size.width, (1f - point.y) * size.height))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHistogram(values: IntArray, color: Color) {
    val peak = values.max().coerceAtLeast(1).toFloat()
    val barWidth = size.width / values.size
    values.forEachIndexed { index, count ->
        val barHeight = size.height * count / peak
        drawRect(color, Offset(index * barWidth, size.height - barHeight), Size(barWidth, barHeight))
    }
}

private fun toCurve(position: Offset, width: Float, height: Float) =
    CurvePoint((position.x / width).coerceIn(0f, 1f), (1f - position.y / height).coerceIn(0f, 1f))

private fun nearest(curve: CurvePoints, position: Offset, width: Float, height: Float, slop: Float): Int? =
    curve.points.indices
        .map { it to (Offset(curve.points[it].x * width, (1f - curve.points[it].y) * height) - position).getDistance() }
        .filter { it.second <= slop }
        .minByOrNull { it.second }
        ?.first

