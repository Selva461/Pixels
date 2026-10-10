package com.pixels.enhancer.ui.local

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.local.BrushStroke
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.local.MaskShape
import com.pixels.enhancer.domain.local.RangeMask
import com.pixels.enhancer.ui.components.ChoiceChip
import com.pixels.enhancer.ui.components.PanelHeading
import com.pixels.enhancer.ui.components.ProSlider
import com.pixels.enhancer.ui.components.StateIconToggle
import com.pixels.enhancer.ui.components.Tracks
import com.pixels.enhancer.ui.components.percentText
import com.pixels.enhancer.ui.editor.EditorActions
import com.pixels.enhancer.ui.editor.MaskKind
import com.pixels.enhancer.ui.panels.PanelColumn
import com.pixels.enhancer.ui.panels.PanelHint
import com.pixels.enhancer.ui.theme.PhotoCanvas
import kotlin.math.min
import kotlin.math.roundToInt

private val HANDLE_COLOR = Color.White
private val HANDLE_EDGE = Color.Black.copy(alpha = 0.7f)
private val OUTLINE_COLOR = Color.White
private val BRUSH_PREVIEW = Color(0x66E5484D)

/** What dragging on the photo does in the Masking tool. */
enum class BrushMode { OFF, ADD, ERASE }

/** Brush settings for painting masks; size is a fraction of the image width. */
data class BrushSettings(val mode: BrushMode = BrushMode.OFF, val size: Float = BrushStroke.DEFAULT_RADIUS, val feather: Float = BrushStroke.DEFAULT_FEATHER, val flow: Float = 1f)

fun LocalAdjustment.kindLabelRes(): Int = when {
    range is RangeMask.Luminance -> R.string.mask_luminance
    range is RangeMask.Color -> R.string.mask_color
    shape is MaskShape.Radial -> R.string.mask_radial
    shape is MaskShape.Linear -> R.string.mask_linear
    else -> R.string.mask_brush
}

private fun LocalAdjustment.kindIcon(): ImageVector = when {
    range is RangeMask.Luminance -> Icons.Outlined.Exposure
    range is RangeMask.Color -> Icons.Outlined.Colorize
    shape is MaskShape.Radial -> Icons.Outlined.RadioButtonUnchecked
    shape is MaskShape.Linear -> Icons.Outlined.Gradient
    else -> Icons.Outlined.Brush
}

