package com.pixels.enhancer.regions

import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.regions.GuidedMatte
import kotlin.test.Test
import kotlin.test.assertTrue

class GuidedMatteTest {
    /** Beige subject on the left of x = [edge], green grass on the right. */
    private fun photo(w: Int, h: Int, edge: Float) = PixelBuffer(w, h, IntArray(w * h) { i ->
        if ((i % w + 0.5f) / w < edge) Argb.opaque(225, 200, 170) else Argb.opaque(120, 150, 60)
    })

    @Test
    fun `a coarse mask that spills past the subject is pulled back to its edge`() {
        val w = 96
        val h = 32
        val guide = photo(w, h, 0.5f)
        // Coarse mask: 3 px past the true edge (x = 48), as a mask detected at a smaller size is.
        val coarse = FloatArray(w * h) { i -> if (i % w < 51) 1f else 0f }
        val refined = GuidedMatte.snap(coarse, guide, 8, 3, 3, 1e-4f).evaluate(guide)
        val row = h / 2 * w
        val spill = (48 until w).sumOf { refined[row + it].toDouble() }
        assertTrue(spill < 0.5, "spill $spill px (coarse: 3 px)")
        assertTrue((0 until 48).all { refined[row + it] > 0.9f }, "the subject keeps its mask")
    }

    @Test
    fun `evaluated on a larger photo the edge stays on that photo's edge`() {
        val small = photo(64, 24, 0.5f)
        val large = photo(256, 96, 0.5f)
        val coarse = FloatArray(64 * 24) { i -> if (i % 64 < 32) 1f else 0f }
        val model = GuidedMatte.fit(coarse, small, 3, 1e-4f)
        val out = FloatArray(large.pixelCount)
        model.forEachPixel(large) { i, v -> out[i] = v }
        val row = 48 * 256
        // One full-size pixel either side of the edge (x = 128): no ramp stretched over the upscale.
        assertTrue(out[row + 126] > 0.9f, "inside ${out[row + 126]}")
        assertTrue(out[row + 129] < 0.1f, "outside ${out[row + 129]}")
    }
}
