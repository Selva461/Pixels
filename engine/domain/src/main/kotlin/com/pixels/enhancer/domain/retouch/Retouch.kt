package com.pixels.enhancer.domain.retouch

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.smoothstep
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class RetouchMode {
    /** Copies texture from the source and blends its colour and brightness into the surroundings. */
    HEAL,

    /** Copies the source exactly. */
    CLONE,
}

/**
 * One spot removal. Positions are normalised (0..1) in the edited output, like local masks;
 * [radius] is a fraction of the image width. Only real pixels from elsewhere in the same photo are
 * used — nothing is generated.
 */
data class RetouchSpot(
    val id: Int,
    val targetX: Float,
    val targetY: Float,
    val sourceX: Float,
    val sourceY: Float,
    val radius: Float = DEFAULT_RADIUS,
    val feather: Float = DEFAULT_FEATHER,
    val opacity: Float = 1f,
    val mode: RetouchMode = RetouchMode.HEAL,
) {
    fun clamped() = copy(
        targetX = targetX.coerceIn(0f, 1f),
        targetY = targetY.coerceIn(0f, 1f),
        sourceX = sourceX.coerceIn(0f, 1f),
        sourceY = sourceY.coerceIn(0f, 1f),
        radius = radius.coerceIn(MIN_RADIUS, MAX_RADIUS),
        feather = feather.coerceIn(0f, 1f),
        opacity = opacity.coerceIn(0f, 1f),
    )

    companion object {
        const val DEFAULT_RADIUS = 0.03f
        const val DEFAULT_FEATHER = 0.4f
        const val MIN_RADIUS = 0.004f
        const val MAX_RADIUS = 0.2f
    }
}

data class Retouch(val spots: List<RetouchSpot> = emptyList()) {
    val isEmpty: Boolean get() = spots.isEmpty()

    fun with(spot: RetouchSpot) = Retouch(
        spots.map { if (it.id == spot.id) spot.clamped() else it }.let { if (it.any { s -> s.id == spot.id }) it else (it + spot.clamped()).takeLast(MAX_SPOTS) },
    )

    fun without(id: Int) = Retouch(spots.filterNot { it.id == id })

    fun nextId(): Int = (spots.maxOfOrNull { it.id } ?: 0) + 1

    companion object {
        val NONE = Retouch()
        const val MAX_SPOTS = 100
    }
}

/**
 * Renders [Retouch] spots in order (later spots may copy from earlier repairs, as in Lightroom).
 *
 * Heal: the copied patch keeps its texture, while a smooth colour offset makes its edge match the
 * target's surroundings — the offset is measured around the rim at many angles and blended toward
 * the rim's mean at the centre, so there is no visible seam even on gradients.
 */
object RetouchRenderer {

    fun apply(image: PixelBuffer, retouch: Retouch): PixelBuffer {
        if (retouch.isEmpty) return image
        val out = image.copy()
        retouch.spots.forEach { applySpot(out, it.clamped()) }
        return out
    }

    private fun applySpot(image: PixelBuffer, spot: RetouchSpot) {
        val width = image.width
        val height = image.height
        val radius = max(1f, spot.radius * width)
        val tx = spot.targetX * width
        val ty = spot.targetY * height
        val dx = (spot.sourceX - spot.targetX) * width
        val dy = (spot.sourceY - spot.targetY) * height
        val inner = radius * (1f - spot.feather)
        val snapshot = image.pixels.copyOf()
        val rim = if (spot.mode == RetouchMode.HEAL) RimOffsets.measure(snapshot, width, height, tx, ty, dx, dy, radius) else null

        val x0 = max(0, (tx - radius).toInt())
        val x1 = min(width - 1, (tx + radius).toInt() + 1)
        val y0 = max(0, (ty - radius).toInt())
        val y1 = min(height - 1, (ty + radius).toInt() + 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val ox = x + 0.5f - tx
                val oy = y + 0.5f - ty
                val distance = sqrt(ox * ox + oy * oy)
                if (distance > radius) continue
                val alpha = spot.opacity * (1f - smoothstep(inner, radius, distance))
                if (alpha <= 0f) continue
                val sx = (x + dx).roundToInt().coerceIn(0, width - 1)
                val sy = (y + dy).roundToInt().coerceIn(0, height - 1)
                val source = snapshot[sy * width + sx]
                var red = Argb.red(source).toFloat()
                var green = Argb.green(source).toFloat()
                var blue = Argb.blue(source).toFloat()
                if (rim != null) {
                    val offset = rim.at(atan2(oy, ox), distance / radius)
                    red += offset[0]
                    green += offset[1]
                    blue += offset[2]
                }
                val target = snapshot[y * width + x]
                image.pixels[y * width + x] = Argb.pack(
                    Argb.alpha(target),
                    mix(Argb.red(target), red, alpha),
                    mix(Argb.green(target), green, alpha),
                    mix(Argb.blue(target), blue, alpha),
                )
            }
        }
    }

    private fun mix(base: Int, value: Float, alpha: Float): Int = (base + (value - base) * alpha).roundToInt()

    /** Target-minus-source colour difference sampled around the rim, interpolated inside. */
    private class RimOffsets(private val samples: Array<FloatArray>, private val mean: FloatArray) {
        private val result = FloatArray(3)

        fun at(angle: Float, normalisedRadius: Float): FloatArray {
            val position = ((angle / (2 * Math.PI).toFloat()) * SAMPLES + SAMPLES) % SAMPLES
            val i0 = position.toInt() % SAMPLES
            val i1 = (i0 + 1) % SAMPLES
            val f = position - position.toInt()
            val t = normalisedRadius.coerceIn(0f, 1f)
            for (c in 0..2) {
                val rimValue = samples[i0][c] + (samples[i1][c] - samples[i0][c]) * f
                result[c] = mean[c] + (rimValue - mean[c]) * t
            }
            return result
        }

        companion object {
            const val SAMPLES = 32
            private const val RING_RADIUS = 1.05f
            private const val RING_SPREAD = 2

            fun measure(pixels: IntArray, width: Int, height: Int, tx: Float, ty: Float, dx: Float, dy: Float, radius: Float): RimOffsets {
                val samples = Array(SAMPLES) { FloatArray(3) }
                val mean = FloatArray(3)
                for (i in 0 until SAMPLES) {
                    val angle = i * 2 * Math.PI / SAMPLES
                    val px = tx + cos(angle).toFloat() * radius * RING_RADIUS
                    val py = ty + sin(angle).toFloat() * radius * RING_RADIUS
                    val target = average(pixels, width, height, px, py)
                    val source = average(pixels, width, height, px + dx, py + dy)
                    for (c in 0..2) {
                        samples[i][c] = target[c] - source[c]
                        mean[c] += samples[i][c] / SAMPLES
                    }
                }
                return RimOffsets(samples, mean)
            }

            /** Small box average so single noisy pixels don't steer the offset. */
            private fun average(pixels: IntArray, width: Int, height: Int, x: Float, y: Float): FloatArray {
                val sum = FloatArray(3)
                var count = 0
                val cx = x.roundToInt()
                val cy = y.roundToInt()
                for (yy in cy - RING_SPREAD..cy + RING_SPREAD) {
                    for (xx in cx - RING_SPREAD..cx + RING_SPREAD) {
                        val p = pixels[yy.coerceIn(0, height - 1) * width + xx.coerceIn(0, width - 1)]
                        sum[0] += Argb.red(p).toFloat()
                        sum[1] += Argb.green(p).toFloat()
                        sum[2] += Argb.blue(p).toFloat()
                        count++
                    }
                }
                for (c in 0..2) sum[c] /= count
                return sum
            }
        }
    }
}