/** Mask list, add menu and the selected mask's settings. */
@Composable
fun MaskingPanel(
    adjustments: LocalAdjustments,
    selectedId: Int?,
    showOverlay: Boolean,
    brush: BrushSettings,
    onBrushChanged: (BrushSettings) -> Unit,
    actions: EditorActions,
) {
    val selected = adjustments.items.firstOrNull { it.id == selectedId }
    var addMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LocalAdjustment?>(null) }
    renaming?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.mask_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(LocalAdjustments.MAX_NAME_LENGTH) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.name_label)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onRenameMask(item.id, name)
                    renaming = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                OutlinedButton(
                    onClick = { addMenu = true },
                    enabled = adjustments.items.size < LocalAdjustments.MAX_ITEMS,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.mask_add))
                }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    listOf(
                        Triple(MaskKind.BRUSH, R.string.mask_brush, Icons.Outlined.Brush),
                        Triple(MaskKind.LINEAR, R.string.mask_linear, Icons.Outlined.Gradient),
                        Triple(MaskKind.RADIAL, R.string.mask_radial, Icons.Outlined.RadioButtonUnchecked),
                        Triple(MaskKind.LUMINANCE, R.string.mask_luminance, Icons.Outlined.Exposure),
                        Triple(MaskKind.COLOR, R.string.mask_color, Icons.Outlined.Colorize),
                    ).forEach { (kind, label, icon) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            leadingIcon = { Icon(icon, contentDescription = null) },
                            onClick = {
                                addMenu = false
                                actions.onAddMask(kind)
                                onBrushChanged(brush.copy(mode = if (kind == MaskKind.BRUSH) BrushMode.ADD else BrushMode.OFF))
                            },
                        )
                    }
                }
            }
            adjustments.items.forEachIndexed { index, item ->
                ChoiceChip(
                    selected = item.id == selectedId,
                    onClick = { actions.onSelectMask(if (item.id == selectedId) null else item.id) },
                    label = item.name.ifEmpty { "${index + 1} · ${stringResource(item.kindLabelRes())}" },
                    icon = item.kindIcon(),
                )
            }
        }
        if (selected == null) {
            PanelHint(stringResource(if (adjustments.items.isEmpty()) R.string.mask_empty_hint else R.string.mask_select_hint))
            return@Column
        }
        PanelColumn {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                StateIconToggle(showOverlay, actions::onMaskOverlayChanged, Icons.Outlined.Visibility, stringResource(R.string.mask_overlay))
                StateIconToggle(
                    checked = selected.invert,
                    onCheckedChange = {
                        actions.onLocalChanged(selected.copy(invert = it))
                        actions.onEditFinished()
                    },
                    icon = Icons.Outlined.InvertColors,
                    description = stringResource(R.string.local_invert),
                )
                IconButton(onClick = { actions.onDuplicateMask(selected.id) }, enabled = adjustments.items.size < LocalAdjustments.MAX_ITEMS) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.mask_duplicate))
                }
                IconButton(onClick = { renaming = selected }) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.mask_rename))
                }
                IconButton(onClick = { actions.onMaskRemoved(selected.id) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.local_delete))
                }
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceChip(brush.mode == BrushMode.ADD, { onBrushChanged(brush.copy(mode = if (brush.mode == BrushMode.ADD) BrushMode.OFF else BrushMode.ADD)) }, stringResource(R.string.mask_brush_add))
                    ChoiceChip(brush.mode == BrushMode.ERASE, { onBrushChanged(brush.copy(mode = if (brush.mode == BrushMode.ERASE) BrushMode.OFF else BrushMode.ERASE)) }, stringResource(R.string.mask_brush_erase))
                }
            }
            if (brush.mode != BrushMode.OFF) {
                ProSlider(stringResource(R.string.mask_brush_size), brush.size, "${(brush.size * 1000).roundToInt() / 10f}%", { onBrushChanged(brush.copy(size = it)) }, {}, range = BrushStroke.MIN_RADIUS..0.15f, resetValue = BrushStroke.DEFAULT_RADIUS)
                ProSlider(stringResource(R.string.mask_brush_feather), brush.feather, percentText(brush.feather), { onBrushChanged(brush.copy(feather = it)) }, {}, range = 0f..1f, resetValue = BrushStroke.DEFAULT_FEATHER)
                ProSlider(stringResource(R.string.mask_brush_flow), brush.flow, percentText(brush.flow), { onBrushChanged(brush.copy(flow = it)) }, {}, range = 0.05f..1f, resetValue = 1f)
            }
            RangeControls(selected, actions)
            val shape = selected.shape
            if (shape is MaskShape.Radial) {
                ProSlider(stringResource(R.string.local_feather), shape.feather, percentText(shape.feather), { actions.onLocalChanged(selected.copy(shape = shape.copy(feather = it))) }, actions::onEditFinished, range = 0f..1f, resetValue = 0.5f)
            }
            PanelHeading(stringResource(R.string.mask_adjustments))
            MaskSlider(R.string.local_exposure, selected.exposure, actions) { selected.copy(exposure = it) }
            MaskSlider(R.string.local_contrast, selected.contrast, actions) { selected.copy(contrast = it) }
            MaskSlider(R.string.local_highlights, selected.highlights, actions) { selected.copy(highlights = it) }
            MaskSlider(R.string.local_shadows, selected.shadows, actions) { selected.copy(shadows = it) }
            MaskSlider(R.string.local_temperature, selected.temperature, actions, Tracks.temperature) { selected.copy(temperature = it) }
            MaskSlider(R.string.local_tint, selected.tint, actions, Tracks.tint) { selected.copy(tint = it) }
            MaskSlider(R.string.local_saturation, selected.saturation, actions) { selected.copy(saturation = it) }
            MaskSlider(R.string.local_clarity, selected.clarity, actions) { selected.copy(clarity = it) }
            MaskSlider(R.string.local_sharpness, selected.sharpness, actions) { selected.copy(sharpness = it) }
        }
    }
}

