package com.pixels.enhancer.validation

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.validation.NaturalOutputValidator
import com.pixels.enhancer.testing.Degradations
import com.pixels.enhancer.testing.SyntheticScenes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NaturalOutputValidatorTest {
    private val validator = NaturalOutputValidator()
    private val scene = SyntheticScenes.natural(160, 120)

    @Test
    fun `identical output passes`() {
        assertTrue(validator.validate(scene, scene.copy()).passed)
    }

    @Test
    fun `wrong dimensions fail`() {
        val result = validator.validate(scene, TestImages.solid(100, 10, 10))
        assertFalse(result.passed)
        assertEquals("Dimensions", result.failures.single().name)
    }

    @Test
    fun `black output fails`() {
        val result = validator.validate(scene, PixelBuffer.filled(scene.width, scene.height, Argb.opaque(0, 0, 0)))
        assertTrue(result.failures.any { it.name == "Not blank" })
    }

    @Test
    fun `alpha corruption fails`() {
        val broken = scene.copy().apply { pixels[0] = pixels[0] and 0x00FFFFFF }
        assertTrue(validator.validate(scene, broken).failures.any { it.name == "Alpha preserved" })
    }

    @Test
    fun `colour explosion fails`() {
        val exploded = Degradations.saturation(scene, factor = 3f)
        assertTrue(validator.validate(scene, exploded).failures.any { it.name.contains("chroma") })
    }

    @Test
    fun `newly blown highlights fail`() {
        val blown = Degradations.exposure(scene, linearGain = 3f)
        assertTrue(validator.validate(scene, blown).failures.any { it.name == "Highlight clipping" })
    }
}
