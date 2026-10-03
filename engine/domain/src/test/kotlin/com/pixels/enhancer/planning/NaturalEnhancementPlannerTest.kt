package com.pixels.enhancer.planning

import com.pixels.enhancer.domain.analysis.ChannelBalance
import com.pixels.enhancer.domain.analysis.LuminancePercentiles
import com.pixels.enhancer.domain.planning.AdjustmentKind
import com.pixels.enhancer.domain.planning.EnhancementStrength
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.planning.QualityPreset
import com.pixels.enhancer.goodAnalysis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec section 28: Cases A–F expressed against the decision layer. */
class NaturalEnhancementPlannerTest {
    private val planner = NaturalEnhancementPlanner()
    private val preset = QualityPreset.NATURAL

    private fun plan(analysis: com.pixels.enhancer.domain.analysis.ImageAnalysis) =
        planner.createPlan(analysis, EnhancementStrength.NOMINAL, preset)

    @Test
    fun `case A - already good photo gets no correction`() {
        val plan = plan(goodAnalysis())
        assertFalse(plan.hasAnyCorrection, plan.entries().joinToString { "${it.first}=${it.second}" })
    }

    @Test
    fun `every adjustment carries a reason`() {
        plan(goodAnalysis()).entries().forEach { (kind, adjustment) -> assertTrue(adjustment.reason.isNotBlank(), "$kind") }
        plan(goodAnalysis(exposure = 0.3f, noiseScore = 0.6f)).entries().forEach { (kind, adjustment) ->
            assertTrue(adjustment.reason.isNotBlank(), "$kind")
        }
    }

    @Test
    fun `case B - slightly dark photo gets small lift, shadow recovery, no saturation change`() {
        val plan = plan(
            goodAnalysis(exposure = 0.33f, percentiles = LuminancePercentiles(0.01f, 0.03f, 0.30f, 0.70f, 0.85f)),
        )
        assertTrue(plan.exposure.enabled && plan.exposure.amount in 0.05f..0.6f, "${plan.exposure}")
        assertTrue(plan.shadows.enabled && plan.shadows.amount > 0f, "${plan.shadows}")
        assertFalse(plan.saturation.enabled)
    }

    @Test
    fun `existing highlight clipping limits the exposure lift`() {
        val clean = plan(goodAnalysis(exposure = 0.30f))
        val clipped = plan(goodAnalysis(exposure = 0.30f, highlightClipping = 0.05f))
        assertTrue(clipped.exposure.amount < clean.exposure.amount * 0.6f)
    }

    @Test
    fun `overexposed photo is darkened and highlights recovered`() {
        val plan = plan(
            goodAnalysis(exposure = 0.70f, highlightClipping = 0.08f, percentiles = LuminancePercentiles(0.2f, 0.3f, 0.72f, 0.99f, 1f)),
        )
        assertTrue(plan.exposure.amount < 0f)
        assertTrue(plan.highlights.enabled && plan.highlights.amount < 0f)
    }

    @Test
    fun `case C - yellow indoor photo gets white balance and no saturation boost`() {
        val plan = plan(goodAnalysis(castScore = 0.85f, balance = ChannelBalance(1.25f, 1.1f, 0.65f, 0.5f), saturation = 0.08f))
        assertTrue(plan.whiteBalance.enabled)
        assertFalse(plan.saturation.enabled)
        assertTrue(plan.saturation.reason.contains("cast"), plan.saturation.reason)
    }

    @Test
    fun `cast with few neutral references is corrected less`() {
        val confident = plan(goodAnalysis(castScore = 0.85f, balance = ChannelBalance(1.2f, 1f, 0.8f, 0.5f)))
        val unsure = plan(goodAnalysis(castScore = 0.85f, balance = ChannelBalance(1.2f, 1f, 0.8f, 0.01f)))
        assertTrue(unsure.whiteBalance.amount < confident.whiteBalance.amount)
    }

    @Test
    fun `case D - noisy low light photo gets denoise and conservative sharpening`() {
        val noisy = plan(goodAnalysis(exposure = 0.32f, noiseScore = 0.6f, sharpness = 0.5f))
        val clean = plan(goodAnalysis(sharpness = 0.5f))
        assertTrue(noisy.noiseReduction.enabled)
        assertTrue(noisy.sharpening.amount < clean.sharpening.amount, "noisy ${noisy.sharpening} clean ${clean.sharpening}")
    }

    @Test
    fun `case E - very sharp photo gets no extra sharpening`() {
        assertFalse(plan(goodAnalysis(sharpness = 0.9f)).sharpening.enabled)
    }

    @Test
    fun `case F - oversaturated photo is never boosted`() {
        val plan = plan(goodAnalysis(saturation = 0.45f))
        assertTrue(plan.saturation.amount < 0f)
    }

    @Test
    fun `soft photo is sharpened within the natural cap`() {
        val plan = plan(goodAnalysis(sharpness = 0.3f))
        assertTrue(plan.sharpening.enabled)
        assertTrue(plan.sharpening.amount <= preset.limits.maxSharpening)
    }

    @Test
    fun `flat photo gets a contrast boost but already contrasty does not`() {
        assertTrue(plan(goodAnalysis(contrast = 0.35f)).contrast.amount > 0f)
        assertFalse(plan(goodAnalysis(contrast = 0.85f)).contrast.enabled)
    }

    @Test
    fun `plan amounts stay inside hard ranges at any strength`() {
        val worst = goodAnalysis(
            exposure = 0.05f, contrast = 0.1f, saturation = 0.9f, sharpness = 0f, noiseScore = 1f,
            highlightClipping = 0.3f, shadowClipping = 0.3f, castScore = 1f, balance = ChannelBalance(2f, 1f, 0.3f, 0.5f),
            percentiles = LuminancePercentiles(0f, 0f, 0.05f, 1f, 1f),
        )
        listOf(0f, 0.25f, 0.5f, 1f).forEach { strength ->
            planner.createPlan(worst, strength, preset).entries().forEach { (kind, adjustment) ->
                val range = preset.limits.hardRange(kind)
                assertTrue(adjustment.amount in range.min..range.max, "$kind=${adjustment.amount} at $strength")
            }
        }
    }

    @Test
    fun `plan records strength preset and algorithm version`() {
        val plan = planner.createPlan(goodAnalysis(), 0.4f, preset)
        assertEquals(0.4f, plan.strength)
        assertEquals(preset.id, plan.presetId)
        assertEquals("1.1", plan.algorithmVersion)
        assertEquals(AdjustmentKind.entries.size, plan.entries().size)
    }
}
