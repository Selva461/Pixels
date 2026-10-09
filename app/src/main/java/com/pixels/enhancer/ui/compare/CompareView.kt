package com.pixels.enhancer.ui.compare

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.ui.theme.OnPhotoCanvas
import com.pixels.enhancer.ui.theme.PhotoCanvas
import com.pixels.enhancer.ui.theme.PhotoLabelBacking
import kotlin.math.roundToInt

enum class CompareMode { ORIGINAL, ENHANCED, COMPARE }

enum class SplitOrientation { HORIZONTAL, VERTICAL }

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 8f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Before/after viewer with pinch-zoom and pan. Zoom and pan apply to both images identically;
 * the divider stays fixed on screen, so the same detail is always compared. Neither image is
 * modified — this is purely a display-time clip.
 *
 * Changing [resetKey] resets zoom, pan and divider.
 */
@Composable
fun CompareView(
    original: ImageBitmap,
    enhanced: ImageBitmap,
    mode: CompareMode,
    split: SplitOrientation,
    beforeLabel: String,
    afterLabel: String,
    resetKey: Int,
    modifier: Modifier = Modifier,
    description: String? = null,
    dividerDescription: String? = null,
) {
    var scale by remember(resetKey) { mutableFloatStateOf(MIN_ZOOM) }
    var pan by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var divider by remember(resetKey, split) { mutableFloatStateOf(0.5f) }
    var holdingForOriginal by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier
            .clipToBounds()
            .background(PhotoCanvas)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .pointerInput(resetKey) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    scale = (scale * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    pan = clampPan(pan + panChange, scale, size.width.toFloat(), size.height.toFloat())
                }
            }
            .pointerInput(resetKey) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > MIN_ZOOM) MIN_ZOOM else DOUBLE_TAP_ZOOM
                        pan = Offset.Zero
                    },
                    // Press and hold shows the untouched original until the finger lifts.
                    onPress = {
                        holdingForOriginal = true
                        tryAwaitRelease()
                        holdingForOriginal = false
                    },
                )
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val transform = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = pan.x
            translationY = pan.y
        }

        when (if (holdingForOriginal) CompareMode.ORIGINAL else mode) {
            CompareMode.ORIGINAL -> {
                FitImage(original, transform)
                if (holdingForOriginal) Label(beforeLabel, Alignment.TopStart)
            }
            CompareMode.ENHANCED -> FitImage(enhanced, transform)
            CompareMode.COMPARE -> {
                FitImage(original, transform)
                // The clip is applied outside the zoom transform so the divider stays put on screen.
                Box(
                    Modifier.fillMaxSize().drawWithContent {
                        if (split == SplitOrientation.HORIZONTAL) {
                            clipRect(left = size.width * divider) { this@drawWithContent.drawContent() }
                        } else {
                            clipRect(top = size.height * divider) { this@drawWithContent.drawContent() }
                        }
                    },
                ) { FitImage(enhanced, transform) }
                DividerHandle(split, divider, widthPx, heightPx, dividerDescription) { delta ->
                    divider = (divider + delta).coerceIn(0f, 1f)
                }
                Label(beforeLabel, Alignment.TopStart)
                Label(afterLabel, if (split == SplitOrientation.HORIZONTAL) Alignment.TopEnd else Alignment.BottomStart)
            }
        }
    }
}

@Composable
private fun FitImage(bitmap: ImageBitmap, modifier: Modifier) {
    Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
}

/**
 * The split line and its round knob. The knob's touch area is 48 dp around the 28 dp circle, and
 * screen readers can move the split like a slider (swipe up or down) without dragging.
 */
@Suppress("LongParameterList")
@Composable
private fun DividerHandle(
    split: SplitOrientation,
    position: Float,
    widthPx: Float,
    heightPx: Float,
    description: String?,
    onDrag: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val lineThickness = 2.dp
    val knob = 48.dp
    val knobPx = with(density) { knob.toPx() }
    val dragModifier = Modifier
        .semantics {
            if (description != null) contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(position, 0f..1f)
            setProgress { target ->
                onDrag(target.coerceIn(0f, 1f) - position)
                true
            }
        }
        .pointerInput(split) {
            detectDragGestures { change, amount ->
                change.consume()
                onDrag(if (split == SplitOrientation.HORIZONTAL) amount.x / widthPx else amount.y / heightPx)
            }
        }
    if (split == SplitOrientation.HORIZONTAL) {
        val x = (widthPx * position).roundToInt()
        Box(Modifier.offset { IntOffset(x - with(density) { lineThickness.roundToPx() } / 2, 0) }.width(lineThickness).fillMaxHeight().background(Color.White))
        Box(
            Modifier
                .offset { IntOffset((x - knobPx / 2).roundToInt(), ((heightPx - knobPx) / 2).roundToInt()) }
                .size(knob)
                .then(dragModifier)
                .padding(KNOB_INSET)
                .clip(CircleShape)
                .background(Color.White),
        )
    } else {
        val y = (heightPx * position).roundToInt()
        Box(Modifier.offset { IntOffset(0, y - with(density) { lineThickness.roundToPx() } / 2) }.height(lineThickness).fillMaxWidth().background(Color.White))
        Box(
            Modifier
                .offset { IntOffset(((widthPx - knobPx) / 2).roundToInt(), (y - knobPx / 2).roundToInt()) }
                .size(knob)
                .then(dragModifier)
                .padding(KNOB_INSET)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun BoxScope.Label(text: String, alignment: Alignment) {
    Text(
        text,
        color = OnPhotoCanvas,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .align(alignment)
            .padding(8.dp)
            .background(PhotoLabelBacking, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** (48 dp touch area − 28 dp visible knob) / 2. */
private val KNOB_INSET = 10.dp

/** Keeps the zoomed image covering the viewport: pan is limited to the overflow on each side. */
private fun clampPan(pan: Offset, scale: Float, width: Float, height: Float): Offset {
    val maxX = (scale - 1f) * width / 2f
    val maxY = (scale - 1f) * height / 2f
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}
