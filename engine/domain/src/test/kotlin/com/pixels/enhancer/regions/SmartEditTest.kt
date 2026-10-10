package com.pixels.enhancer.regions

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.domain.local.LocalAdjustmentRenderer
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.local.MaskShape
import com.pixels.enhancer.domain.project.Project
import com.pixels.enhancer.domain.project.ProjectCodec
import com.pixels.enhancer.domain.regions.RegionDetector
import com.pixels.enhancer.domain.regions.RegionKind
import com.pixels.enhancer.domain.regions.SmartEdit
import com.pixels.enhancer.testing.SyntheticScenes
import com.pixels.enhancer.testing.ValueNoise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartEditTest {
    /**
     * Blue sky over the top third; a soft, out-of-focus field below; a sharp, dark, detailed
     * figure in the middle — the usual "person in a landscape" photo.
     */
    private val portrait: PixelBuffer = run {
        val w = 480
        val h = 360
        val soft = ValueNoise(3, cellSize = 40f)
        PixelBuffer(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val u = x / w.toFloat()
            val v = y / h.toFloat()
            when {
                u in 0.42f..0.58f && v in 0.3f..0.95f -> {
                    // Fine, high-contrast texture: in focus.
                    val t = if ((x / 6 + y / 6) % 2 == 0) 30 else 85
                    Argb.opaque(t + 20, t + 5, t)
                }
                v < 0.33f -> Argb.opaque((90 + 60 * v).toInt(), (140 + 50 * v).toInt(), 225)
                else -> {
                    val n = (soft.at(x, y) * 30).toInt()
                    Argb.opaque(110 + n, 150 + n, 70 + n / 2)
                }
            }
        })
    }

    private fun mean(map: FloatArray, w: Int, h: Int, u0: Float, u1: Float, v0: Float, v1: Float): Float {
        var sum = 0f
        var n = 0
        for (y in (v0 * h).toInt() until (v1 * h).toInt()) for (x in (u0 * w).toInt() until (u1 * w).toInt()) {
            sum += map[y * w + x]
            n++
        }
        return sum / n
    }

    @Test
    fun `finds the sky at the top and not on the ground`() {
        val maps = RegionDetector.detect(SyntheticScenes.natural())
        val sky = maps.map(RegionKind.SKY)
        assertTrue(mean(sky, maps.width, maps.height, 0.4f, 0.95f, 0.02f, 0.3f) > 0.8f, "upper sky")
        assertTrue(mean(sky, maps.width, maps.height, 0f, 1f, 0.8f, 1f) < 0.05f, "ground")
    }

    @Test
    fun `finds a sharp subject against a soft background`() {
        val maps = RegionDetector.detect(portrait)
        val subject = maps.map(RegionKind.SUBJECT)
        assertTrue(maps.subjectConfidence > RegionDetector.MIN_SUBJECT_CONFIDENCE, "confidence ${maps.subjectConfidence}")
        assertTrue(mean(subject, maps.width, maps.height, 0.45f, 0.55f, 0.45f, 0.85f) > 0.7f, "subject")
        assertTrue(mean(subject, maps.width, maps.height, 0f, 0.25f, 0.5f, 1f) < 0.05f, "field")
        assertTrue(mean(maps.map(RegionKind.SKY), maps.width, maps.height, 0f, 0.3f, 0f, 0.25f) > 0.8f, "sky")
        val background = maps.map(RegionKind.BACKGROUND)
        assertTrue(mean(background, maps.width, maps.height, 0f, 0.25f, 0.5f, 1f) > 0.9f, "background")
    }

    @Test
    fun `an even photo has no subject and no sky`() {
        val maps = RegionDetector.detect(TestImages.solidColor(90, 120, 80, width = 300, height = 200))
        assertTrue(maps.subjectConfidence < RegionDetector.MIN_SUBJECT_CONFIDENCE)
        assertEquals(0f, maps.subjectFraction)
        assertEquals(0f, maps.skyFraction)
        assertTrue(SmartEdit.suggest(TestImages.solidColor(90, 120, 80, width = 300, height = 200)).isEmpty())
    }

    @Test
    fun `lifts a dark subject, recovers the sky and calms the background`() {
        val masks = SmartEdit.suggest(portrait).associateBy { it.name }
        val subject = masks.getValue(SmartEdit.SUBJECT_NAME)
        val sky = masks.getValue(SmartEdit.SKY_NAME)
        val background = masks.getValue(SmartEdit.BACKGROUND_NAME)
        assertEquals(MaskShape.Region(RegionKind.SUBJECT), subject.shape)
        assertTrue(subject.exposure > 0f && subject.clarity > 0f && subject.sharpness > 0f, "$subject")
        assertTrue(sky.highlights < 0f && sky.saturation > 0f && sky.temperature < 0f, "$sky")
        assertTrue(background.exposure < 0f && background.saturation < 0f, "$background")
    }

    @Test
    fun `region masks only change their region`() {
        val darkSky = LocalAdjustments(listOf(LocalAdjustment.region(1, RegionKind.SKY).copy(exposure = -0.5f)))
        val out = LocalAdjustmentRenderer.apply(portrait, darkSky)
        fun luma(image: PixelBuffer, x: Int, y: Int) = Luma.ofPixel(image.pixels[y * image.width + x])
        assertTrue(luma(out, 40, 30) < luma(portrait, 40, 30) - 0.1f, "sky darker")
        assertEquals(portrait.pixels[300 * 480 + 40], out.pixels[300 * 480 + 40], "field untouched")
    }

    @Test
    fun `masks are detected on the reference, not the edited picture`() {
        val edited = TestImages.solid(60, width = portrait.width, height = portrait.height)
        val item = LocalAdjustment.region(1, RegionKind.SKY).copy(exposure = 0.5f)
        val onEdited = LocalAdjustmentRenderer.maskOf(edited, item)
        val onReference = LocalAdjustmentRenderer.maskOf(edited, item, portrait)
        assertEquals(0f, onEdited.max())
        assertTrue(onReference[30 * portrait.width + 40] > 0.9f)
    }

    @Test
    fun `merge replaces earlier smart masks and keeps the user's own`() {
        val own = LocalAdjustment.radialAt(1, 0.5f, 0.5f, 1f).copy(exposure = 0.2f)
        val renamedRegion = LocalAdjustment.region(2, RegionKind.SKY).copy(exposure = 0.1f, name = "My sky")
        val oldSmart = LocalAdjustment.region(3, RegionKind.SUBJECT).copy(exposure = 0.3f, name = SmartEdit.SUBJECT_NAME)
        val fresh = listOf(LocalAdjustment.region(0, RegionKind.SUBJECT).copy(exposure = 0.1f, name = SmartEdit.SUBJECT_NAME))
        val merged = SmartEdit.merge(LocalAdjustments(listOf(own, renamedRegion, oldSmart)), fresh)
        assertEquals(listOf(1, 2, 3), merged.items.map { it.id })
        assertEquals(0.1f, merged.items.last().exposure)

        val full = LocalAdjustments((1..LocalAdjustments.MAX_ITEMS).map { LocalAdjustment.radialAt(it, 0.5f, 0.5f, 1f) })
        assertEquals(LocalAdjustments.MAX_ITEMS, SmartEdit.merge(full, fresh).items.size)
    }

    @Test
    fun `region masks survive saving and loading`() {
        val local = LocalAdjustments(RegionKind.entries.mapIndexed { i, kind -> LocalAdjustment.region(i + 1, kind).copy(clarity = 0.2f, name = kind.name) })
        val project = Project("p-r", "src", null, 1, 2, EditState(localAdjustments = local))
        assertEquals(local, ProjectCodec.decode(ProjectCodec.encode(project)).edit.localAdjustments)
    }
}