@Composable
private fun RangeControls(selected: LocalAdjustment, actions: EditorActions) {
    when (val range = selected.range) {
        is RangeMask.Luminance -> {
            PanelHeading(stringResource(R.string.mask_luminance))
            ProSlider(stringResource(R.string.mask_range_low), range.low, "${(range.low * 100).roundToInt()}", { actions.onLocalChanged(selected.copy(range = range.copy(low = it.coerceAtMost(range.high - 0.02f)))) }, actions::onEditFinished, range = 0f..1f)
            ProSlider(stringResource(R.string.mask_range_high), range.high, "${(range.high * 100).roundToInt()}", { actions.onLocalChanged(selected.copy(range = range.copy(high = it.coerceAtLeast(range.low + 0.02f)))) }, actions::onEditFinished, range = 0f..1f, resetValue = 1f)
            ProSlider(stringResource(R.string.mask_range_smooth), range.smoothness, percentText(range.smoothness), { actions.onLocalChanged(selected.copy(range = range.copy(smoothness = it))) }, actions::onEditFinished, range = 0.01f..0.5f, resetValue = 0.1f)
        }
        is RangeMask.Color -> {
            PanelHeading(stringResource(R.string.mask_color))
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 10.dp).background(Color(range.red, range.green, range.blue), MaterialTheme.shapes.small).padding(12.dp))
                Text(stringResource(R.string.mask_color_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ProSlider(stringResource(R.string.mask_range_tolerance), range.tolerance, percentText(range.tolerance), { actions.onLocalChanged(selected.copy(range = range.copy(tolerance = it))) }, actions::onEditFinished, range = 0f..1f, resetValue = 0.3f)
        }
        null -> Unit
    }
}

@Composable
private fun MaskSlider(label: Int, value: Float, actions: EditorActions, track: androidx.compose.ui.graphics.Brush? = null, update: (Float) -> LocalAdjustment) {
    ProSlider(stringResource(label), value, percentText(value), { actions.onLocalChanged(update(it)) }, actions::onEditFinished, track = track)
}

private enum class Handle { CENTER, RADIUS_X, RADIUS_Y, START, END }

