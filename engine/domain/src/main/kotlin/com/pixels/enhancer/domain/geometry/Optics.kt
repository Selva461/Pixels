package com.pixels.enhancer.domain.geometry

/**
 * Manual lens corrections. All amounts are −1..1 slider values.
 *
 * - [distortion]: positive removes barrel distortion (wide-angle bulge), negative removes pincushion.
 * - [chromaticAberration]: −1..1, pulls red and blue fringes back onto green along the radius;
 *   positive shrinks the red channel and enlarges blue (red/cyan fringes), negative the reverse.
 * - [vignetting]: positive brightens dark lens corners, negative darkens them.
 */
data class LensCorrection(
    val distortion: Float = 0f,
    val chromaticAberration: Float = 0f,
    val vignetting: Float = 0f,
) {
    val isIdentity: Boolean get() = distortion == 0f && chromaticAberration == 0f && vignetting == 0f

    /** True when pixels move (needs resampling); vignetting alone is a gain. */
    val movesPixels: Boolean get() = distortion != 0f || chromaticAberration != 0f

    fun clamped() = LensCorrection(
        distortion.coerceIn(-1f, 1f),
        chromaticAberration.coerceIn(-1f, 1f),
        vignetting.coerceIn(-1f, 1f),
    )

    companion object {
        val NONE = LensCorrection()

        /** Radial coefficient k1 at full slider: r_src = r·(1 + k1·r²) in units of the half-diagonal. */
        const val MAX_DISTORTION_K1 = 0.18f

        /** Red/blue radial scale difference at full slider (≈ a few pixels at the corner of a 12 MP photo). */
        const val MAX_CA_SCALE = 0.0025f

        /** Corner gain at full vignetting slider, in stops. */
        const val MAX_VIGNETTING_EV = 1f
    }
}

/**
 * Manual perspective ("Upright") transform, applied after lens correction and before straighten.
 *
 * - [vertical]: positive straightens verticals that lean in at the top (shooting up at a building).
 * - [horizontal]: positive straightens lines that converge to the right.
 * - [rotate]: extra rotation in degrees, −10..10.
 * - [aspect]: positive stretches horizontally, negative vertically.
 * - [scale]: 0 fits automatically (no empty edges); positive zooms in further, negative zooms out.
 * - [offsetX]/[offsetY]: shift as a fraction of the frame, −1..1 (scaled to at most 25 %).
 */
data class Perspective(
    val vertical: Float = 0f,
    val horizontal: Float = 0f,
    val rotate: Float = 0f,
    val aspect: Float = 0f,
    val scale: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    val isIdentity: Boolean
        get() = vertical == 0f && horizontal == 0f && rotate == 0f && aspect == 0f && scale == 0f && offsetX == 0f && offsetY == 0f

    fun clamped() = Perspective(
        vertical.coerceIn(-1f, 1f),
        horizontal.coerceIn(-1f, 1f),
        rotate.coerceIn(-MAX_ROTATE_DEGREES, MAX_ROTATE_DEGREES),
        aspect.coerceIn(-1f, 1f),
        scale.coerceIn(-1f, 1f),
        offsetX.coerceIn(-1f, 1f),
        offsetY.coerceIn(-1f, 1f),
    )

    /**
     * The same on-screen correction after the picture is turned 90° clockwise: a left-right
     * keystone becomes a top-bottom one, and offsets and aspect swap axes.
     */
    fun rotatedClockwise() = copy(vertical = horizontal, horizontal = -vertical, aspect = -aspect, offsetX = -offsetY, offsetY = offsetX)

    /** The same on-screen correction after a horizontal mirror. */
    fun mirrored() = copy(horizontal = -horizontal, rotate = -rotate, offsetX = -offsetX)

    companion object {
        val NONE = Perspective()
        const val MAX_ROTATE_DEGREES = 10f

        /** Projective coefficient at full slider (in normalised −1..1 coordinates). */
        const val MAX_KEYSTONE = 0.45f
        const val MAX_ASPECT = 0.3f
        const val MAX_EXTRA_ZOOM = 0.5f
        const val MAX_OFFSET = 0.25f
    }
}