/**
 * Picks a source for a new spot automatically: candidate patches on rings around the target are
 * compared on their rim with the target's rim (where the repair must blend) and on their own
 * texture variance (flat, clean patches are preferred), the way an automatic spot healer chooses.
 */
object RetouchSourceFinder {

    fun find(image: PixelBuffer, targetX: Float, targetY: Float, radius: Float): Pair<Float, Float> {
        val width = image.width
        val height = image.height
        val r = max(1f, radius * width)
        val tx = targetX * width
        val ty = targetY * height
        val targetRim = rim(image, tx, ty, r)
        var best = Pair(targetX, (targetY + radius * 3 * width / height).coerceIn(0f, 1f))
        var bestScore = Float.MAX_VALUE
        for (ring in RINGS) {
            for (i in 0 until DIRECTIONS) {
                val angle = i * 2 * Math.PI / DIRECTIONS
                val cx = tx + cos(angle).toFloat() * r * ring
                val cy = ty + sin(angle).toFloat() * r * ring
                if (cx - r < 0 || cy - r < 0 || cx + r >= width || cy + r >= height) continue
                val candidateRim = rim(image, cx, cy, r)
                var score = 0f
                for (k in targetRim.indices) {
                    val d = targetRim[k] - candidateRim[k]
                    score += d * d
                }
                score += VARIANCE_WEIGHT * variance(image, cx, cy, r) + DISTANCE_WEIGHT * ring
                if (score < bestScore) {
                    bestScore = score
                    best = Pair(cx / width, cy / height)
                }
            }
        }
        return best
    }

    private fun rim(image: PixelBuffer, cx: Float, cy: Float, r: Float): FloatArray {
        val values = FloatArray(RIM_SAMPLES * 3)
        for (i in 0 until RIM_SAMPLES) {
            val angle = i * 2 * Math.PI / RIM_SAMPLES
            val x = (cx + cos(angle).toFloat() * r * 1.1f).roundToInt().coerceIn(0, image.width - 1)
            val y = (cy + sin(angle).toFloat() * r * 1.1f).roundToInt().coerceIn(0, image.height - 1)
            val p = image.pixels[y * image.width + x]
            values[i * 3] = Argb.red(p).toFloat()
            values[i * 3 + 1] = Argb.green(p).toFloat()
            values[i * 3 + 2] = Argb.blue(p).toFloat()
        }
        return values
    }

    private fun variance(image: PixelBuffer, cx: Float, cy: Float, r: Float): Float {
        var sum = 0f
        var sumSq = 0f
        var count = 0
        val step = max(1, (r / 6).toInt())
        var y = (cy - r).toInt()
        while (y <= cy + r) {
            var x = (cx - r).toInt()
            while (x <= cx + r) {
                val p = image.pixels[y.coerceIn(0, image.height - 1) * image.width + x.coerceIn(0, image.width - 1)]
                val v = (Argb.red(p) + Argb.green(p) + Argb.blue(p)) / 3f
                sum += v
                sumSq += v * v
                count++
                x += step
            }
            y += step
        }
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    private val RINGS = floatArrayOf(2.2f, 3f, 4.5f)
    private const val DIRECTIONS = 12
    private const val RIM_SAMPLES = 24
    private const val VARIANCE_WEIGHT = 2f
    private const val DISTANCE_WEIGHT = 50f
}
