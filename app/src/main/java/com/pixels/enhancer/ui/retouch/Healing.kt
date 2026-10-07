package com.pixels.enhancer.ui.retouch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.retouch.Retouch
import com.pixels.enhancer.domain.retouch.RetouchMode
import com.pixels.enhancer.domain.retouch.RetouchSpot
import com.pixels.enhancer.ui.components.ProSlider
import com.pixels.enhancer.ui.components.percentText
import com.pixels.enhancer.ui.editor.EditorActions
import com.pixels.enhancer.ui.editor.HealSettings
import com.pixels.enhancer.ui.panels.PanelColumn
import com.pixels.enhancer.ui.theme.PhotoCanvas
import kotlin.math.min
import kotlin.math.roundToInt

private val SPOT_COLOR = Color.White
private val SOURCE_COLOR = Color.White.copy(alpha = 0.7f)
private val SELECTED_COLOR = Color(0xFFE39A72)

@Composable
fun HealingPanel(retouch: Retouch, selectedId: Int?, settings: HealSettings, actions: EditorActions) {
    val selected = retouch.spots.firstOrNull { it.id == selectedId }
    PanelColumn {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(settings.mode == RetouchMode.HEAL, { actions.onHealSettingsChanged(settings.copy(mode = RetouchMode.HEAL)); actions.onEditFinished() }, { Text(stringResource(R.string.heal_mode_heal)) })
            FilterChip(settings.mode == RetouchMode.CLONE, { actions.onHealSettingsChanged(settings.copy(mode = RetouchMode.CLONE)); actions.onEditFinished() }, { Text(stringResource(R.string.heal_mode_clone)) })
            Text(
                stringResource(R.string.heal_count, retouch.spots.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (selected != null) {
                IconButton(onClick = { actions.onSpotRemoved(selected.id) }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete)) }
            }
        }
        Text(
            stringResource(R.string.heal_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        ProSlider(
            stringResource(R.string.heal_size), settings.radius, "${(settings.radius * 1000).roundToInt() / 10f}%",
            { actions.onHealSettingsChanged(settings.copy(radius = it)) }, actions::onEditFinished,
            range = RetouchSpot.MIN_RADIUS..0.12f, resetValue = RetouchSpot.DEFAULT_RADIUS,
        )
        ProSlider(
            stringResource(R.string.heal_feather), settings.feather, percentText(settings.feather),
            { actions.onHealSettingsChanged(settings.copy(feather = it)) }, actions::onEditFinished,
            range = 0f..1f, resetValue = RetouchSpot.DEFAULT_FEATHER,
        )
        if (selected != null) {
            ProSlider(
                stringResource(R.string.heal_opacity), selected.opacity, percentText(selected.opacity),
                { actions.onSpotChanged(selected.copy(opacity = it)) }, actions::onEditFinished, range = 0f..1f, resetValue = 1f,
            )
        }
    }
}

private enum class SpotHandle { TARGET, SOURCE }

/**
 * Photo for the Healing tool. Tap empty space to repair a spot there (the source is found
 * automatically); tap a spot to select it; drag a selected spot's solid circle to move the repair
 * or its dashed circle to choose a different source.
 */
@Composable
fun HealCanvas(image: ImageBitmap, retouch: Retouch, selectedId: Int?, actions: EditorActions, modifier: Modifier = Modifier) {
    val spots by rememberUpdatedState(retouch.spots)
    val selection by rememberUpdatedState(selectedId)
    var dragging by remember { mutableStateOf<Pair<Int, SpotHandle>?>(null) }
    BoxWithConstraints(modifier.clipToBounds().background(PhotoCanvas)) {
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        val scale = min(boxWidth / image.width, boxHeight / image.height)
        val frame = Rect(
            Offset((boxWidth - image.width * scale) / 2f, (boxHeight - image.height * scale) / 2f),
            Size(image.width * scale, image.height * scale),
        )
        val currentFrame by rememberUpdatedState(frame)
        fun screen(x: Float, y: Float) = Offset(currentFrame.left + x * currentFrame.width, currentFrame.top + y * currentFrame.height)
        fun hit(position: Offset): Pair<Int, SpotHandle>? {
            val ordered = spots.sortedByDescending { it.id == selection }
            for (spot in ordered) {
                val r = (spot.radius * currentFrame.width).coerceAtLeast(MIN_TOUCH)
                if ((screen(spot.targetX, spot.targetY) - position).getDistance() <= r) return spot.id to SpotHandle.TARGET
                if (spot.id == selection && (screen(spot.sourceX, spot.sourceY) - position).getDistance() <= r) return spot.id to SpotHandle.SOURCE
            }
            return null
        }
        Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(image) {
                    detectTapGestures { position ->
                        val hitSpot = hit(position)
                        when {
                            hitSpot != null -> actions.onSelectSpot(hitSpot.first)
                            currentFrame.contains(position) -> actions.onAddSpot(
                                (position.x - currentFrame.left) / currentFrame.width,
                                (position.y - currentFrame.top) / currentFrame.height,
                            )
                            else -> actions.onSelectSpot(null)
                        }
                    }
                }
                .pointerInput(image) {
                    detectDragGestures(
                        onDragStart = { position ->
                            dragging = hit(position)
                            dragging?.let { actions.onSelectSpot(it.first) }
                        },
                        onDragEnd = {
                            if (dragging != null) actions.onEditFinished()
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                    ) { change, drag ->
                        val (id, handle) = dragging ?: return@detectDragGestures
                        val spot = spots.firstOrNull { it.id == id } ?: return@detectDragGestures
                        change.consume()
                        val dx = drag.x / currentFrame.width
                        val dy = drag.y / currentFrame.height
                        actions.onSpotChanged(
                            if (handle == SpotHandle.TARGET) {
                                spot.copy(targetX = (spot.targetX + dx).coerceIn(0f, 1f), targetY = (spot.targetY + dy).coerceIn(0f, 1f))
                            } else {
                                spot.copy(sourceX = (spot.sourceX + dx).coerceIn(0f, 1f), sourceY = (spot.sourceY + dy).coerceIn(0f, 1f))
                            },
                        )
                    }
                },
        ) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            retouch.spots.forEach { spot ->
                val radius = spot.radius * frame.width
                val target = screen(spot.targetX, spot.targetY)
                val isSelected = spot.id == selectedId
                val color = if (isSelected) SELECTED_COLOR else SPOT_COLOR
                drawCircle(Color.Black.copy(alpha = 0.35f), radius, target, style = Stroke(3.dp.toPx()))
                drawCircle(color, radius, target, style = Stroke(1.5.dp.toPx()))
                if (isSelected) {
                    val source = screen(spot.sourceX, spot.sourceY)
                    drawCircle(SOURCE_COLOR, radius, source, style = Stroke(1.5.dp.toPx(), pathEffect = dash))
                    drawLine(SOURCE_COLOR, source, target, strokeWidth = 1.dp.toPx(), pathEffect = dash)
                }
            }
        }
    }
}

private const val MIN_TOUCH = 36f
