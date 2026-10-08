package com.pixels.enhancer.processing

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.domain.geometry.AutoGeometry
import com.pixels.enhancer.domain.geometry.GeometryOps
import com.pixels.enhancer.domain.geometry.LensCorrection
import com.pixels.enhancer.domain.geometry.OpticsWarp
import com.pixels.enhancer.domain.geometry.Perspective
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.Srgb
import com.pixels.enhancer.domain.planning.ManualAdjustmentMerger
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.processing.ops.ChannelGains
import com.pixels.enhancer.domain.processing.ops.WhiteBalanceGains
import com.pixels.enhancer.domain.processing.stages.ColorMixerStage
import com.pixels.enhancer.domain.retouch.Retouch
import com.pixels.enhancer.domain.retouch.RetouchMode
import com.pixels.enhancer.domain.retouch.RetouchRenderer
import com.pixels.enhancer.domain.retouch.RetouchSpot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AutoAndRetouchExtrasTest {

    /** Light background with a grid of dark vertical and horizontal lines, like windows on a facade. */
    private fun facade(w: Int = 320, h: Int = 240): PixelBuffer {
        val pixels = IntArray(w * h) { Argb.opaque(220, 215, 205) }
        for (y in 0 until h) for (x in 0 until w) {
            if (x % 40 in 18..21 || y % 40 in 18..21) pixels[y * w + x] = Argb.opaque(30, 30, 35)
        }
        return PixelBuffer(w, h, pixels)
    }

    @Test
    fun `auto level undoes a tilt`() {
        val tilted = GeometryOps.straighten(facade(), 4f)
        val correction = AutoGeometry.levelDegrees(tilted)
        assertEquals(-4f, correction, 0.6f)
        assertEquals(0f, AutoGeometry.levelDegrees(facade()), 0.3f)
    }

    @Test
    fun `auto level leaves photos without straight lines alone`() {
        assertEquals(0f, AutoGeometry.levelDegrees(TestImages.solid(120, 200, 150)))
    }

    @Test
    fun `auto upright finds a keystone correction that straightens converging verticals`() {
        // Make keystone distortion by applying the opposite of a vertical correction.
        val keystoned = OpticsWarp.apply(facade(), LensCorrection.NONE, Perspective(vertical = -0.5f))
        val found = AutoGeometry.upright(keystoned)
        assertTrue(found.vertical > 0.2f, "expected a positive vertical correction, got $found")
        assertEquals(Perspective.NONE.vertical, AutoGeometry.upright(facade()).vertical, 0.1f)
    }

    @Test
    fun `white balance picker neutralises the sampled colour`() {
        val (temperature, tint) = WhiteBalanceGains.neutralizingShift(180, 160, 120)!!
        val gains = WhiteBalanceGains.withCreativeShift(ChannelGains.IDENTITY, temperature, tint)
        val r = Srgb.toLinear(180) * gains.red
        val g = Srgb.toLinear(160) * gains.green
        val b = Srgb.toLinear(120) * gains.blue
        assertEquals(r, g, r * 0.02f)
        assertEquals(g, b, g * 0.02f)
        assertTrue(temperature < 0f, "a warm sample needs cooling")
        assertNull(WhiteBalanceGains.neutralizingShift(255, 250, 240))
        assertNull(WhiteBalanceGains.neutralizingShift(3, 3, 3))
    }

    @Test
    fun `red eye turns red pupils dark and leaves skin alone`() {
        val w = 60
        val pixels = IntArray(w * w) { Argb.opaque(200, 150, 120) }
        for (y in 25..35) for (x in 25..35) pixels[y * w + x] = Argb.opaque(200, 40, 40)
        val image = PixelBuffer(w, w, pixels)
        val spot = RetouchSpot(1, 0.5f, 0.5f, 0.5f, 0.5f, radius = 0.15f, feather = 0.2f, mode = RetouchMode.RED_EYE)
        val out = RetouchRenderer.apply(image, Retouch(listOf(spot)))
        val pupil = out.pixels[30 * w + 30]
        assertTrue(Argb.red(pupil) < 60, "pupil red ${Argb.red(pupil)}")
        assertEquals(image.pixels[24 * w + 30], out.pixels[24 * w + 30], "skin inside the circle stays")
        assertEquals(image.pixels[2], out.pixels[2])
    }

    @Test
    fun `global hue rotates every colour`() {
        val plan = { manual: ManualAdjustments -> { p: com.pixels.enhancer.domain.planning.EnhancementPlan -> ManualAdjustmentMerger.merge(p, manual) } }
        val context = contextFor(planOverride = plan(ManualAdjustments.of(ManualControl.HUE to 1f)))
        val stage = ColorMixerStage()
        assertTrue(stage.isEnabled(context))
        val red = runBlocking { stage.execute(TestImages.solidColor(220, 40, 40), context) }.pixels[0]
        assertTrue(Argb.green(red) > 55 && Argb.green(red) > Argb.blue(red) + 20, "red should move toward orange: ${Integer.toHexString(red)}")
        val grey = runBlocking { stage.execute(TestImages.solid(128), context) }.pixels[0]
        assertEquals(TestImages.solid(128).pixels[0], grey)
    }
}
