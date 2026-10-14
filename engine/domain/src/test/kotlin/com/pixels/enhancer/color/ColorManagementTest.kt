package com.pixels.enhancer.color

import com.pixels.enhancer.domain.color.ColorConversion
import com.pixels.enhancer.domain.color.IccProfiles
import com.pixels.enhancer.domain.color.LinearImage
import com.pixels.enhancer.domain.color.Oklab
import com.pixels.enhancer.domain.color.RgbColorSpace
import com.pixels.enhancer.domain.color.TransferFunction
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ColorManagementTest {

    /** Every P3 colour at the edge of its gamut, plus a ramp of greys. */
    private val p3Gamut: PixelBuffer = run {
        val colors = mutableListOf<Int>()
        for (step in 0..255 step 5) {
            listOf(
                Argb.opaque(255, step, 0), Argb.opaque(step, 255, 0), Argb.opaque(0, 255, step),
                Argb.opaque(0, step, 255), Argb.opaque(step, 0, 255), Argb.opaque(255, 0, step),
                Argb.opaque(step, step, step),
            ).forEach(colors::add)
        }
        PixelBuffer(colors.size, 1, colors.toIntArray())
    }

    @Test
    fun `same space converts nothing and dithers nothing`() {
        assertSame(p3Gamut, ColorConversion.convert(p3Gamut, RgbColorSpace.DISPLAY_P3, RgbColorSpace.DISPLAY_P3))
        assertTrue(RgbColorSpace.SRGB.isSrgb)
        assertTrue(!RgbColorSpace.DISPLAY_P3.isSrgb)
    }

    @Test
    fun `P3 to sRGB keeps neutrals and never hard-clips a channel to zero`() {
        val srgb = ColorConversion.convert(p3Gamut, RgbColorSpace.DISPLAY_P3, RgbColorSpace.SRGB)
        var clipped = 0
        p3Gamut.pixels.forEachIndexed { i, source ->
            val out = srgb.pixels[i]
            val grey = Argb.red(source) == Argb.green(source) && Argb.green(source) == Argb.blue(source)
            if (grey) {
                // Same white point and curve: greys only move by the ±1 LSB dither.
                for (shift in intArrayOf(16, 8, 0)) assertTrue(abs((out shr shift and 0xFF) - (source shr shift and 0xFF)) <= 1)
            } else if (minOf(Argb.red(out), Argb.green(out), Argb.blue(out)) == 0) {
                clipped++
            }
        }
        // A hard clip puts a channel at 0 for nearly every saturated P3 colour; soft compression for almost none.
        assertTrue(clipped < p3Gamut.pixelCount / 20, "clipped $clipped of ${p3Gamut.pixelCount}")
    }

    @Test
    fun `gamut compression keeps lightness and hue`() {
        val srgb = ColorConversion.convert(p3Gamut, RgbColorSpace.DISPLAY_P3, RgbColorSpace.SRGB)
        val before = DoubleArray(3)
        val after = DoubleArray(3)
        val toSrgb = linearInSrgb(RgbColorSpace.DISPLAY_P3)
        p3Gamut.pixels.forEachIndexed { i, source ->
            val rgb = toSrgb(source)
            Oklab.fromLinearSrgb(rgb[0], rgb[1], rgb[2], before)
            val out = srgb.pixels[i]
            val s = RgbColorSpace.SRGB
            Oklab.fromLinearSrgb(s.decode(Argb.red(out)).toDouble(), s.decode(Argb.green(out)).toDouble(), s.decode(Argb.blue(out)).toDouble(), after)
            assertEquals(before[0], after[0], 0.04, "lightness of #${Integer.toHexString(source)}")
            if (hypot(before[1], before[2]) > 0.05) {
                val hueShift = Math.toDegrees(atan2(after[2], after[1]) - atan2(before[2], before[1])).let { ((it + 540) % 360) - 180 }
                assertTrue(abs(hueShift) < 3.0, "hue of #${Integer.toHexString(source)} moved $hueShift°")
            }
        }
    }

    @Test
    fun `float round trip through linear light is within one code value`() {
        val random = java.util.Random(3)
        val interior = PixelBuffer(4096, 1, IntArray(4096) { Argb.opaque(40 + random.nextInt(176), 40 + random.nextInt(176), 40 + random.nextInt(176)) })
        // Knee 1.0: no compression inside the gamut, so only the curves, matrices and dither are tested.
        val back = ColorConversion.encode(ColorConversion.toLinear(interior, RgbColorSpace.DISPLAY_P3), RgbColorSpace.DISPLAY_P3, knee = 1.0)
        var worst = 0
        interior.pixels.forEachIndexed { i, source ->
            for (shift in intArrayOf(16, 8, 0)) worst = maxOf(worst, abs((back.pixels[i] shr shift and 0xFF) - (source shr shift and 0xFF)))
        }
        assertTrue(worst <= 1, "worst code error $worst")
    }

    @Test
    fun `dither is repeatable for a given seed`() {
        val linear = LinearImage(4, 1, RgbColorSpace.SRGB, FloatArray(12) { 0.2f }, IntArray(4) { 255 })
        assertEquals(
            ColorConversion.encode(linear, RgbColorSpace.SRGB, seed = 7).pixels.toList(),
            ColorConversion.encode(linear, RgbColorSpace.SRGB, seed = 7).pixels.toList(),
        )
    }

    @Test
    fun `parses the JDK's sRGB profile as sRGB`() {
        val parsed = assertNotNull(IccProfiles.parse(ICC_Profile.getInstance(ColorSpace.CS_sRGB).data))
        assertTrue(parsed.isSrgb, "matrix ${parsed.toXyz.toList()}")
    }

    @Test
    fun `parses a Display P3 profile with a parametric curve`() {
        val parsed = assertNotNull(IccProfiles.parse(displayP3Profile()))
        assertEquals("Display P3 test", parsed.name)
        assertTrue(parsed.sameEncodingAs(RgbColorSpace.DISPLAY_P3))
        assertTrue(!parsed.isSrgb)
    }

    @Test
    fun `refuses what is not an RGB matrix profile`() {
        assertNull(IccProfiles.parse(ByteArray(200)))
        assertNull(IccProfiles.parse(ICC_Profile.getInstance(ColorSpace.CS_GRAY).data))
    }

    @Test
    fun `table curves invert`() {
        val table = TransferFunction.Table(DoubleArray(1024) { Math.pow(it / 1023.0, 2.2) })
        for (x in listOf(0.0, 0.1, 0.5, 0.9, 1.0)) assertEquals(x, table.fromLinear(table.toLinear(x)), 1e-3)
    }

    /** Unclipped linear sRGB of an 8-bit colour in [space] (the reference the compressed result is compared with). */
    private fun linearInSrgb(space: RgbColorSpace): (Int) -> DoubleArray = { color ->
        val linear = ColorConversion.toLinear(PixelBuffer(1, 1, intArrayOf(color)), space).rgb
        val xyz = DoubleArray(3) { row -> (0 until 3).sumOf { space.toXyz[row * 3 + it] * linear[it] } }
        DoubleArray(3) { row -> (0 until 3).sumOf { RgbColorSpace.SRGB.fromXyz[row * 3 + it] * xyz[it] } }
    }

    /** A minimal v4 matrix/TRC profile: header, tag table, desc, r/g/bXYZ and one shared `para` curve. */
    private fun displayP3Profile(): ByteArray {
        val m = RgbColorSpace.DISPLAY_P3.toXyz
        fun xyz(column: Int) = ByteBuffer.allocate(20).put("XYZ ".toByteArray()).putInt(0)
            .apply { for (row in 0 until 3) putInt((m[row * 3 + column] * 65536).let(Math::round).toInt()) }.array()
        val curve = ByteBuffer.allocate(40).put("para".toByteArray()).putInt(0).putShort(4).putShort(0)
            .apply { listOf(2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045, 0.0, 0.0).forEach { putInt(Math.round(it * 65536).toInt()) } }.array()
        val name = "Display P3 test".toByteArray(Charsets.UTF_16BE)
        val desc = ByteBuffer.allocate(28 + name.size).put("mluc".toByteArray()).putInt(0).putInt(1).putInt(12)
            .put("enUS".toByteArray()).putInt(name.size).putInt(28).put(name).array()
        val tags = listOf("desc" to desc, "rXYZ" to xyz(0), "gXYZ" to xyz(1), "bXYZ" to xyz(2), "rTRC" to curve)
        val tableSize = 4 + 12 * (tags.size + 2)
        val body = ByteArrayOutputStream()
        val offsets = tags.map { (_, data) -> (128 + tableSize + body.size()).also { body.write(data) } }
        val table = ByteBuffer.allocate(tableSize).putInt(tags.size + 2)
        tags.forEachIndexed { i, (sig, data) -> table.put(sig.toByteArray()).putInt(offsets[i]).putInt(data.size) }
        // g and b share the red curve, as real profiles do.
        listOf("gTRC", "bTRC").forEach { table.put(it.toByteArray()).putInt(offsets.last()).putInt(curve.size) }
        val header = ByteBuffer.allocate(128).putInt(128 + tableSize + body.size()).apply {
            position(16); put("RGB ".toByteArray()); put("XYZ ".toByteArray())
        }.array()
        return header + table.array() + body.toByteArray()
    }
}
