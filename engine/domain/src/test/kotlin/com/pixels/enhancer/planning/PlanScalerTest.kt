package com.pixels.enhancer.planning

import com.pixels.enhancer.domain.planning.EnhancementStrength
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.planning.PlanScaler
import com.pixels.enhancer.domain.planning.QualityPreset
import com.pixels.enhancer.goodAnalysis
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanScalerTest {
    private val preset = QualityPreset.NATURAL
    private val scaler = PlanScaler(preset.limits)
    private val basePlan = NaturalEnhancementPlanner().createBasePlan(
        goodAnalysis(exposure = 0.3f, contrast = 0.4f, sharpness = 0.4f, noiseScore = 0.4f),
        preset,
    )

    @Test
    fun `strength zero disables every adjustment and says why`() {
        val plan = scaler.scale(basePlan, 0f)
        assertFalse(plan.hasAnyCorrection)
        assertTrue(plan.exposure.reason.contains("strength 0"))
    }

    @Test
    fun `nominal strength leaves the base plan unchanged`() {
        val plan = scaler.scale(basePlan, EnhancementStrength.NOMINAL)
        basePlan.entries().zip(plan.entries()).forEach { (base, scaled) -> assertEquals(base.second.amount, scaled.second.amount) }
    }

    @Test
    fun `amounts grow monotonically with strength and stay clamped`() {
        val strengths = listOf(0.1f, 0.3f, 0.5f, 0.7f, 1f)
        val plans = strengths.map { scaler.scale(basePlan, it) }
        plans.zipWithNext().forEach { (lower, higher) ->
            lower.entries().zip(higher.entries()).forEach { (a, b) ->
                assertTrue(abs(b.second.amount) >= abs(a.second.amount), "${a.first}: ${a.second.amount} -> ${b.second.amount}")
                val range = preset.limits.hardRange(b.first)
                assertTrue(b.second.amount in range.min..range.max)
            }
        }
    }

    @Test
    fun `disabled adjustments stay disabled at full strength`() {
        val plan = scaler.scale(basePlan, 1f)
        basePlan.entries().zip(plan.entries()).forEach { (base, scaled) ->
            if (!base.second.enabled) assertFalse(scaled.second.enabled, "${base.first}")
        }
    }

    @Test
    fun `out of range strength is clamped`() {
        assertEquals(1f, scaler.scale(basePlan, 3f).strength)
        assertEquals(0f, scaler.scale(basePlan, -1f).strength)
    }
}
