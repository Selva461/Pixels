package com.pixels.enhancer.domain.presets

import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.planning.ColorGrading
import com.pixels.enhancer.domain.planning.ColorMixer
import com.pixels.enhancer.domain.planning.ControlGroup
import com.pixels.enhancer.domain.planning.GradeWheel
import com.pixels.enhancer.domain.planning.HslShift
import com.pixels.enhancer.domain.planning.HueBand
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.planning.ToneCurves

/** Which parts of an edit a preset or a paste carries. Geometry, masks and retouching are per-photo and never included. */
enum class SettingsGroup(val label: String) {
    LIGHT("Light"),
    COLOR("Color"),
    EFFECTS("Effects"),
    DETAIL("Detail"),
    CURVES("Curve"),
    COLOR_MIXER("Color mixer"),
    COLOR_GRADING("Color grading"),
    ;

    companion object {
        val ALL: Set<SettingsGroup> = entries.toSet()
    }
}

/**
 * A preset: slider and colour settings that can be applied to any photo. [settings] uses the
 * normal edit model, so a user preset is simply a saved edit minus the per-photo parts.
 */
data class Preset(
    val id: String,
    val name: String,
    val category: String,
    val settings: EditState,
    val builtIn: Boolean = true,
)

object PresetMath {
    /** Manual controls belonging to each settings group. */
    private fun groupOf(control: ManualControl): SettingsGroup = when (control.group) {
        ControlGroup.LIGHT -> SettingsGroup.LIGHT
        ControlGroup.COLOR -> SettingsGroup.COLOR
        ControlGroup.EFFECTS -> SettingsGroup.EFFECTS
        ControlGroup.DETAIL, ControlGroup.PORTRAIT -> SettingsGroup.DETAIL
    }

    /**
     * Applies [preset] to [current] at [amount] (0..2, 1 = as designed), replacing the preset's
     * groups and keeping the photo's geometry, masks, retouching and scene choice.
     */
    fun apply(current: EditState, preset: Preset, amount: Float = 1f): EditState {
        val a = amount.coerceIn(0f, MAX_AMOUNT)
        val settings = preset.settings
        var manual = ManualAdjustments.NONE
        // Controls of groups the preset doesn't touch stay as the user set them.
        current.manual.values.forEach { (control, value) -> if (groupOf(control) !in presetGroups(preset)) manual = manual.with(control, value) }
        settings.manual.values.forEach { (control, value) -> manual = manual.with(control, value * a) }
        return current.copy(
            manual = manual,
            lookId = settings.lookId,
            colorMixer = scaleMixer(settings.colorMixer, a),
            colorGrading = scaleGrading(settings.colorGrading, a),
            toneCurves = settings.toneCurves,
        )
    }

    /** Copies the chosen [groups] of [from] onto [to] (Copy / Paste settings). */
    fun paste(from: EditState, to: EditState, groups: Set<SettingsGroup>): EditState {
        var manual = ManualAdjustments.NONE
        ManualControl.entries.forEach { control ->
            val value = if (groupOf(control) in groups) from.manual[control] else to.manual[control]
            manual = manual.with(control, value)
        }
        return to.copy(
            manual = manual,
            lookId = if (SettingsGroup.LIGHT in groups || SettingsGroup.COLOR in groups) from.lookId else to.lookId,
            toneCurves = if (SettingsGroup.CURVES in groups) from.toneCurves else to.toneCurves,
            colorMixer = if (SettingsGroup.COLOR_MIXER in groups) from.colorMixer else to.colorMixer,
            colorGrading = if (SettingsGroup.COLOR_GRADING in groups) from.colorGrading else to.colorGrading,
        )
    }

    /** The settings part of an edit, for saving as a user preset. */
    fun settingsOf(edit: EditState): EditState = EditState(
        strength = edit.strength,
        manual = edit.manual,
        lookId = edit.lookId,
        colorMixer = edit.colorMixer,
        toneCurves = edit.toneCurves,
        colorGrading = edit.colorGrading,
    )

    private fun presetGroups(preset: Preset): Set<SettingsGroup> =
        preset.settings.manual.values.keys.map(::groupOf).toSet() + setOf(SettingsGroup.LIGHT, SettingsGroup.COLOR)

