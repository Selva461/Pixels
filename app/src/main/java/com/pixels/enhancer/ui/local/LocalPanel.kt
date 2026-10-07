package com.pixels.enhancer.ui.local

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.local.MaskShape
import com.pixels.enhancer.ui.theme.PhotoCanvas
import kotlin.math.min
import kotlin.math.roundToInt

private const val PERCENT = 100
private val HANDLE_COLOR = Color.White
private val OUTLINE_COLOR = Color.White.copy(alpha = 0.8f)

/** Callbacks for the Local tab. */
class LocalActions(
    val onAddRadial: () -> Unit,
    val onAddLinear: () -> Unit,
    val onChanged: (LocalAdjustment) -> Unit,
    val onRemove: (Int) -> Unit,
    val onSelect: (Int?) -> Unit,
)

/** Mask list and the selected mask's settings. */
@Composable
fun LocalPanel(
    adjustments: LocalAdjustments,
    selectedId: Int?,
    actions: LocalActions,
    onEditFinished: () -> Unit,
) {
    val selected = adjustments.items.firstOrNull { it.id == selectedId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            adjustments.items.forEachIndexed { index, item ->
                val kind = stringResource(if (item.shape is MaskShape.Radial) R.string.local_radial else R.string.local_linear)
                FilterChip(item.id == selectedId, { actions.onSelect(item.id) }, { Text("${index + 1} · $kind") })
            }
            val canAdd = adjustments.items.size < LocalAdjustments.MAX_ITEMS
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = actions.onAddRadial, enabled = canAdd) { Text(stringResource(R.string.local_add_radial)) }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = actions.onAddLinear, enabled = canAdd) { Text(stringResource(R.string.local_add_linear)) }
        }
        if (selected == null) {
            Text(stringResource(R.string.local_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected.invert, { actions.onChanged(selected.copy(invert = !selected.invert)); onEditFinished() }, { Text(stringResource(R.string.local_invert)) })
            TextButton(onClick = { actions.onRemove(selected.id) }) { Text(stringResource(R.string.local_delete)) }
        }
        val shape = selected.shape
        if (shape is MaskShape.Radial) {
            MaskSlider(stringResource(R.string.local_feather), shape.feather, 0f..1f, { actions.onChanged(selected.copy(shape = shape.copy(feather = it))) }, onEditFinished)
        }
        MaskSlider(stringResource(R.string.local_exposure), selected.exposure, -1f..1f, { actions.onChanged(selected.copy(exposure = it)) }, onEditFinished)
        MaskSlider(stringResource(R.string.local_contrast), selected.contrast, -1f..1f, { actions.onChanged(selected.copy(contrast = it)) }, onEditFinished)
        MaskSlider(stringResource(R.string.local_saturation), selected.saturation, -1f..1f, { actions.onChanged(selected.copy(saturation = it)) }, onEditFinished)
        MaskSlider(stringResource(R.string.local_temperature), selected.temperature, -1f..1f, { actions.onChanged(selected.copy(temperature = it)) }, onEditFinished)
    }
}

@Composable
private fun MaskSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text((value * PERCENT).roundToInt().let { if (it > 0) "+$it" else it.toString() }, style = MaterialTheme.typography.labelMedium)
    }
    Slider(value = value, onValueChange = onChange, onValueChangeFinished = onFinished, valueRange = range)
}

private enum class Handle { CENTER, RADIUS_X, RADIUS_Y, START, END }

/**
 * The edited photo with draggable handles for the selected mask. Radial: drag the centre to move,
 * the side/top dots to resize. Linear: drag the full-effect line (start) and the no-effect line (end).
 */
