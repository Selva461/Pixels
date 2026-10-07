package com.pixels.enhancer.ui.panels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.analysis.Histogram
import com.pixels.enhancer.domain.analysis.SceneType
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.geometry.LensCorrection
import com.pixels.enhancer.domain.geometry.Perspective
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.project.EditVersion
import com.pixels.enhancer.ui.adjust.ColorMixerPanel
import com.pixels.enhancer.ui.adjust.CurvePanel
import com.pixels.enhancer.ui.adjust.HistogramView
import com.pixels.enhancer.ui.components.PanelHeading
import com.pixels.enhancer.ui.components.ProSlider
import com.pixels.enhancer.ui.components.Tracks
import com.pixels.enhancer.ui.components.percentText
import com.pixels.enhancer.ui.editor.CropAspect
import com.pixels.enhancer.ui.editor.EditorActions
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Scrollable column every panel uses. */
@Composable
fun PanelColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) { content() }
}

/** One manual-control slider; double-tap resets. */
@Composable
fun ControlSlider(control: ManualControl, edit: EditState, actions: EditorActions) {
    val value = edit.manual[control]
    ProSlider(
        label = control.label,
        value = value,
        valueText = control.format(value),
        onChange = { actions.onControlChanged(control, it) },
        onFinished = actions::onEditFinished,
        range = control.min..control.max,
        track = when (control) {
            ManualControl.TEMPERATURE -> Tracks.temperature
            ManualControl.TINT -> Tracks.tint
            ManualControl.HUE -> Tracks.hue
            else -> null
        },
    )
}

/** Small segmented switch between a panel's sub-views. */
@Composable
fun <T> SegmentRow(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

private enum class LightView { SLIDERS, CURVE }

@Composable
fun LightPanel(edit: EditState, histogram: Histogram?, showClipping: Boolean, actions: EditorActions) {
    var view by rememberSaveable { mutableStateOf(LightView.SLIDERS) }
    Column(Modifier.fillMaxSize()) {
        SegmentRow(LightView.entries, view, { stringResource(if (it == LightView.SLIDERS) R.string.panel_adjust else R.string.panel_curve) }) { view = it }
        when (view) {
            LightView.CURVE -> CurvePanel(edit, histogram, actions::onCurveChanged, actions::onEditFinished, actions::onResetCurve)
            LightView.SLIDERS -> PanelColumn {
                histogram?.let { HistogramView(it, Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 20.dp)) }
                Row(Modifier.padding(horizontal = 16.dp)) {
                    FilterChip(
                        selected = showClipping,
                        onClick = { actions.onShowClippingChanged(!showClipping) },
                        label = { Text(stringResource(R.string.light_show_clipping)) },
                    )
                }
                listOf(
                    ManualControl.EXPOSURE, ManualControl.CONTRAST, ManualControl.HIGHLIGHTS, ManualControl.SHADOWS,
                    ManualControl.WHITES, ManualControl.BLACKS,
                ).forEach { ControlSlider(it, edit, actions) }
                PanelHeading(stringResource(R.string.panel_fine_tune))
                listOf(ManualControl.BRIGHTNESS, ManualControl.MIDTONES, ManualControl.GAMMA, ManualControl.EXPOSURE_COMPENSATION)
                    .forEach { ControlSlider(it, edit, actions) }
                PanelHeading(stringResource(R.string.panel_portrait))
                ControlSlider(ManualControl.FACE_EXPOSURE, edit, actions)
            }
        }
    }
}

private enum class ColorView { ADJUST, MIXER, GRADING }

