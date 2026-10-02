package com.pixels.enhancer.domain.planning

import java.util.Locale

/**
 * A user-facing slider. Slider values run over [min]..[max] (0 = no change); [scale] converts a
 * slider value of ±1 into a plan amount. The automatic correction and the manual offset are
 * added, then clamped to [combinedRange] — wider than the natural auto ranges because the user
 * explicitly asked for the look, but still bounded so nothing breaks.
 */
enum class ManualControl(
    val label: String,
    val kind: AdjustmentKind,
    val min: Float,
    val max: Float,
    val scale: Float,
    val combinedRange: AmountRange,
) {
    EXPOSURE("Exposure", AdjustmentKind.EXPOSURE, -1f, 1f, 1.2f, AmountRange(-1.5f, 1.5f)),
    CONTRAST("Contrast", AdjustmentKind.CONTRAST, -1f, 1f, 0.15f, AmountRange(-0.2f, 0.25f)),
    HIGHLIGHTS("Highlights", AdjustmentKind.HIGHLIGHTS, -1f, 1f, 0.2f, AmountRange(-0.3f, 0.2f)),
    SHADOWS("Shadows", AdjustmentKind.SHADOWS, -1f, 1f, 0.2f, AmountRange(-0.2f, 0.3f)),
    TEMPERATURE("Temperature", AdjustmentKind.TEMPERATURE, -1f, 1f, 1f, AmountRange(-1f, 1f)),
    TINT("Tint", AdjustmentKind.TINT, -1f, 1f, 1f, AmountRange(-1f, 1f)),
    VIBRANCE("Vibrance", AdjustmentKind.SATURATION, -1f, 1f, 0.35f, AmountRange(-0.4f, 0.4f)),
    SATURATION("Saturation", AdjustmentKind.GLOBAL_SATURATION, -1f, 1f, 1f, AmountRange(-1f, 1f)),
    CLARITY("Clarity", AdjustmentKind.DETAIL, -1f, 1f, 0.25f, AmountRange(-0.25f, 0.35f)),
    SHARPNESS("Sharpness", AdjustmentKind.SHARPENING, 0f, 1f, 0.6f, AmountRange(0f, 0.8f)),
    NOISE_REDUCTION("Noise reduction", AdjustmentKind.NOISE_REDUCTION, 0f, 1f, 1f, AmountRange(0f, 1f)),
    VIGNETTE("Vignette", AdjustmentKind.VIGNETTE, -1f, 1f, 1f, AmountRange(-1f, 1f)),
    GRAIN("Grain", AdjustmentKind.GRAIN, 0f, 1f, 1f, AmountRange(0f, 1f)),
}

/** Slider positions for every [ManualControl]; missing entries are 0 (no change). */
data class ManualAdjustments(val values: Map<ManualControl, Float> = emptyMap()) {

    operator fun get(control: ManualControl): Float = values[control] ?: 0f

    fun with(control: ManualControl, value: Float): ManualAdjustments {
        val clamped = value.coerceIn(control.min, control.max)
        return copy(values = if (clamped == 0f) values - control else values + (control to clamped))
    }

    val isNeutral: Boolean get() = values.values.all { it == 0f }

    companion object {
        val NONE = ManualAdjustments()

        fun of(vararg settings: Pair<ManualControl, Float>): ManualAdjustments =
            settings.fold(NONE) { adjustments, (control, value) -> adjustments.with(control, value) }
    }
}

/** Adds the user's slider offsets on top of the automatic plan, recording both in the reason. */
object ManualAdjustmentMerger {

    fun merge(plan: EnhancementPlan, manual: ManualAdjustments): EnhancementPlan {
        if (manual.isNeutral) return plan
        return plan.mapAdjustments { kind, adjustment ->
            val controls = ManualControl.entries.filter { it.kind == kind && manual[it] != 0f }
            if (controls.isEmpty()) return@mapAdjustments adjustment
            val auto = if (adjustment.enabled) adjustment.amount else 0f
            var amount = auto
            controls.forEach { control -> amount = control.combinedRange.clamp(amount + manual[control] * control.scale) }
            val manualNote = controls.joinToString { "manual ${it.label.lowercase()} ${signed(manual[it])}" }
            val reason = if (adjustment.enabled) "${adjustment.reason}; $manualNote" else manualNote.replaceFirstChar { it.uppercase() }
            Adjustment.of(amount, reason)
        }
    }

    private fun signed(value: Float) = String.format(Locale.ROOT, "%+.2f", value)
}

/** A named bundle of slider positions — the app's "filters". The automatic correction still runs underneath. */
data class Look(val id: String, val name: String, val adjustments: ManualAdjustments) {
    companion object {
        val NONE = Look("none", "None", ManualAdjustments.NONE)

        val ALL: List<Look> = listOf(
            NONE,
            Look(
                "vivid", "Vivid",
                ManualAdjustments.of(ManualControl.VIBRANCE to 0.6f, ManualControl.CONTRAST to 0.3f, ManualControl.CLARITY to 0.3f),
            ),
            Look("warm", "Warm", ManualAdjustments.of(ManualControl.TEMPERATURE to 0.45f, ManualControl.VIBRANCE to 0.2f)),
            Look("cool", "Cool", ManualAdjustments.of(ManualControl.TEMPERATURE to -0.45f, ManualControl.TINT to 0.1f)),
            Look(
                "soft", "Soft",
                ManualAdjustments.of(
                    ManualControl.CONTRAST to -0.3f, ManualControl.CLARITY to -0.4f,
                    ManualControl.HIGHLIGHTS to -0.3f, ManualControl.SHADOWS to 0.3f,
                ),
            ),
            Look(
                "matte", "Matte",
                ManualAdjustments.of(
                    ManualControl.CONTRAST to -0.4f, ManualControl.SHADOWS to 0.5f,
                    ManualControl.SATURATION to -0.2f, ManualControl.GRAIN to 0.2f,
                ),
            ),
            Look(
                "mono", "Mono",
                ManualAdjustments.of(ManualControl.SATURATION to -1f, ManualControl.CONTRAST to 0.3f, ManualControl.CLARITY to 0.2f),
            ),
            Look(
                "film", "Film",
                ManualAdjustments.of(
                    ManualControl.TEMPERATURE to 0.2f, ManualControl.SATURATION to -0.15f, ManualControl.CONTRAST to 0.2f,
                    ManualControl.GRAIN to 0.35f, ManualControl.VIGNETTE to -0.35f,
                ),
            ),
            Look(
                "drama", "Drama",
                ManualAdjustments.of(
                    ManualControl.CONTRAST to 0.5f, ManualControl.CLARITY to 0.6f, ManualControl.VIGNETTE to -0.5f,
                    ManualControl.SATURATION to -0.1f, ManualControl.HIGHLIGHTS to -0.5f, ManualControl.SHADOWS to 0.3f,
                ),
            ),
        )

        fun byId(id: String?): Look = ALL.firstOrNull { it.id == id } ?: NONE
    }
}
