package com.pixels.enhancer.domain.geometry

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.Srgb
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lens correction and perspective in one inverse warp: every output pixel is traced back through
 * perspective, then lens distortion, to a position in the source, and sampled bilinearly (red and
 * blue at slightly different radii when chromatic aberration is corrected). Same output size as
 * the input; the frame is zoomed just enough that no empty edges appear, unless the user zooms out.
 */
object OpticsWarp {

    fun apply(image: PixelBuffer, lens: LensCorrection, perspective: Perspective): PixelBuffer {
        if (lens.isIdentity && perspective.isIdentity) return image
        val mapper = Mapper(image.width, image.height, lens, perspective)
        val out = IntArray(image.pixelCount)
        val point = FloatArray(2)
        val k1Ca = lens.chromaticAberration * LensCorrection.MAX_CA_SCALE
        val vignetteEv = lens.vignetting * LensCorrection.MAX_VIGNETTING_EV
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                mapper.toSource(x.toFloat(), y.toFloat(), point)
                val sx = point[0]
                val sy = point[1]
                val color = if (k1Ca == 0f) {
                    sample(image, sx, sy)
                } else {
                    // Lateral CA: red and blue are magnified differently from green about the centre.
                    val red = sampleChannel(image, mapper.radialX(sx, 1f - k1Ca), mapper.radialY(sy, 1f - k1Ca), RED_SHIFT)
                    val green = sampleChannel(image, sx, sy, GREEN_SHIFT)
                    val blue = sampleChannel(image, mapper.radialX(sx, 1f + k1Ca), mapper.radialY(sy, 1f + k1Ca), BLUE_SHIFT)
                    Argb.opaque(red, green, blue)
                }
                out[y * image.width + x] = if (vignetteEv == 0f) color else vignette(color, mapper.radiusSquared(sx, sy), vignetteEv)
            }
        }
        return PixelBuffer(image.width, image.height, out)
    }

    private fun vignette(color: Int, radiusSquared: Float, ev: Float): Int {
        val gain = 2f.pow(ev * radiusSquared.coerceIn(0f, 1f))
        return Argb.pack(
            Argb.alpha(color),
            Srgb.toSrgb8(Srgb.toLinear(Argb.red(color)) * gain),
            Srgb.toSrgb8(Srgb.toLinear(Argb.green(color)) * gain),
            Srgb.toSrgb8(Srgb.toLinear(Argb.blue(color)) * gain),
        )
    }

    /** Output → source mapping, also used by the auto-fit search. */
    class Mapper(width: Int, height: Int, private val lens: LensCorrection, private val perspective: Perspective) {
        private val centerX = (width - 1) / 2f
        private val centerY = (height - 1) / 2f
        private val halfWidth = max(1f, width / 2f)
        private val halfHeight = max(1f, height / 2f)
        private val halfDiagonal = sqrt(halfWidth * halfWidth + halfHeight * halfHeight)
        private val k1 = -lens.distortion * LensCorrection.MAX_DISTORTION_K1
        private val keystoneY = -perspective.vertical * Perspective.MAX_KEYSTONE
        private val keystoneX = -perspective.horizontal * Perspective.MAX_KEYSTONE
        private val aspectX = 1f + max(0f, perspective.aspect) * Perspective.MAX_ASPECT
        private val aspectY = 1f + max(0f, -perspective.aspect) * Perspective.MAX_ASPECT
        private val cosR: Float
        private val sinR: Float
        private val zoom: Float

        init {
            val radians = Math.toRadians(perspective.rotate.toDouble())
            cosR = cos(radians).toFloat()
            sinR = sin(radians).toFloat()
            zoom = fitZoom(width, height) * (1f + perspective.scale * Perspective.MAX_EXTRA_ZOOM)
        }

        fun toSource(x: Float, y: Float, out: FloatArray) = map(x, y, zoom, out)

        private fun map(x: Float, y: Float, zoom: Float, out: FloatArray) {
            // Normalised output coordinates, −1..1 on each axis.
            var nx = (x - centerX) / halfWidth
            var ny = (y - centerY) / halfHeight
            nx = nx / zoom - perspective.offsetX * Perspective.MAX_OFFSET * 2f
            ny = ny / zoom - perspective.offsetY * Perspective.MAX_OFFSET * 2f
            nx /= aspectX
            ny /= aspectY
            // Rotation in pixel space so it stays a true rotation on non-square images.
            val px = nx * halfWidth
            val py = ny * halfHeight
            nx = (cosR * px + sinR * py) / halfWidth
            ny = (-sinR * px + cosR * py) / halfHeight
            // Projective keystone.
            val w = 1f + keystoneY * ny + keystoneX * nx
            val safeW = if (w < MIN_W) MIN_W else w
            nx /= safeW
            ny /= safeW
            // Radial lens model in units of the half-diagonal.
            var sx = nx * halfWidth
            var sy = ny * halfHeight
            if (k1 != 0f) {
                val r2 = (sx * sx + sy * sy) / (halfDiagonal * halfDiagonal)
                val factor = 1f + k1 * r2
                sx *= factor
                sy *= factor
            }
            out[0] = sx + centerX
            out[1] = sy + centerY
        }

        fun radialX(sourceX: Float, scale: Float) = (sourceX - centerX) * scale + centerX
        fun radialY(sourceY: Float, scale: Float) = (sourceY - centerY) * scale + centerY

        fun radiusSquared(sourceX: Float, sourceY: Float): Float {
            val dx = sourceX - centerX
            val dy = sourceY - centerY
            return (dx * dx + dy * dy) / (halfDiagonal * halfDiagonal)
        }

        /**
         * Smallest zoom ≥ 1 at which every point on the output border maps inside the source, so the
         * corrected photo has no empty or smeared edges. Bisection over border samples.
         */
        private fun fitZoom(width: Int, height: Int): Float {
            if (fits(width, height, 1f)) return 1f
            var low = 1f
            var high = MAX_FIT_ZOOM
            if (!fits(width, height, high)) return high
            repeat(FIT_ITERATIONS) {
                val mid = (low + high) / 2f
                if (fits(width, height, mid)) high = mid else low = mid
            }
            return high
        }

        private fun fits(width: Int, height: Int, zoom: Float): Boolean {
            val point = FloatArray(2)
            val maxX = width - 1f + EDGE_TOLERANCE
            val maxY = height - 1f + EDGE_TOLERANCE
            for (i in 0..BORDER_SAMPLES) {
                val t = i.toFloat() / BORDER_SAMPLES
                val positions = arrayOf(
                    t * (width - 1) to 0f,
                    t * (width - 1) to (height - 1f),
                    0f to t * (height - 1),
                    (width - 1f) to t * (height - 1),
                )
                for ((x, y) in positions) {
                    map(x, y, zoom, point)
                    if (point[0] < -EDGE_TOLERANCE || point[1] < -EDGE_TOLERANCE || point[0] > maxX || point[1] > maxY) return false
                }
            }
            return true
        }
    }

    private fun sample(image: PixelBuffer, x: Float, y: Float): Int =
        Argb.opaque(sampleChannel(image, x, y, RED_SHIFT), sampleChannel(image, x, y, GREEN_SHIFT), sampleChannel(image, x, y, BLUE_SHIFT))

    /** Bilinear sample of one 8-bit channel; positions outside the image clamp to the edge. */
    private fun sampleChannel(image: PixelBuffer, x: Float, y: Float, shift: Int): Int {
        val maxX = image.width - 1
        val maxY = image.height - 1
        val cx = x.coerceIn(0f, maxX.toFloat())
        val cy = y.coerceIn(0f, maxY.toFloat())
        val x0 = floor(cx).toInt()
        val y0 = floor(cy).toInt()
        val x1 = min(x0 + 1, maxX)
        val y1 = min(y0 + 1, maxY)
        val fx = cx - x0
        val fy = cy - y0
        val a = (image.pixels[y0 * image.width + x0] shr shift) and 0xFF
        val b = (image.pixels[y0 * image.width + x1] shr shift) and 0xFF
        val c = (image.pixels[y1 * image.width + x0] shr shift) and 0xFF
        val d = (image.pixels[y1 * image.width + x1] shr shift) and 0xFF
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return (top + (bottom - top) * fy + 0.5f).toInt()
    }

    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BLUE_SHIFT = 0
    private const val MIN_W = 0.05f
    private const val MAX_FIT_ZOOM = 3f
    private const val FIT_ITERATIONS = 20
    private const val BORDER_SAMPLES = 32
    private const val EDGE_TOLERANCE = 0.5f
}
