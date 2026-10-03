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
    val group: ControlGroup,
    val kind: AdjustmentKind,
    val min: Float,
    val max: Float,
    val scale: Float,
    val combinedRange: AmountRange,
    /** Shown under the slider in the editor. */
    val description: String,
) {
    EXPOSURE("Exposure", ControlGroup.LIGHT, AdjustmentKind.EXPOSURE, -1f, 1f, 1.2f, AmountRange(-1.5f, 1.5f), "Overall brightness, in camera stops"),
    CONTRAST("Contrast", ControlGroup.LIGHT, AdjustmentKind.CONTRAST, -1f, 1f, 0.15f, AmountRange(-0.2f, 0.25f), "Separation between light and dark"),
    HIGHLIGHTS("Highlights", ControlGroup.LIGHT, AdjustmentKind.HIGHLIGHTS, -1f, 1f, 0.2f, AmountRange(-0.3f, 0.2f), "Bright areas; lower to recover detail"),
    SHADOWS("Shadows", ControlGroup.LIGHT, AdjustmentKind.SHADOWS, -1f, 1f, 0.2f, AmountRange(-0.2f, 0.3f), "Dark areas; raise to open them up"),
    WHITES("Whites", ControlGroup.LIGHT, AdjustmentKind.WHITES, -1f, 1f, 0.15f, AmountRange(-0.15f, 0.15f), "The brightest tones and white point"),
    BLACKS("Blacks", ControlGroup.LIGHT, AdjustmentKind.BLACKS, -1f, 1f, 0.15f, AmountRange(-0.15f, 0.15f), "The darkest tones and black point"),
    MIDTONES("Midtones", ControlGroup.LIGHT, AdjustmentKind.MIDTONES, -1f, 1f, 0.15f, AmountRange(-0.15f, 0.15f), "Middle brightness without moving black or white"),
    TEMPERATURE("Temperature", ControlGroup.COLOR, AdjustmentKind.TEMPERATURE, -1f, 1f, 1f, AmountRange(-1f, 1f), "Cooler (blue) to warmer (yellow)"),
    TINT("Tint", ControlGroup.COLOR, AdjustmentKind.TINT, -1f, 1f, 1f, AmountRange(-1f, 1f), "Green to magenta"),
    VIBRANCE("Vibrance", ControlGroup.COLOR, AdjustmentKind.SATURATION, -1f, 1f, 0.35f, AmountRange(-0.4f, 0.4f), "Boosts muted colours more than vivid ones and spares skin"),
    SATURATION("Saturation", ControlGroup.COLOR, AdjustmentKind.GLOBAL_SATURATION, -1f, 1f, 1f, AmountRange(-1f, 1f), "All colours equally; −100 is black and white"),
    CLARITY("Clarity", ControlGroup.DETAIL, AdjustmentKind.DETAIL, -1f, 1f, 0.25f, AmountRange(-0.25f, 0.35f), "Local contrast in midtones"),
    DEHAZE("Dehaze", ControlGroup.DETAIL, AdjustmentKind.DEHAZE, -1f, 1f, 0.8f, AmountRange(-0.8f, 0.8f), "Removes (or adds) atmospheric haze"),
    SHARPNESS("Sharpness", ControlGroup.DETAIL, AdjustmentKind.SHARPENING, 0f, 1f, 0.6f, AmountRange(0f, 0.8f), "Edge definition; cannot restore missing detail"),
    NOISE_REDUCTION("Noise reduction", ControlGroup.DETAIL, AdjustmentKind.NOISE_REDUCTION, 0f, 1f, 1f, AmountRange(0f, 1f), "Smooths grain while keeping edges"),
    VIGNETTE("Vignette", ControlGroup.EFFECTS, AdjustmentKind.VIGNETTE, -1f, 1f, 1f, AmountRange(-1f, 1f), "Darker or lighter corners"),
    GRAIN("Grain", ControlGroup.EFFECTS, AdjustmentKind.GRAIN, 0f, 1f, 1f, AmountRange(0f, 1f), "Film-like texture"),
}

enum class ControlGroup(val label: String) {
    LIGHT("Light"),
    COLOR("Color"),
    DETAIL("Detail"),
    EFFECTS("Effects"),
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
