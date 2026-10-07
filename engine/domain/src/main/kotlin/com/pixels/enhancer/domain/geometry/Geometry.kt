package com.pixels.enhancer.domain.geometry

/**
 * Crop rectangle in normalised coordinates (0..1) of the image after rotation, flip and
 * straightening. Always valid: construct through [of], which clamps and enforces a minimum size.
 */
class CropRect private constructor(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    /** The same region after the image is turned 90° clockwise. */
    fun rotatedClockwise() = of(1f - bottom, left, 1f - top, right)

    fun rotatedCounterClockwise() = of(top, 1f - right, bottom, 1f - left)

    fun flippedHorizontally() = of(1f - right, top, 1f - left, bottom)

    // Not a data class: a generated copy() would bypass the clamping in of().
    override fun equals(other: Any?) =
        other is CropRect && left == other.left && top == other.top && right == other.right && bottom == other.bottom

    override fun hashCode() = listOf(left, top, right, bottom).hashCode()

    override fun toString() = "CropRect($left, $top, $right, $bottom)"

    companion object {
        /** Smallest crop side as a fraction of the image, so a crop can never collapse to nothing. */
        const val MIN_SIZE = 0.05f

        val FULL = CropRect(0f, 0f, 1f, 1f)

        fun of(left: Float, top: Float, right: Float, bottom: Float): CropRect {
            val l = left.coerceIn(0f, 1f - MIN_SIZE)
            val t = top.coerceIn(0f, 1f - MIN_SIZE)
            return CropRect(l, t, right.coerceIn(l + MIN_SIZE, 1f), bottom.coerceIn(t + MIN_SIZE, 1f))
        }
    }
}

/**
 * User geometry, applied in this order: [quarterTurns] clockwise, horizontal [flipHorizontal],
 * [lens] correction and [perspective] (one warp, auto-zoomed so no empty edges appear),
 * [straightenDegrees] (with automatic crop so no empty corners appear), then [crop].
 *
 * The helper functions keep what the user *sees* consistent: e.g. rotating a flipped image
 * clockwise on screen turns the underlying image counter-clockwise.
 */
data class Geometry(
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val straightenDegrees: Float = 0f,
    val crop: CropRect = CropRect.FULL,
    val lens: LensCorrection = LensCorrection.NONE,
    val perspective: Perspective = Perspective.NONE,
) {
    val isIdentity: Boolean
        get() = quarterTurns == 0 && !flipHorizontal && straightenDegrees == 0f && crop.isFull && lens.isIdentity && perspective.isIdentity

    /** True when the output's width and height are swapped relative to the source. */
    val swapsAxes: Boolean get() = quarterTurns % 2 == 1

    fun rotatedClockwise(): Geometry = copy(
        quarterTurns = Math.floorMod(quarterTurns + if (flipHorizontal) -1 else 1, TURNS),
        crop = crop.rotatedClockwise(),
        perspective = perspective.rotatedClockwise(),
    )

    fun rotatedCounterClockwise(): Geometry = copy(
        quarterTurns = Math.floorMod(quarterTurns + if (flipHorizontal) 1 else -1, TURNS),
        crop = crop.rotatedCounterClockwise(),
        perspective = perspective.rotatedClockwise().rotatedClockwise().rotatedClockwise(),
    )

    /** Mirroring a straightened image reverses the tilt, so the angle is negated to keep the picture level. */
    fun flipped(): Geometry = copy(
        flipHorizontal = !flipHorizontal,
        straightenDegrees = negate(straightenDegrees),
        crop = crop.flippedHorizontally(),
        perspective = perspective.mirrored(),
    )

    fun withLens(value: LensCorrection): Geometry = copy(lens = value.clamped())

    fun withPerspective(value: Perspective): Geometry = copy(perspective = value.clamped())

    /** Upside-down mirror of what is on screen: a horizontal flip followed by a half turn. */
    fun flippedVertically(): Geometry = flipped().rotatedClockwise().rotatedClockwise()

    fun straightened(degrees: Float): Geometry = copy(straightenDegrees = degrees.coerceIn(-MAX_STRAIGHTEN_DEGREES, MAX_STRAIGHTEN_DEGREES))

    fun withoutCrop(): Geometry = copy(crop = CropRect.FULL)

    companion object {
        val NONE = Geometry()
        const val MAX_STRAIGHTEN_DEGREES = 45f
        private const val TURNS = 4
    }
}