@Composable
fun LocalMaskEditor(image: ImageBitmap, selected: LocalAdjustment?, onShapeChanged: (MaskShape) -> Unit, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(selected)
    val slop = with(LocalDensity.current) { 32.dp.toPx() }
    var handle by remember { mutableStateOf<Handle?>(null) }
    BoxWithConstraints(modifier.clipToBounds().background(PhotoCanvas)) {
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        val scale = min(boxWidth / image.width, boxHeight / image.height)
        val frame = Rect(
            Offset((boxWidth - image.width * scale) / 2f, (boxHeight - image.height * scale) / 2f),
            Size(image.width * scale, image.height * scale),
        )
        val currentFrame by rememberUpdatedState(frame)
        Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize().pointerInput(image) {
                detectDragGestures(
                    onDragStart = { position -> handle = current?.let { hitTest(it.shape, position, currentFrame, slop) } },
                    onDragEnd = {
                        handle = null
                        onFinished()
                    },
                    onDragCancel = { handle = null },
                ) { change, drag ->
                    val item = current ?: return@detectDragGestures
                    val target = handle ?: return@detectDragGestures
                    change.consume()
                    onShapeChanged(moved(item.shape, target, drag.x / currentFrame.width, drag.y / currentFrame.height))
                }
            },
        ) {
            val item = selected ?: return@Canvas
            fun toScreen(x: Float, y: Float) = Offset(frame.left + x * frame.width, frame.top + y * frame.height)
            val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
            when (val shape = item.shape) {
                is MaskShape.Radial -> {
                    val center = toScreen(shape.centerX, shape.centerY)
                    val size = Size(shape.radiusX * 2 * frame.width, shape.radiusY * 2 * frame.height)
                    drawOval(OUTLINE_COLOR, center - Offset(size.width / 2, size.height / 2), size, style = Stroke(2.dp.toPx()))
                    val inner = size * (1f - shape.feather)
                    drawOval(OUTLINE_COLOR, center - Offset(inner.width / 2, inner.height / 2), inner, style = Stroke(1.dp.toPx(), pathEffect = dash))
                    drawCircle(HANDLE_COLOR, 7.dp.toPx(), center)
                    drawCircle(HANDLE_COLOR, 6.dp.toPx(), toScreen(shape.centerX + shape.radiusX, shape.centerY))
                    drawCircle(HANDLE_COLOR, 6.dp.toPx(), toScreen(shape.centerX, shape.centerY - shape.radiusY))
                }
                is MaskShape.Linear -> {
                    val start = toScreen(shape.startX, shape.startY)
                    val end = toScreen(shape.endX, shape.endY)
                    val direction = end - start
                    val length = direction.getDistance().coerceAtLeast(1f)
                    val perpendicular = Offset(-direction.y / length, direction.x / length) * (frame.width + frame.height)
                    drawLine(OUTLINE_COLOR, start - perpendicular, start + perpendicular, strokeWidth = 2.dp.toPx())
                    drawLine(OUTLINE_COLOR, end - perpendicular, end + perpendicular, strokeWidth = 1.dp.toPx(), pathEffect = dash)
                    drawCircle(HANDLE_COLOR, 7.dp.toPx(), start)
                    drawCircle(HANDLE_COLOR, 6.dp.toPx(), end)
                }
            }
        }
    }
}

private fun hitTest(shape: MaskShape, position: Offset, frame: Rect, slop: Float): Handle? {
    fun screen(x: Float, y: Float) = Offset(frame.left + x * frame.width, frame.top + y * frame.height)
    val candidates = when (shape) {
        is MaskShape.Radial -> listOf(
            Handle.RADIUS_X to screen(shape.centerX + shape.radiusX, shape.centerY),
            Handle.RADIUS_Y to screen(shape.centerX, shape.centerY - shape.radiusY),
            Handle.CENTER to screen(shape.centerX, shape.centerY),
        )
        is MaskShape.Linear -> listOf(Handle.START to screen(shape.startX, shape.startY), Handle.END to screen(shape.endX, shape.endY))
    }
    return candidates.map { it.first to (it.second - position).getDistance() }.filter { it.second <= slop }.minByOrNull { it.second }?.first
}

private const val MIN_RADIUS = 0.03f
private const val MAX_RADIUS = 1.5f

private fun moved(shape: MaskShape, handle: Handle, dx: Float, dy: Float): MaskShape = when (shape) {
    is MaskShape.Radial -> when (handle) {
        Handle.CENTER -> shape.copy(centerX = (shape.centerX + dx).coerceIn(0f, 1f), centerY = (shape.centerY + dy).coerceIn(0f, 1f))
        Handle.RADIUS_X -> shape.copy(radiusX = (shape.radiusX + dx).coerceIn(MIN_RADIUS, MAX_RADIUS))
        Handle.RADIUS_Y -> shape.copy(radiusY = (shape.radiusY - dy).coerceIn(MIN_RADIUS, MAX_RADIUS))
        else -> shape
    }
    is MaskShape.Linear -> when (handle) {
        Handle.START -> shape.copy(startX = (shape.startX + dx).coerceIn(0f, 1f), startY = (shape.startY + dy).coerceIn(0f, 1f))
        Handle.END -> shape.copy(endX = (shape.endX + dx).coerceIn(0f, 1f), endY = (shape.endY + dy).coerceIn(0f, 1f))
        else -> shape
    }
}

