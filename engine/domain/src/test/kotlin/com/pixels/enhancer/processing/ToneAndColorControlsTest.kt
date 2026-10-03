package com.pixels.enhancer.processing

import com.pixels.enhancer.TestImages
import com.pixels.enhancer.domain.analysis.Histogram
import com.pixels.enhancer.domain.analysis.LuminancePercentiles
import com.pixels.enhancer.domain.analysis.SaturationEstimator
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.planning.Adjustment
import com.pixels.enhancer.domain.planning.ColorMixer
import com.pixels.enhancer.domain.planning.HslShift
import com.pixels.enhancer.domain.planning.HueBand
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.processing.ops.ToneCurve
import com.pixels.enhancer.domain.processing.stages.ColorMixerStage
import com.pixels.enhancer.domain.processing.stages.DehazeStage
import com.pixels.enhancer.domain.project.Project
import com.pixels.enhancer.domain.project.ProjectCodec
import com.pixels.enhancer.goodAnalysis
import com.pixels.enhancer.testing.Degradations
import com.pixels.enhancer.testing.SyntheticScenes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToneControlsTest {
    @Test
    fun `whites and blacks act on their own ends and keep the curve anchored`() {
        val whites = ToneCurve.build(0f, 0f, 0f, whites = 0.1f)
        val blacks = ToneCurve.build(0f, 0f, 0f, blacks = -0.1f)
        assertTrue(whites.map(0.85f) > 0.9f && kotlin.math.abs(whites.map(0.2f) - 0.2f) < 0.005f)
        assertTrue(blacks.map(0.15f) < 0.1f && kotlin.math.abs(blacks.map(0.8f) - 0.8f) < 0.005f)
        assertEquals(0f, blacks.map(0f), 0.001f)
        assertEquals(1f, whites.map(1f), 0.001f)
    }

    @Test
    fun `midtones move the middle most`() {
        val curve = ToneCurve.build(0f, 0f, 0f, midtones = 0.1f)
        assertTrue(curve.map(0.5f) - 0.5f > curve.map(0.1f) - 0.1f)
        assertEquals(0.6f, curve.map(0.5f), 0.01f)
    }

    @Test
    fun `every manual control has a description and group`() {
        ManualControl.entries.forEach { assertTrue(it.description.isNotBlank(), it.name) }
    }
}

class DehazeTest {
    @Test
    fun `dehaze restores contrast and colour on a hazy scene`() = runTest {
        val hazy = Degradations.haze(SyntheticScenes.natural(), amount = 0.4f)
        val analysis = com.pixels.enhancer.domain.analysis.StatisticalImageAnalyzer().analyze(hazy)
        val context = contextFor(analysis = analysis, planOverride = { it.copy(dehaze = Adjustment.of(0.8f, "t")) })
        val result = DehazeStage().execute(hazy.copy(), context)
        val before = com.pixels.enhancer.domain.analysis.LuminanceStatistics.compute(hazy).percentiles
        val after = com.pixels.enhancer.domain.analysis.LuminanceStatistics.compute(result).percentiles
        assertTrue(after.p5 < before.p5 - 0.05f, "blacks ${before.p5} -> ${after.p5}")
        assertTrue(SaturationEstimator.compute(result).meanChroma > SaturationEstimator.compute(hazy).meanChroma)
    }

    @Test
    fun `negative dehaze adds a veil`() = runTest {
        val context = contextFor(
            analysis = goodAnalysis(percentiles = LuminancePercentiles(0.02f, 0.05f, 0.5f, 0.9f, 0.97f)),
            planOverride = { it.copy(dehaze = Adjustment.of(-0.8f, "t")) },
        )
        val result = DehazeStage().execute(TestImages.solid(10), context)
        assertTrue(Argb.red(result.pixels[0]) > 40)
    }
}

class ColorMixerTest {
    private suspend fun mix(red: Int, green: Int, blue: Int, mixer: ColorMixer): Int =
        ColorMixerStage().execute(TestImages.solidColor(red, green, blue), contextFor(planOverride = { it.copy(colorMixer = mixer) })).pixels[0]

    @Test
    fun `green saturation affects greens but not reds or greys`() = runTest {
        val mixer = ColorMixer.NONE.with(HueBand.GREEN, HslShift(saturation = -1f))
        assertTrue(SaturationEstimator.chromaOf(mix(60, 160, 60, mixer)) < 0.05f)
        assertEquals(Argb.opaque(200, 40, 40), mix(200, 40, 40, mixer))
        assertEquals(Argb.opaque(128, 128, 128), mix(128, 128, 128, mixer))
    }

    @Test
    fun `hue shift rotates blue toward purple and keeps brightness`() = runTest {
        val shifted = mix(40, 80, 200, ColorMixer.NONE.with(HueBand.BLUE, HslShift(hue = 1f)))
        assertTrue(Argb.red(shifted) > 60, "red ${Argb.red(shifted)}")
        assertEquals(Luma.of8Bit(40, 80, 200), Luma.ofPixel(shifted), 0.05f)
    }

    @Test
    fun `luminance lowers a colour band`() = runTest {
        val darker = mix(220, 140, 40, ColorMixer.NONE.with(HueBand.ORANGE, HslShift(luminance = -1f)))
        assertTrue(Luma.ofPixel(darker) < Luma.of8Bit(220, 140, 40) - 0.1f)
    }

    @Test
    fun `adjustments fade between neighbouring bands`() {
        val mixer = ColorMixer.NONE.with(HueBand.YELLOW, HslShift(saturation = -1f))
        // Between orange (30°) and yellow (60°) the effect is partial.
        runTest {
            val between = SaturationEstimator.chromaOf(mix(230, 170, 30, mixer))
            assertTrue(between > 0.1f && between < 0.75f, "partial effect expected, chroma $between")
        }
    }

    @Test
    fun `mixer survives a project round trip`() {
        val edit = EditState(colorMixer = ColorMixer.NONE.with(HueBand.AQUA, HslShift(0.2f, -0.5f, 0.1f)))
        val project = Project("p-1", "src", null, 1, 2, edit)
        assertEquals(edit, ProjectCodec.decode(ProjectCodec.encode(project)).edit)
    }

    @Test
    fun `histogram counts every sampled pixel once per channel`() {
        val histogram = Histogram.compute(TestImages.ramp(0, 255, 256, 10))
        assertEquals(histogram.luma.sum(), histogram.red.sum())
        assertTrue(histogram.luma.count { it > 0 } > 50)
    }
}
