package com.pixels.enhancer.planning

import com.pixels.enhancer.domain.planning.Adjustment
import com.pixels.enhancer.domain.planning.Look
import com.pixels.enhancer.domain.planning.ManualAdjustmentMerger
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.planning.QualityPreset
import com.pixels.enhancer.goodAnalysis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ManualAdjustmentsTest {
    private val autoPlan = NaturalEnhancementPlanner().createPlan(goodAnalysis(exposure = 0.3f), 0.5f, QualityPreset.NATURAL)

    @Test
    fun `neutral manual adjustments leave the plan untouched`() {
        assertSame(autoPlan, ManualAdjustmentMerger.merge(autoPlan, ManualAdjustments.NONE))
    }

    @Test
    fun `manual offsets add to the automatic amount and both appear in the reason`() {
        val merged = ManualAdjustmentMerger.merge(autoPlan, ManualAdjustments.of(ManualControl.EXPOSURE to 0.25f))
        assertEquals(autoPlan.exposure.amount + 0.25f * ManualControl.EXPOSURE.scale, merged.exposure.amount, 1e-4f)
        assertTrue(merged.exposure.reason.contains("underexposed"))
        assertTrue(merged.exposure.reason.contains("manual exposure +0.25"))
    }

    @Test
    fun `manual-only controls enable their adjustment`() {
        val merged = ManualAdjustmentMerger.merge(autoPlan, ManualAdjustments.of(ManualControl.VIGNETTE to -0.5f, ManualControl.GRAIN to 0.4f))
        assertEquals(-0.5f, merged.vignette.amount)
        assertTrue(merged.grain.enabled)
        assertFalse(merged.temperature.enabled)
    }

    @Test
    fun `combined amounts stay inside the manual range`() {
        val maxed = ManualAdjustments(ManualControl.entries.associateWith { it.max })
        val merged = ManualAdjustmentMerger.merge(autoPlan, maxed)
        ManualControl.entries.forEach { control ->
            val amount = merged[control.kind].amount
            assertTrue(amount in control.combinedRange.min..control.combinedRange.max, "$control=$amount")
        }
    }

    @Test
    fun `slider values are clamped and zero removes the entry`() {
        val adjustments = ManualAdjustments.NONE.with(ManualControl.SHARPNESS, -1f).with(ManualControl.EXPOSURE, 3f)
        assertEquals(0f, adjustments[ManualControl.SHARPNESS])
        assertEquals(1f, adjustments[ManualControl.EXPOSURE])
        assertTrue(adjustments.with(ManualControl.EXPOSURE, 0f).isNeutral)
    }

    @Test
    fun `looks have unique ids and only None is neutral`() {
        assertEquals(Look.ALL.size, Look.ALL.map { it.id }.toSet().size)
        Look.ALL.forEach { assertEquals(it.id == "none", it.adjustments.isNeutral, it.id) }
        assertEquals(Look.NONE, Look.byId("missing"))
    }

    @Test
    fun `disabled auto adjustment gets a manual reason`() {
        val plan = autoPlan.copy(contrast = Adjustment.none("Contrast fine"))
        val merged = ManualAdjustmentMerger.merge(plan, ManualAdjustments.of(ManualControl.CONTRAST to -0.4f))
        assertTrue(merged.contrast.reason.startsWith("Manual contrast"))
        assertTrue(merged.contrast.amount < 0f)
    }
}