/**
 * The edited photo for the Masking tool: the selected mask's overlay, draggable handles for
 * linear/radial masks, brush painting (when a brush mode is on) and colour picking (tap) for
 * colour-range masks.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun MaskCanvas(
    image: ImageBitmap,
    overlay: ImageBitmap?,
    selected: LocalAdjustment?,
    brush: BrushSettings,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(selected)
    val currentBrush by rememberUpdatedState(brush)
    val slop = with(LocalDensity.current) { 32.dp.toPx() }
    var handle by remember { mutableStateOf<Handle?>(null) }
    val strokePoints = remember { mutableStateListOf<Offset>() }
    val description = stringResource(R.string.mask_canvas_description)
    BoxWithConstraints(modifier.clipToBounds().background(PhotoCanvas).semantics { contentDescription = description }) {
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        val scale = min(boxWidth / image.width, boxHeight / image.height)
        val frame = Rect(
            Offset((boxWidth - image.width * scale) / 2f, (boxHeight - image.height * scale) / 2f),
            Size(image.width * scale, image.height * scale),
        )
        val currentFrame by rememberUpdatedState(frame)
        fun normalised(position: Offset) = Offset(
            ((position.x - currentFrame.left) / currentFrame.width).coerceIn(0f, 1f),
            ((position.y - currentFrame.top) / currentFrame.height).coerceIn(0f, 1f),
        )
        Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        if (overlay != null) Image(overlay, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(image) {
                    detectTapGestures { position ->
                        val item = current ?: return@detectTapGestures
                        if (item.range is RangeMask.Color && currentBrush.mode == BrushMode.OFF) {
                            val point = normalised(position)
                            actions.onSampleMaskColor(point.x, point.y)
                        }
                    }
                }
                .pointerInput(image) {
                    detectDragGestures(
                        onDragStart = { position ->
                            val item = current
                            if (item != null && currentBrush.mode != BrushMode.OFF) {
                                strokePoints.clear()
                                strokePoints.add(normalised(position))
                            } else {
                                handle = item?.let { hitTest(it.shape, position, currentFrame, slop) }
                            }
                        },
                        onDragEnd = {
                            if (strokePoints.isNotEmpty()) {
                                val b = currentBrush
                                actions.onBrushStroke(
                                    BrushStroke(strokePoints.flatMap { listOf(it.x, it.y) }, b.size, b.feather, b.flow, erase = b.mode == BrushMode.ERASE),
                                )
                                strokePoints.clear()
                            } else if (handle != null) {
                                handle = null
                                actions.onEditFinished()
                            }
                        },
                        onDragCancel = {
                            strokePoints.clear()
                            handle = null
                        },
                    ) { change, drag ->
                        val item = current ?: return@detectDragGestures
                        if (strokePoints.isNotEmpty()) {
                            change.consume()
                            strokePoints.add(normalised(change.position))
                            return@detectDragGestures
                        }
                        val target = handle ?: return@detectDragGestures
                        change.consume()
                        actions.onLocalChanged(item.copy(shape = moved(item.shape, target, drag.x / currentFrame.width, drag.y / currentFrame.height)))
                    }
                },
        ) {
            fun toScreen(x: Float, y: Float) = Offset(frame.left + x * frame.width, frame.top + y * frame.height)
            if (strokePoints.size > 0) {
                val path = Path()
                strokePoints.forEachIndexed { i, p -> toScreen(p.x, p.y).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
                drawPath(
                    path,
                    if (brush.mode == BrushMode.ERASE) Color(0x66FFFFFF) else BRUSH_PREVIEW,
                    style = Stroke(width = brush.size * 2 * frame.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            val item = selected ?: return@Canvas
            val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
            // White lines and handles get a dark edge, so they show over bright skies and dark shadows alike.
            val edge = 1.5.dp.toPx()
            fun handleAt(position: Offset, radius: Float) {
                drawCircle(HANDLE_EDGE, radius + edge, position)
                drawCircle(HANDLE_COLOR, radius, position)
            }
            when (val shape = item.shape) {
                is MaskShape.Radial -> {
                    val center = toScreen(shape.centerX, shape.centerY)
                    val size = Size(shape.radiusX * 2 * frame.width, shape.radiusY * 2 * frame.height)
                    val outer = center - Offset(size.width / 2, size.height / 2)
                    drawOval(HANDLE_EDGE, outer, size, style = Stroke(2.dp.toPx() + edge * 2))
                    drawOval(OUTLINE_COLOR, outer, size, style = Stroke(2.dp.toPx()))
                    val inner = size * (1f - shape.feather)
                    val innerTopLeft = center - Offset(inner.width / 2, inner.height / 2)
                    drawOval(HANDLE_EDGE, innerTopLeft, inner, style = Stroke(1.dp.toPx() + edge * 2, pathEffect = dash))
                    drawOval(OUTLINE_COLOR, innerTopLeft, inner, style = Stroke(1.dp.toPx(), pathEffect = dash))
                    handleAt(center, 7.dp.toPx())
                    handleAt(toScreen(shape.centerX + shape.radiusX, shape.centerY), 6.dp.toPx())
                    handleAt(toScreen(shape.centerX, shape.centerY - shape.radiusY), 6.dp.toPx())
                }
                is MaskShape.Linear -> {
                    val start = toScreen(shape.startX, shape.startY)
                    val end = toScreen(shape.endX, shape.endY)
                    val direction = end - start
                    val length = direction.getDistance().coerceAtLeast(1f)
                    val perpendicular = Offset(-direction.y / length, direction.x / length) * (frame.width + frame.height)
                    drawLine(HANDLE_EDGE, start - perpendicular, start + perpendicular, strokeWidth = 2.dp.toPx() + edge * 2)
                    drawLine(OUTLINE_COLOR, start - perpendicular, start + perpendicular, strokeWidth = 2.dp.toPx())
                    drawLine(HANDLE_EDGE, end - perpendicular, end + perpendicular, strokeWidth = 1.dp.toPx() + edge * 2, pathEffect = dash)
                    drawLine(OUTLINE_COLOR, end - perpendicular, end + perpendicular, strokeWidth = 1.dp.toPx(), pathEffect = dash)
                    handleAt(start, 7.dp.toPx())
                    handleAt(end, 6.dp.toPx())
                }
                MaskShape.None, MaskShape.Full -> Unit
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
        MaskShape.None, MaskShape.Full -> emptyList()
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
    MaskShape.None, MaskShape.Full -> shape
}