@Composable
fun ColorPanel(edit: EditState, picking: Boolean, onPick: () -> Unit, actions: EditorActions) {
    var view by rememberSaveable { mutableStateOf(ColorView.ADJUST) }
    Column(Modifier.fillMaxSize()) {
        SegmentRow(ColorView.entries, view, {
            stringResource(
                when (it) {
                    ColorView.ADJUST -> R.string.panel_adjust
                    ColorView.MIXER -> R.string.panel_mixer
                    ColorView.GRADING -> R.string.panel_grading
                },
            )
        }) { view = it }
        when (view) {
            ColorView.ADJUST -> PanelColumn {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!edit.colorGrading.monochrome, { actions.onMonochromeChanged(false) }, { Text(stringResource(R.string.color_treatment_color)) })
                    FilterChip(edit.colorGrading.monochrome, { actions.onMonochromeChanged(true) }, { Text(stringResource(R.string.color_treatment_bw)) })
                    FilterChip(
                        selected = picking,
                        onClick = onPick,
                        leadingIcon = { Icon(Icons.Outlined.Colorize, contentDescription = null) },
                        label = { Text(stringResource(R.string.color_pick_wb)) },
                    )
                }
                listOf(ManualControl.TEMPERATURE, ManualControl.TINT, ManualControl.VIBRANCE, ManualControl.SATURATION, ManualControl.HUE)
                    .forEach { ControlSlider(it, edit, actions) }
                if (edit.colorGrading.monochrome) {
                    Text(
                        stringResource(R.string.color_bw_mix_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            ColorView.MIXER -> ColorMixerPanel(edit, actions::onColorMixerChanged, actions::onEditFinished, actions::onResetColorMixer)
            ColorView.GRADING -> GradingPanel(edit.colorGrading, actions)
        }
    }
}

@Composable
fun EffectsPanel(edit: EditState, actions: EditorActions) {
    PanelColumn {
        listOf(ManualControl.TEXTURE, ManualControl.CLARITY, ManualControl.DEHAZE).forEach { ControlSlider(it, edit, actions) }
        PanelHeading(stringResource(R.string.panel_vignette))
        listOf(ManualControl.VIGNETTE, ManualControl.VIGNETTE_MIDPOINT, ManualControl.VIGNETTE_FEATHER, ManualControl.VIGNETTE_ROUNDNESS)
            .forEach { ControlSlider(it, edit, actions) }
        PanelHeading(stringResource(R.string.panel_grain))
        listOf(ManualControl.GRAIN, ManualControl.GRAIN_SIZE, ManualControl.GRAIN_ROUGHNESS).forEach { ControlSlider(it, edit, actions) }
    }
}

@Composable
fun DetailPanel(edit: EditState, actions: EditorActions) {
    PanelColumn {
        PanelHeading(stringResource(R.string.panel_sharpening))
        listOf(ManualControl.SHARPNESS, ManualControl.SHARPEN_RADIUS, ManualControl.SHARPEN_DETAIL, ManualControl.SHARPEN_MASKING)
            .forEach { ControlSlider(it, edit, actions) }
        PanelHeading(stringResource(R.string.panel_noise))
        listOf(ManualControl.NOISE_REDUCTION, ManualControl.COLOR_NOISE_REDUCTION).forEach { ControlSlider(it, edit, actions) }
    }
}

@Composable
fun OpticsPanel(geometry: Geometry, actions: EditorActions) {
    val lens = geometry.lens
    PanelColumn {
        Text(
            stringResource(R.string.optics_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        ProSlider(stringResource(R.string.optics_distortion), lens.distortion, percentText(lens.distortion), { actions.onLensChanged(lens.copy(distortion = it)) }, actions::onEditFinished)
        ProSlider(
            stringResource(R.string.optics_chromatic), lens.chromaticAberration, percentText(lens.chromaticAberration),
            { actions.onLensChanged(lens.copy(chromaticAberration = it)) }, actions::onEditFinished,
        )
        ProSlider(stringResource(R.string.optics_vignetting), lens.vignetting, percentText(lens.vignetting), { actions.onLensChanged(lens.copy(vignetting = it)) }, actions::onEditFinished)
        TextButton(onClick = actions::onResetLens, enabled = !lens.isIdentity, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.reset))
        }
    }
}

@Composable
fun GeometryPanel(geometry: Geometry, actions: EditorActions) {
    val p = geometry.perspective
    fun set(value: Perspective) = actions.onPerspectiveChanged(value)
    PanelColumn {
        Text(
            stringResource(R.string.geometry_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions::onAutoUpright, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.geometry_auto)) }
        }
        ProSlider(stringResource(R.string.geometry_vertical), p.vertical, percentText(p.vertical), { set(p.copy(vertical = it)) }, actions::onEditFinished)
        ProSlider(stringResource(R.string.geometry_horizontal), p.horizontal, percentText(p.horizontal), { set(p.copy(horizontal = it)) }, actions::onEditFinished)
        ProSlider(
            stringResource(R.string.geometry_rotate), p.rotate, String.format(Locale.ROOT, "%.1f°", p.rotate), { set(p.copy(rotate = it)) }, actions::onEditFinished,
            range = -Perspective.MAX_ROTATE_DEGREES..Perspective.MAX_ROTATE_DEGREES,
        )
        ProSlider(stringResource(R.string.geometry_aspect), p.aspect, percentText(p.aspect), { set(p.copy(aspect = it)) }, actions::onEditFinished)
        ProSlider(stringResource(R.string.geometry_scale), p.scale, percentText(p.scale), { set(p.copy(scale = it)) }, actions::onEditFinished)
        ProSlider(stringResource(R.string.geometry_offset_x), p.offsetX, percentText(p.offsetX), { set(p.copy(offsetX = it)) }, actions::onEditFinished)
        ProSlider(stringResource(R.string.geometry_offset_y), p.offsetY, percentText(p.offsetY), { set(p.copy(offsetY = it)) }, actions::onEditFinished)
        TextButton(onClick = actions::onResetPerspective, enabled = !p.isIdentity, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.reset))
        }
    }
}

