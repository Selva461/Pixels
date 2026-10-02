package com.pixels.enhancer.domain.geometry

import kotlin.math.abs
import kotlin.math.min

enum class CropCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * Crop-frame interaction maths, in normalised coordinates. A pixel aspect ratio (width/height)
 * becomes a normalised ratio by dividing by the frame's own aspect.
 */
object CropMath {

    /** The largest crop with [pixelRatio] that fits the frame, centred where [current] is centred. */
    fun largestCentered(current: CropRect, pixelRatio: Float, frameAspect: Float): CropRect {
        val normalisedRatio = pixelRatio / frameAspect
        val width = if (normalisedRatio >= 1f) 1f else normalisedRatio
        val height = if (normalisedRatio >= 1f) 1f / normalisedRatio else 1f
        val left = ((current.left + current.right) / 2f - width / 2f).coerceIn(0f, 1f - width)
        val top = ((current.top + current.bottom) / 2f - height / 2f).coerceIn(0f, 1f - height)
        return CropRect.of(left, top, left + width, top + height)
    }

    /** Moves the whole rectangle, stopping at the frame edges without changing its size. */
    fun move(crop: CropRect, dx: Float, dy: Float): CropRect {
        val clampedDx = dx.coerceIn(-crop.left, 1f - crop.right)
        val clampedDy = dy.coerceIn(-crop.top, 1f - crop.bottom)
        return CropRect.of(crop.left + clampedDx, crop.top + clampedDy, crop.right + clampedDx, crop.bottom + clampedDy)
    }

    /**
     * Drags [corner] by (dx, dy) with the opposite corner fixed. With [pixelRatio] set, the height
     * follows the width so the aspect ratio holds; the result is shrunk to fit if needed.
     */
    fun resize(crop: CropRect, corner: CropCorner, dx: Float, dy: Float, pixelRatio: Float?, frameAspect: Float): CropRect {
        val movesLeft = corner == CropCorner.TOP_LEFT || corner == CropCorner.BOTTOM_LEFT
        val movesTop = corner == CropCorner.TOP_LEFT || corner == CropCorner.TOP_RIGHT
        val anchorX = if (movesLeft) crop.right else crop.left
        val anchorY = if (movesTop) crop.bottom else crop.top
        val draggedX = (if (movesLeft) crop.left else crop.right) + dx
        val draggedY = (if (movesTop) crop.top else crop.bottom) + dy

        // Available room between the anchor and the frame edge in the dragged direction.
        val roomX = if (movesLeft) anchorX else 1f - anchorX
        val roomY = if (movesTop) anchorY else 1f - anchorY
        var width = (if (movesLeft) anchorX - draggedX else draggedX - anchorX).coerceIn(CropRect.MIN_SIZE, roomX)
        var height = (if (movesTop) anchorY - draggedY else draggedY - anchorY).coerceIn(CropRect.MIN_SIZE, roomY)

        if (pixelRatio != null) {
            val normalisedRatio = pixelRatio / frameAspect
            // Follow whichever axis the finger moved more, then fit both inside the room available.
            height = if (abs(dx) >= abs(dy)) width / normalisedRatio else height
            width = height * normalisedRatio
            val fit = min(1f, min(roomX / width, roomY / height))
            width *= fit
            height *= fit
        }
        val left = if (movesLeft) anchorX - width else anchorX
        val top = if (movesTop) anchorY - height else anchorY
        return CropRect.of(left, top, left + width, top + height)
    }
}
