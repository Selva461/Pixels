package com.pixels.enhancer.domain.planning

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/** How a slider value is shown to the user. */
enum class ValueFormat {
    /** −100..+100 (or 0..100). */
    PERCENT,

    /** Camera stops: value × scale, one decimal, e.g. "+1.5 EV". */
    EV,

    /** Gamma 0.5..2.0: 2^value. */
    GAMMA,
}

/**
 * A user-facing slider. Slider values run over [min]..[max] (0 = no change); [scale] converts a
 * slider value of ±1 into a plan amount. The automatic correction and the manual offsets of every
 * control of the same [kind] are added, then clamped to [combinedRange] — wider than the natural
 * auto ranges because the user explicitly asked for the look, but still bounded so nothing breaks.
 * [advanced] controls are progressively disclosed so the beginner panel stays short.
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
    val advanced: Boolean = false,
    val format: ValueFormat = ValueFormat.PERCENT,
) {
    EXPOSURE("Exposure", ControlGroup.LIGHT, AdjustmentKind.EXPOSURE, -1f, 1f, 3f, AmountRange(-3.5f, 3.5f), "Overall brightness, in camera stops", format = ValueFormat.EV),
    CONTRAST("Contrast", ControlGroup.LIGHT, AdjustmentKind.CONTRAST, -1f, 1f, 0.15f, AmountRange(-0.2f, 0.25f), "Separation between light and dark"),
    HIGHLIGHTS("Highlights", ControlGroup.LIGHT, AdjustmentKind.HIGHLIGHTS, -1f, 1f, 0.2f, AmountRange(-0.3f, 0.2f), "Bright areas; lower to recover detail"),
    SHADOWS("Shadows", ControlGroup.LIGHT, AdjustmentKind.SHADOWS, -1f, 1f, 0.2f, AmountRange(-0.2f, 0.3f), "Dark areas; raise to open them up"),
    WHITES("Whites", ControlGroup.LIGHT, AdjustmentKind.WHITES, -1f, 1f, 0.15f, AmountRange(-0.15f, 0.15f), "The brightest tones and white point"),
    BLACKS("Blacks", ControlGroup.LIGHT, AdjustmentKind.BLACKS, -1f, 1f, 0.15f, AmountRange(-0.15f, 0.15f), "The darkest tones and black point"),
    BRIGHTNESS("Brightness", ControlGroup.LIGHT, AdjustmentKind.BRIGHTNESS, -1f, 1f, 0.15f, AmountRange(-0.15f, 0.15f), "Perceived lightness across most tones", advanced = true),
    MIDTONES("Midtones", ControlGroup.LIGHT, AdjustmentKind.MIDTONES, -1f, 1f, 0.15f, AmountRange(-0.15f, 0.15f), "Only the middle tones; black and white stay put", advanced = true),
    GAMMA("Gamma", ControlGroup.LIGHT, AdjustmentKind.GAMMA, -1f, 1f, 1f, AmountRange(-1f, 1f), "Midtone response, 0.5 to 2.0", advanced = true, format = ValueFormat.GAMMA),
    EXPOSURE_COMPENSATION("Exposure compensation", ControlGroup.LIGHT, AdjustmentKind.EXPOSURE, -1f, 1f, 2f, AmountRange(-3.5f, 3.5f), "Fine exposure correction on top of Exposure", advanced = true, format = ValueFormat.EV),
    TEMPERATURE("Temperature", ControlGroup.COLOR, AdjustmentKind.TEMPERATURE, -1f, 1f, 1f, AmountRange(-1f, 1f), "Cooler (blue) to warmer (yellow)"),
    TINT("Tint", ControlGroup.COLOR, AdjustmentKind.TINT, -1f, 1f, 1f, AmountRange(-1f, 1f), "Green to magenta"),
    VIBRANCE("Vibrance", ControlGroup.COLOR, AdjustmentKind.SATURATION, -1f, 1f, 0.35f, AmountRange(-0.4f, 0.4f), "Boosts muted colours more than vivid ones and spares skin"),
    HUE("Hue", ControlGroup.COLOR, AdjustmentKind.GLOBAL_HUE, -1f, 1f, 1f, AmountRange(-1f, 1f), "Shifts every colour around the colour wheel, up to 30°", advanced = true),
    SATURATION("Saturation", ControlGroup.COLOR, AdjustmentKind.GLOBAL_SATURATION, -1f, 1f, 1f, AmountRange(-1f, 1f), "All colours equally; −100 is black and white"),
    CLARITY("Clarity", ControlGroup.DETAIL, AdjustmentKind.DETAIL, -1f, 1f, 0.25f, AmountRange(-0.25f, 0.35f), "Local contrast in midtones"),
    TEXTURE("Texture", ControlGroup.DETAIL, AdjustmentKind.TEXTURE, -1f, 1f, 0.5f, AmountRange(-0.5f, 0.5f), "Fine surface detail such as fabric, bark or skin pores", advanced = true),
    DEHAZE("Dehaze", ControlGroup.DETAIL, AdjustmentKind.DEHAZE, -1f, 1f, 0.8f, AmountRange(-0.8f, 0.8f), "Removes (or adds) atmospheric haze"),
    SHARPNESS("Sharpness", ControlGroup.DETAIL, AdjustmentKind.SHARPENING, 0f, 1f, 0.6f, AmountRange(0f, 0.8f), "Edge definition; cannot restore missing detail"),
    SHARPEN_MASKING("Sharpen masking", ControlGroup.DETAIL, AdjustmentKind.SHARPEN_MASKING, 0f, 1f, 1f, AmountRange(0f, 1f), "Higher limits sharpening to strong edges only", advanced = true),
    SHARPEN_RADIUS("Sharpen radius", ControlGroup.DETAIL, AdjustmentKind.SHARPEN_RADIUS, 0f, 1f, 1f, AmountRange(0f, 1f), "Width of sharpened edges, 0.5 to 3 px", advanced = true),
    SHARPEN_DETAIL("Sharpen detail", ControlGroup.DETAIL, AdjustmentKind.SHARPEN_DETAIL, 0f, 1f, 1f, AmountRange(0f, 1f), "Higher sharpens fine detail more; lower keeps halos down", advanced = true),
    DEFRINGE_PURPLE("Defringe purple", ControlGroup.DETAIL, AdjustmentKind.DEFRINGE_PURPLE, 0f, 1f, 1f, AmountRange(0f, 1f), "Removes purple fringes along high-contrast edges", advanced = true),
    DEFRINGE_GREEN("Defringe green", ControlGroup.DETAIL, AdjustmentKind.DEFRINGE_GREEN, 0f, 1f, 1f, AmountRange(0f, 1f), "Removes green fringes along high-contrast edges", advanced = true),
    NOISE_REDUCTION("Noise reduction", ControlGroup.DETAIL, AdjustmentKind.NOISE_REDUCTION, 0f, 1f, 1f, AmountRange(0f, 1f), "Smooths brightness grain while keeping edges"),
    COLOR_NOISE_REDUCTION("Colour noise reduction", ControlGroup.DETAIL, AdjustmentKind.COLOR_NOISE_REDUCTION, 0f, 1f, 1f, AmountRange(0f, 1f), "Removes coloured speckles; brightness detail is untouched", advanced = true),
    FACE_EXPOSURE("Face exposure", ControlGroup.PORTRAIT, AdjustmentKind.FACE_EXPOSURE, -1f, 1f, 1f, AmountRange(-1.25f, 1.5f), "Brightens or darkens detected faces only, with a soft edge", format = ValueFormat.EV),
    VIGNETTE("Vignette", ControlGroup.EFFECTS, AdjustmentKind.VIGNETTE, -1f, 1f, 1f, AmountRange(-1f, 1f), "Darker or lighter corners"),
    VIGNETTE_MIDPOINT("Vignette midpoint", ControlGroup.EFFECTS, AdjustmentKind.VIGNETTE_MIDPOINT, -1f, 1f, 1f, AmountRange(-1f, 1f), "How far the vignette reaches toward the centre", advanced = true),
    VIGNETTE_FEATHER("Vignette feather", ControlGroup.EFFECTS, AdjustmentKind.VIGNETTE_FEATHER, -1f, 1f, 1f, AmountRange(-1f, 1f), "Softness of the vignette edge", advanced = true),
    VIGNETTE_ROUNDNESS("Vignette roundness", ControlGroup.EFFECTS, AdjustmentKind.VIGNETTE_ROUNDNESS, -1f, 1f, 1f, AmountRange(-1f, 1f), "Oval (follows the frame) to circular", advanced = true),
    GRAIN("Grain", ControlGroup.EFFECTS, AdjustmentKind.GRAIN, 0f, 1f, 1f, AmountRange(0f, 1f), "Film-like texture"),
    GRAIN_SIZE("Grain size", ControlGroup.EFFECTS, AdjustmentKind.GRAIN_SIZE, 0f, 1f, 1f, AmountRange(0f, 1f), "Fine to coarse grain", advanced = true),
    GRAIN_ROUGHNESS("Grain roughness", ControlGroup.EFFECTS, AdjustmentKind.GRAIN_ROUGHNESS, 0f, 1f, 1f, AmountRange(0f, 1f), "Even to clumpy grain", advanced = true),
    ;

    /** The value as the user reads it, e.g. "+25", "−1.5 EV" or "γ 1.41". */
    fun format(value: Float): String = when (format) {
        ValueFormat.PERCENT -> (value * PERCENT).roundToInt().let { if (it > 0) "+$it" else it.toString() }
        ValueFormat.EV -> String.format(Locale.ROOT, "%+.1f EV", value * scale).replace("+0.0", "0.0").replace("-0.0", "0.0")
        ValueFormat.GAMMA -> String.format(Locale.ROOT, "γ %.2f", 2f.pow(value))
    }

    private companion object {
        const val PERCENT = 100
    }
}

enum class ControlGroup(val label: String) {
    LIGHT("Light"),
    COLOR("Colour"),
    DETAIL("Detail"),
    PORTRAIT("Portrait"),
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
