package com.pixels.enhancer.testing

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Deterministic, photo-like test scene: sky with clouds, textured grass, a building with sharp
 * windows, neutral grey/white/black reference patches, a skin-tone disc and a saturated red
 * object. Golden tests degrade it in controlled ways and check that the pipeline corrects the
 * defect without damaging the rest.
 */
object SyntheticScenes {

    fun natural(width: Int = 640, height: Int = 480, seed: Int = 7): PixelBuffer {
        val random = Random(seed)
        val coarse = ValueNoise(seed, cellSize = width / 10f)
        val fine = ValueNoise(seed + 1, cellSize = 3f)
        val clouds = ValueNoise(seed + 2, cellSize = width / 6f)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val u = x / width.toFloat()
                val v = y / height.toFloat()
                var rgb = background(u, v, x, y, coarse, fine, clouds)
                rgb = objects(u, v, width, height, rgb, fine, x, y)
                val grain = random.nextGaussian() * SENSOR_GRAIN
                pixels[y * width + x] = Argb.opaque(
                    (rgb[0] + grain).toInt(),
                    (rgb[1] + grain).toInt(),
                    (rgb[2] + grain).toInt(),
                )
            }
        }
        return PixelBuffer(width, height, pixels)
    }

    private const val SENSOR_GRAIN = 1.0
    private const val HORIZON = 0.42f

    private fun background(u: Float, v: Float, x: Int, y: Int, coarse: ValueNoise, fine: ValueNoise, clouds: ValueNoise): FloatArray {
        if (v < HORIZON) {
            val t = v / HORIZON
            val sky = floatArrayOf(lerp(95f, 185f, t), lerp(145f, 210f, t), lerp(205f, 235f, t))
            val cloud = ((clouds.at(x, y) - 0.55f) * 3f).coerceIn(0f, 1f)
            return floatArrayOf(lerp(sky[0], 238f, cloud), lerp(sky[1], 240f, cloud), lerp(sky[2], 244f, cloud))
        }
        val texture = (coarse.at(x, y) - 0.5f) * 40f + (fine.at(x, y) - 0.5f) * 28f
        val depthShade = lerp(1.05f, 0.8f, (v - HORIZON) / (1f - HORIZON))
        val grass = floatArrayOf((80f + texture) * depthShade, (122f + texture) * depthShade, (58f + texture * 0.6f) * depthShade)
        if (u in 0.42f..0.58f && v > 0.62f) {
            val path = 125f + texture * 0.3f
            return floatArrayOf(path, path - 5f, path - 12f)
        }
        return grass
    }

    @Suppress("LongParameterList")
    private fun objects(u: Float, v: Float, width: Int, height: Int, base: FloatArray, fine: ValueNoise, x: Int, y: Int): FloatArray {
        // Building with a sharp-edged window grid.
        if (u in 0.06f..0.36f && v in 0.18f..0.78f) {
            val windowColumn = ((u - 0.06f) / 0.05f).toInt()
            val windowRow = ((v - 0.18f) / 0.08f).toInt()
            val inWindow = (u - 0.06f) % 0.05f in 0.012f..0.04f && (v - 0.18f) % 0.08f in 0.02f..0.06f
            if (inWindow && windowColumn in 0..5 && windowRow in 0..6) return floatArrayOf(58f, 64f, 78f)
            val wall = 172f + (fine.at(x, y) - 0.5f) * 10f
            return floatArrayOf(wall, wall - 9f, wall - 20f)
        }
        // Neutral reference patches: grey card, white card, black patch.
        if (v in 0.50f..0.60f) {
            if (u in 0.66f..0.72f) return floatArrayOf(118f, 118f, 118f)
            if (u in 0.73f..0.79f) return floatArrayOf(232f, 232f, 232f)
            if (u in 0.80f..0.86f) return floatArrayOf(22f, 22f, 22f)
        }
        // Skin-tone disc with soft shading, like a face in light.
        val distance = hypot((u - 0.75f) * width, (v - 0.78f) * height) / (0.08f * height)
        if (distance < 1f) {
            val shade = 1.05f - 0.25f * distance
            return floatArrayOf(222f * shade, 172f * shade, 140f * shade)
        }
        // Saturated red object.
        if (u in 0.88f..0.96f && v in 0.70f..0.90f) return floatArrayOf(188f, 38f, 34f)
        return base
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

/** Smooth 2-D value noise: random lattice values with smoothstep interpolation. */
class ValueNoise(seed: Int, private val cellSize: Float) {
    private val lattice = Random(seed).let { random -> FloatArray(LATTICE * LATTICE) { random.nextFloat() } }

    fun at(x: Int, y: Int): Float {
        val gx = x / cellSize
        val gy = y / cellSize
        val x0 = floor(gx).toInt()
        val y0 = floor(gy).toInt()
        val tx = smooth(gx - x0)
        val ty = smooth(gy - y0)
        val top = lerp(value(x0, y0), value(x0 + 1, y0), tx)
        val bottom = lerp(value(x0, y0 + 1), value(x0 + 1, y0 + 1), tx)
        return lerp(top, bottom, ty)
    }

    private fun value(x: Int, y: Int) = lattice[Math.floorMod(y, LATTICE) * LATTICE + Math.floorMod(x, LATTICE)]

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        const val LATTICE = 256
    }
}