    private fun scaleMixer(mixer: ColorMixer, a: Float) = mixer.shifts.entries.fold(ColorMixer.NONE) { acc, (band, shift) ->
        acc.with(band, HslShift(shift.hue * a, shift.saturation * a, shift.luminance * a))
    }

    private fun scaleGrading(grading: ColorGrading, a: Float): ColorGrading {
        fun s(w: GradeWheel) = GradeWheel(w.hue, w.saturation * a, w.luminance * a)
        return grading.copy(shadows = s(grading.shadows), midtones = s(grading.midtones), highlights = s(grading.highlights), global = s(grading.global)).clamped()
    }

    const val MAX_AMOUNT = 2f
}

/** Built-in presets: classic, non-generative looks made only of the app's own sliders. */
object PresetLibrary {
    private fun preset(
        id: String,
        name: String,
        category: String,
        vararg controls: Pair<ManualControl, Float>,
        mixer: Map<HueBand, HslShift> = emptyMap(),
        grading: ColorGrading = ColorGrading.NONE,
        curves: ToneCurves = ToneCurves.NONE,
    ) = Preset(
        id = "builtin.$id",
        name = name,
        category = category,
        settings = EditState(
            manual = ManualAdjustments.of(*controls),
            colorMixer = mixer.entries.fold(ColorMixer.NONE) { acc, (band, shift) -> acc.with(band, shift) },
            colorGrading = grading,
            toneCurves = curves,
        ),
    )

    private fun split(shadowHue: Float, shadowSat: Float, highlightHue: Float, highlightSat: Float, balance: Float = 0f, mono: Boolean = false) =
        ColorGrading(shadows = GradeWheel(shadowHue, shadowSat), highlights = GradeWheel(highlightHue, highlightSat), balance = balance, monochrome = mono)

    private const val PORTRAIT = "Portrait"
    private const val LANDSCAPE = "Landscape"
    private const val CINEMATIC = "Cinematic"
    private const val VINTAGE = "Vintage"
    private const val BW = "Black & White"
    private const val FOOD = "Food"
    private const val URBAN = "Urban"
    private const val SEASONS = "Seasons"

