package com.pixels.enhancer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pixels.enhancer.core.logging.EnhancerLogger
import com.pixels.enhancer.data.decoder.TfLitePeopleSegmenter
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.regions.SubjectHint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeopleSegmenterTest {
    private val errors = mutableListOf<String>()
    private val logger = object : EnhancerLogger {
        override fun event(name: String, fields: Map<String, Any?>) = Unit
        override fun error(name: String, fields: Map<String, Any?>, throwable: Throwable?) {
            errors += "$name ${throwable?.message}"
        }
    }

    /** Sky over grass: no people. */
    private val landscape = PixelBuffer(640, 480, IntArray(640 * 480) { i -> if (i / 640 < 200) Argb.opaque(110, 160, 230) else Argb.opaque(80, 130, 60) })

    @Test
    fun bundledModelLoadsRunsOfflineAndFindsNoPersonInALandscape() = runBlocking {
        val segmenter = TfLitePeopleSegmenter(InstrumentationRegistry.getInstrumentation().targetContext.assets, logger)
        val started = System.nanoTime()
        val hint = segmenter.segment(landscape)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("model failed to load: $errors", errors.isEmpty())
        assertNotNull(hint)
        assertEquals(SubjectHint.PASCAL_INPUT_SIZE, hint!!.width)
        assertFalse("a landscape has no person", hint.hasSubject)
        // Second run reuses the loaded interpreter.
        assertNotNull(segmenter.segment(landscape))
        assertTrue("segmentation took $millis ms", millis < 20_000)
    }
}
