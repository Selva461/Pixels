package com.pixels.enhancer.ui.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.domain.geometry.CropCorner
import com.pixels.enhancer.domain.geometry.CropMath
import com.pixels.enhancer.domain.geometry.CropRect
import com.pixels.enhancer.ui.theme.PhotoCanvas
import kotlin.math.min

private enum class DragTarget { MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

private val SCRIM = Color.Black.copy(alpha = 0.55f)
private val GUIDE = Color.White.copy(alpha = 0.45f)

/**
 * Shows the uncropped [image] with a draggable crop frame: drag inside to move, drag a corner to
 * resize. [pixelRatio] (width / height) locks the aspect ratio when set.
 */
@Composable
fun CropEditor(
    image: ImageBitmap,
    crop: CropRect,
    pixelRatio: Float?,
    onCropChanged: (CropRect) -> Unit,
    onCropFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCrop by rememberUpdatedState(crop)
    val currentRatio by rememberUpdatedState(pixelRatio)
    val frameAspect = image.width.toFloat() / image.height
    val touchSlopPx = with(LocalDensity.current) { 36.dp.toPx() }
    var target by remember { mutableStateOf<DragTarget?>(null) }

    BoxWithConstraints(modifier.clipToBounds().background(PhotoCanvas)) {
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        val scale = min(boxWidth / image.width, boxHeight / image.height)
        val imageRect = Rect(
            offset = Offset((boxWidth - image.width * scale) / 2f, (boxHeight - image.height * scale) / 2f),
            size = Size(image.width * scale, image.height * scale),
        )
        // Gesture callbacks outlive recompositions; read the latest layout through this.
        val currentImageRect by rememberUpdatedState(imageRect)

        Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize().pointerInput(image) {
                detectDragGestures(
                    onDragStart = { position -> target = hitTest(position, cropRectOnScreen(currentCrop, currentImageRect), touchSlopPx) },
                    onDragEnd = {
                        target = null
                        onCropFinished()
                    },
                    onDragCancel = { target = null },
                ) { change, drag ->
                    val dragTarget = target ?: return@detectDragGestures
                    change.consume()
                    val dx = drag.x / currentImageRect.width
                    val dy = drag.y / currentImageRect.height
                    val updated = when (dragTarget) {
                        DragTarget.MOVE -> CropMath.move(currentCrop, dx, dy)
                        DragTarget.TOP_LEFT -> CropMath.resize(currentCrop, CropCorner.TOP_LEFT, dx, dy, currentRatio, frameAspect)
                        DragTarget.TOP_RIGHT -> CropMath.resize(currentCrop, CropCorner.TOP_RIGHT, dx, dy, currentRatio, frameAspect)
                        DragTarget.BOTTOM_LEFT -> CropMath.resize(currentCrop, CropCorner.BOTTOM_LEFT, dx, dy, currentRatio, frameAspect)
                        DragTarget.BOTTOM_RIGHT -> CropMath.resize(currentCrop, CropCorner.BOTTOM_RIGHT, dx, dy, currentRatio, frameAspect)
                    }
                    onCropChanged(updated)
                }
            },
        ) {
            drawCropFrame(cropRectOnScreen(crop, imageRect), imageRect)
        }
    }
}

private fun cropRectOnScreen(crop: CropRect, imageRect: Rect) = Rect(
    left = imageRect.left + crop.left * imageRect.width,
    top = imageRect.top + crop.top * imageRect.height,
    right = imageRect.left + crop.right * imageRect.width,
    bottom = imageRect.top + crop.bottom * imageRect.height,
)

private fun hitTest(position: Offset, frame: Rect, slop: Float): DragTarget? {
    fun near(corner: Offset) = (position - corner).getDistance() <= slop
    return when {
        near(frame.topLeft) -> DragTarget.TOP_LEFT
        near(frame.topRight) -> DragTarget.TOP_RIGHT
        near(frame.bottomLeft) -> DragTarget.BOTTOM_LEFT
        near(frame.bottomRight) -> DragTarget.BOTTOM_RIGHT
        frame.contains(position) -> DragTarget.MOVE
        else -> null
    }
}

private fun DrawScope.drawCropFrame(frame: Rect, imageRect: Rect) {
    // Dim everything outside the crop.
    drawRect(SCRIM, Offset(imageRect.left, imageRect.top), Size(imageRect.width, frame.top - imageRect.top))
    drawRect(SCRIM, Offset(imageRect.left, frame.bottom), Size(imageRect.width, imageRect.bottom - frame.bottom))
    drawRect(SCRIM, Offset(imageRect.left, frame.top), Size(frame.left - imageRect.left, frame.height))
    drawRect(SCRIM, Offset(frame.right, frame.top), Size(imageRect.right - frame.right, frame.height))

    // Rule-of-thirds guides.
    for (i in 1..2) {
        val x = frame.left + frame.width * i / 3f
        val y = frame.top + frame.height * i / 3f
        drawLine(GUIDE, Offset(x, frame.top), Offset(x, frame.bottom), strokeWidth = 1.dp.toPx())
        drawLine(GUIDE, Offset(frame.left, y), Offset(frame.right, y), strokeWidth = 1.dp.toPx())
    }
    drawRect(Color.White, frame.topLeft, frame.size, style = Stroke(width = 2.dp.toPx()))

    val handle = 18.dp.toPx()
    val thickness = 4.dp.toPx()
    listOf(
        frame.topLeft to Offset(1f, 1f),
        frame.topRight to Offset(-1f, 1f),
        frame.bottomLeft to Offset(1f, -1f),
        frame.bottomRight to Offset(-1f, -1f),
    ).forEach { (corner, direction) ->
        drawLine(Color.White, corner, corner + Offset(direction.x * handle, 0f), strokeWidth = thickness)
        drawLine(Color.White, corner, corner + Offset(0f, direction.y * handle), strokeWidth = thickness)
    }
}
