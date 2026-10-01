package com.pixels.enhancer.domain.planning

/**
 * Applies the user's strength to a base plan. The base plan corresponds to
 * [EnhancementStrength.NOMINAL]; 0 disables everything and 1 doubles the nominal amounts. The
 * result is clamped to the preset's hard ranges, so the slider can never push a correction
 * outside natural limits.
 */
class PlanScaler(private val limits: NaturalLimits) {

    fun scale(plan: EnhancementPlan, strength: Float): EnhancementPlan {
        val clampedStrength = strength.coerceIn(EnhancementStrength.MIN, EnhancementStrength.MAX)
        val factor = clampedStrength / EnhancementStrength.NOMINAL
        return plan.mapAdjustments { kind, adjustment ->
            when {
                !adjustment.enabled -> adjustment
                clampedStrength == 0f -> Adjustment.none("${adjustment.reason} (disabled: strength 0)")
                else -> adjustment.copy(amount = limits.hardRange(kind).clamp(adjustment.amount * factor))
            }
        }.copy(strength = clampedStrength)
    }
}