@Composable
fun AutoPanel(edit: EditState, detectedScene: SceneType, actions: EditorActions) {
    val override = edit.sceneOverride
    PanelColumn {
        ProSlider(
            stringResource(R.string.auto_strength), edit.strength, "${(edit.strength * 100).roundToInt()}%",
            actions::onStrengthChanged, actions::onEditFinished, range = 0f..1f, resetValue = 0f,
        )
        Text(
            stringResource(R.string.editor_auto_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        PanelHeading(stringResource(R.string.editor_scene, (override ?: detectedScene).label, detectedScene.label))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(override == null, { actions.onSceneSelected(null) }, { Text(stringResource(R.string.editor_scene_auto)) })
            SceneType.entries.forEach { scene -> FilterChip(override == scene, { actions.onSceneSelected(scene) }, { Text(scene.label) }) }
        }
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            TextButton(onClick = actions::onResetAll) { Text(stringResource(R.string.editor_reset_all)) }
            TextButton(onClick = actions::onShowOriginalEdit) { Text(stringResource(R.string.editor_original_state)) }
        }
    }
}

@Composable
fun CropPanel(edit: EditState, aspect: CropAspect, actions: EditorActions) {
    val straighten = edit.geometry.straightenDegrees
    PanelColumn {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIconButton(Icons.Outlined.RotateLeft, stringResource(R.string.crop_rotate_left), actions::onRotateCounterClockwise)
            ToolIconButton(Icons.Outlined.RotateRight, stringResource(R.string.crop_rotate_right), actions::onRotateClockwise)
            ToolIconButton(Icons.Outlined.Flip, stringResource(R.string.crop_flip), actions::onFlip)
            ToolIconButton(Icons.Outlined.Flip, stringResource(R.string.crop_flip_vertical), actions::onFlipVertical, Modifier.rotate(90f))
            TextButton(onClick = actions::onAutoStraighten) { Text(stringResource(R.string.crop_auto_straighten)) }
            TextButton(onClick = actions::onResetGeometry) { Text(stringResource(R.string.crop_reset)) }
        }
        ProSlider(
            stringResource(R.string.crop_straighten_label), straighten, String.format(Locale.ROOT, "%.1f°", straighten),
            actions::onStraightenChanged, actions::onEditFinished,
            range = -Geometry.MAX_STRAIGHTEN_DEGREES..Geometry.MAX_STRAIGHTEN_DEGREES,
        )
        PanelHeading(stringResource(R.string.crop_aspect))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CropAspect.entries.forEach { option -> FilterChip(option == aspect, { actions.onCropAspectSelected(option) }, { Text(option.label) }) }
        }
    }
}

@Composable
fun ToolIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, iconModifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label, modifier = iconModifier) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun VersionsPanel(versions: List<EditVersion>, actions: EditorActions, onSaveVersion: () -> Unit) {
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    PanelColumn {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.versions_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onSaveVersion, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.versions_save)) }
        }
        if (versions.isEmpty()) {
            Text(
                stringResource(R.string.versions_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        versions.asReversed().forEach { version ->
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { actions.onApplyVersion(version) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(version.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(format.format(Date(version.createdAtMillis)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { actions.onDeleteVersion(version) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Placeholder area while nothing is selected; keeps panel height stable. */
@Composable
fun PanelHint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