    val ALL: List<Preset> = listOf(
        preset(
            "portrait.natural", "Natural skin", PORTRAIT,
            ManualControl.TEXTURE to -0.2f, ManualControl.CLARITY to -0.1f, ManualControl.SHADOWS to 0.15f,
            mixer = mapOf(HueBand.ORANGE to HslShift(0f, -0.1f, 0.15f), HueBand.RED to HslShift(0.05f, -0.1f, 0.05f)),
        ),
        preset(
            "portrait.warm_glow", "Warm glow", PORTRAIT,
            ManualControl.TEMPERATURE to 0.25f, ManualControl.EXPOSURE to 0.05f, ManualControl.HIGHLIGHTS to -0.3f, ManualControl.CLARITY to -0.15f,
            mixer = mapOf(HueBand.ORANGE to HslShift(0f, 0f, 0.2f)),
            grading = split(220f, 0.1f, 40f, 0.2f),
        ),
        preset(
            "portrait.soft_matte", "Soft matte", PORTRAIT,
            ManualControl.CONTRAST to -0.3f, ManualControl.BLACKS to 0.5f, ManualControl.HIGHLIGHTS to -0.2f, ManualControl.SATURATION to -0.1f,
            ManualControl.TEXTURE to -0.25f,
        ),
        preset(
            "portrait.studio", "Studio clean", PORTRAIT,
            ManualControl.WHITES to 0.2f, ManualControl.BLACKS to -0.15f, ManualControl.CONTRAST to 0.15f, ManualControl.VIBRANCE to 0.1f,
            ManualControl.TEXTURE to -0.15f,
        ),
        preset(
            "landscape.vivid", "Vivid nature", LANDSCAPE,
            ManualControl.VIBRANCE to 0.45f, ManualControl.CLARITY to 0.3f, ManualControl.DEHAZE to 0.2f, ManualControl.HIGHLIGHTS to -0.4f,
            ManualControl.SHADOWS to 0.3f,
            mixer = mapOf(HueBand.BLUE to HslShift(0f, 0.2f, -0.15f), HueBand.GREEN to HslShift(0.1f, 0.1f, 0f)),
        ),
        preset(
            "landscape.golden", "Golden hour", LANDSCAPE,
            ManualControl.TEMPERATURE to 0.4f, ManualControl.TINT to 0.1f, ManualControl.HIGHLIGHTS to -0.35f, ManualControl.VIBRANCE to 0.25f,
            grading = split(30f, 0.15f, 45f, 0.35f),
        ),
        preset(
            "landscape.deep_sky", "Deep sky", LANDSCAPE,
            ManualControl.DEHAZE to 0.3f, ManualControl.HIGHLIGHTS to -0.5f, ManualControl.CONTRAST to 0.15f,
            mixer = mapOf(HueBand.BLUE to HslShift(0f, 0.25f, -0.35f), HueBand.AQUA to HslShift(0.15f, 0.1f, -0.15f)),
        ),
        preset(
            "landscape.misty", "Misty morning", LANDSCAPE,
            ManualControl.DEHAZE to -0.25f, ManualControl.CONTRAST to -0.25f, ManualControl.TEMPERATURE to -0.15f, ManualControl.SATURATION to -0.2f,
            ManualControl.HIGHLIGHTS to 0.15f,
        ),
        preset(
            "cinematic.teal_orange", "Teal & orange", CINEMATIC,
            ManualControl.CONTRAST to 0.25f, ManualControl.SATURATION to -0.1f, ManualControl.VIGNETTE to -0.25f,
            mixer = mapOf(HueBand.AQUA to HslShift(0.15f, 0.1f, -0.1f), HueBand.ORANGE to HslShift(0f, 0.15f, 0.05f), HueBand.GREEN to HslShift(0.4f, -0.3f, 0f)),
            grading = split(190f, 0.35f, 35f, 0.3f),
        ),
        preset(
            "cinematic.moody", "Moody", CINEMATIC,
            ManualControl.EXPOSURE to -0.1f, ManualControl.CONTRAST to 0.2f, ManualControl.HIGHLIGHTS to -0.5f, ManualControl.SATURATION to -0.3f,
            ManualControl.VIGNETTE to -0.4f, ManualControl.BLACKS to 0.2f,
            grading = split(220f, 0.25f, 50f, 0.1f),
        ),
        preset(
            "cinematic.bleach", "Bleach bypass", CINEMATIC,
            ManualControl.CONTRAST to 0.45f, ManualControl.SATURATION to -0.55f, ManualControl.CLARITY to 0.3f, ManualControl.HIGHLIGHTS to -0.2f,
        ),
        preset(
            "cinematic.night", "Neon night", CINEMATIC,
            ManualControl.CONTRAST to 0.2f, ManualControl.VIBRANCE to 0.35f, ManualControl.TEMPERATURE to -0.25f, ManualControl.TINT to 0.2f,
            grading = split(250f, 0.3f, 320f, 0.2f),
        ),
        preset(
            "vintage.faded", "Faded film", VINTAGE,
            ManualControl.CONTRAST to -0.25f, ManualControl.BLACKS to 0.6f, ManualControl.SATURATION to -0.25f, ManualControl.GRAIN to 0.3f,
            ManualControl.GRAIN_SIZE to 0.3f,
            grading = split(200f, 0.15f, 45f, 0.2f),
        ),
        preset(
            "vintage.kodak", "Warm print", VINTAGE,
            ManualControl.TEMPERATURE to 0.3f, ManualControl.CONTRAST to 0.15f, ManualControl.GRAIN to 0.25f, ManualControl.VIGNETTE to -0.3f,
            mixer = mapOf(HueBand.YELLOW to HslShift(-0.1f, 0.15f, 0f), HueBand.GREEN to HslShift(-0.2f, -0.2f, 0f)),
        ),
        preset(
            "vintage.polaroid", "Instant", VINTAGE,
            ManualControl.EXPOSURE to 0.1f, ManualControl.CONTRAST to -0.2f, ManualControl.BLACKS to 0.45f, ManualControl.TINT to 0.15f,
            ManualControl.VIGNETTE to -0.35f, ManualControl.GRAIN to 0.2f,
            grading = split(170f, 0.2f, 60f, 0.15f),
        ),
        preset(
            "vintage.seventies", "Seventies", VINTAGE,
            ManualControl.TEMPERATURE to 0.35f, ManualControl.SATURATION to -0.15f, ManualControl.BLACKS to 0.35f, ManualControl.GRAIN to 0.35f,
            grading = split(30f, 0.2f, 50f, 0.25f),
        ),
        preset("bw.classic", "Classic", BW, ManualControl.CONTRAST to 0.25f, ManualControl.CLARITY to 0.15f, grading = ColorGrading(monochrome = true)),
        preset(
            "bw.high_contrast", "High contrast", BW,
            ManualControl.CONTRAST to 0.6f, ManualControl.WHITES to 0.3f, ManualControl.BLACKS to -0.3f, ManualControl.CLARITY to 0.35f,
            grading = ColorGrading(monochrome = true),
            mixer = mapOf(HueBand.BLUE to HslShift(0f, 0f, -0.5f), HueBand.ORANGE to HslShift(0f, 0f, 0.2f)),
        ),
        preset(
            "bw.soft", "Soft grey", BW,
            ManualControl.CONTRAST to -0.25f, ManualControl.BLACKS to 0.35f, ManualControl.GRAIN to 0.2f,
            grading = ColorGrading(monochrome = true),
        ),
        preset(
            "bw.selenium", "Selenium", BW,
            ManualControl.CONTRAST to 0.3f,
            grading = split(260f, 0.2f, 40f, 0.1f, mono = true),
        ),
        preset(
            "bw.sepia", "Sepia", BW,
            ManualControl.CONTRAST to 0.1f, ManualControl.GRAIN to 0.25f, ManualControl.VIGNETTE to -0.3f,
            grading = ColorGrading(global = GradeWheel(35f, 0.35f), monochrome = true),
        ),
        preset(
            "food.fresh", "Fresh", FOOD,
            ManualControl.EXPOSURE to 0.1f, ManualControl.VIBRANCE to 0.35f, ManualControl.TEXTURE to 0.25f, ManualControl.SHADOWS to 0.25f,
            ManualControl.TEMPERATURE to 0.1f,
        ),
        preset(
            "food.dark", "Dark & rich", FOOD,
            ManualControl.EXPOSURE to -0.1f, ManualControl.CONTRAST to 0.3f, ManualControl.HIGHLIGHTS to -0.35f, ManualControl.VIBRANCE to 0.2f,
            ManualControl.VIGNETTE to -0.35f, ManualControl.TEXTURE to 0.2f,
        ),
        preset(
            "urban.gritty", "Gritty", URBAN,
            ManualControl.CONTRAST to 0.35f, ManualControl.CLARITY to 0.5f, ManualControl.TEXTURE to 0.35f, ManualControl.SATURATION to -0.35f,
            ManualControl.VIGNETTE to -0.3f,
        ),
        preset(
            "urban.cool_city", "Cool city", URBAN,
            ManualControl.TEMPERATURE to -0.3f, ManualControl.CONTRAST to 0.2f, ManualControl.CLARITY to 0.2f, ManualControl.HIGHLIGHTS to -0.3f,
            grading = split(210f, 0.25f, 200f, 0.1f),
        ),
        preset(
            "seasons.spring", "Spring", SEASONS,
            ManualControl.EXPOSURE to 0.1f, ManualControl.VIBRANCE to 0.3f, ManualControl.TINT to 0.05f,
            mixer = mapOf(HueBand.GREEN to HslShift(-0.15f, 0.15f, 0.15f), HueBand.MAGENTA to HslShift(0f, 0.15f, 0.1f)),
        ),
        preset(
            "seasons.autumn", "Autumn", SEASONS,
            ManualControl.TEMPERATURE to 0.3f, ManualControl.VIBRANCE to 0.3f,
            mixer = mapOf(HueBand.ORANGE to HslShift(0f, 0.3f, 0f), HueBand.YELLOW to HslShift(-0.25f, 0.2f, 0f), HueBand.GREEN to HslShift(-0.35f, -0.1f, 0f)),
        ),
        preset(
            "seasons.winter", "Winter", SEASONS,
            ManualControl.TEMPERATURE to -0.3f, ManualControl.EXPOSURE to 0.1f, ManualControl.WHITES to 0.2f, ManualControl.SATURATION to -0.2f,
        ),
    )

    val categories: List<String> get() = ALL.map { it.category }.distinct()

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }
}

/** Saved user presets. */
interface PresetStore {
    suspend fun list(): List<Preset>
    suspend fun save(preset: Preset)
    suspend fun delete(id: String)
}
