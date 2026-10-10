package com.pixels.enhancer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pixels.enhancer.data.storage.AndroidWatermarkDecorator
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.export.Watermark
import com.pixels.enhancer.domain.export.WatermarkPosition
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The platform text renderer behind export watermarks. */
@RunWith(AndroidJUnit4::class)
class WatermarkDecoratorTest {
    private val decorator = AndroidWatermarkDecorator()
    private val background = Argb.opaque(40, 60, 90)

    private fun photo() = PixelBuffer(400, 300, IntArray(400 * 300) { background })

    @Test
    fun drawsOnACopyAndNeverChangesTheInput() {
        val input = photo()
        val before = input.pixels.copyOf()

        val output = decorator.decorate(input, ExportOptions(watermark = Watermark("© Pixels test", WatermarkPosition.BOTTOM_RIGHT, size = 0.08f, opacity = 1f)))

        assertArrayEquals("the input can be the open session's image", before, input.pixels)
        assertNotSame(input, output)
        assertEquals(400 to 300, output.width to output.height)
        assertTrue("output stays opaque", output.pixels.all { Argb.alpha(it) == Argb.OPAQUE_ALPHA })
    }

    @Test
    fun textLandsInTheChosenCorner() {
        for (position in WatermarkPosition.entries) {
            val output = decorator.decorate(photo(), ExportOptions(watermark = Watermark("Pixels", position, size = 0.08f, opacity = 1f)))
            val changed = output.pixels.indices.filter { output.pixels[it] != background }
            assertTrue("$position drew nothing", changed.isNotEmpty())
            val centreX = changed.map { it % 400 }.average()
            val centreY = changed.map { it / 400 }.average()
            when (position) {
                WatermarkPosition.TOP_LEFT -> assertTrue("$position", centreX < 200 && centreY < 150)
                WatermarkPosition.TOP_RIGHT -> assertTrue("$position", centreX > 200 && centreY < 150)
                WatermarkPosition.BOTTOM_LEFT -> assertTrue("$position", centreX < 200 && centreY > 150)
                WatermarkPosition.BOTTOM_RIGHT -> assertTrue("$position", centreX > 200 && centreY > 150)
                WatermarkPosition.CENTER -> assertTrue("$position", centreX in 150.0..250.0 && centreY in 100.0..200.0)
            }
        }
    }

    @Test
    fun longTextShrinksToFitAndBlankTextDrawsNothing() {
        val long = decorator.decorate(photo(), ExportOptions(watermark = Watermark("W".repeat(Watermark.MAX_LENGTH), size = Watermark.MAX_SIZE, opacity = 1f)))
        val columns = long.pixels.indices.filter { long.pixels[it] != background }.map { it % 400 }
        assertTrue("text stays inside the photo", columns.min() > 0 && columns.max() < 399)

        val input = photo()
        assertSame(input, decorator.decorate(input, ExportOptions(watermark = Watermark("   "))))
    }
}
