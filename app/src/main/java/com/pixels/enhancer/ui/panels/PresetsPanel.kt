package com.pixels.enhancer.ui.panels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.planning.GradeWheel
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.presets.Preset
import com.pixels.enhancer.domain.presets.PresetLibrary
import com.pixels.enhancer.domain.presets.PresetMath
import com.pixels.enhancer.ui.components.ProSlider
import com.pixels.enhancer.ui.editor.AppliedPreset
import com.pixels.enhancer.ui.editor.EditorActions
import kotlin.math.roundToInt

/**
 * Preset browser: categories, a strip of preset tiles and the Amount slider for the applied one.
 * Long-press a preset of your own to delete it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PresetsPanel(userPresets: List<Preset>, applied: AppliedPreset?, actions: EditorActions, onCreatePreset: () -> Unit) {
    val yours = stringResource(R.string.presets_yours)
    val categories = listOf(yours) + PresetLibrary.categories
    var category by rememberSaveable { mutableStateOf(PresetLibrary.categories.first()) }
    var confirmDelete by remember { mutableStateOf<Preset?>(null) }
    val shown = if (category == yours) userPresets else PresetLibrary.ALL.filter { it.category == category }

    confirmDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.presets_delete_title, preset.name)) },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDeletePreset(preset)
                    confirmDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    // Scrolls, so the Amount slider stays reachable when large text makes the tiles taller.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SegmentRow(categories, category, { it }) { category = it }
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (category == yours) {
                item {
                    OutlinedButton(onClick = onCreatePreset, shape = MaterialTheme.shapes.medium, modifier = Modifier.width(TILE_WIDTH).heightIn(min = TILE_HEIGHT)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Text(stringResource(R.string.presets_create), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            items(shown, key = { it.id }) { preset ->
                val selected = applied?.preset?.id == preset.id
                Column(
                    Modifier
                        // Fixed width, flexible height: long names wrap at large text sizes.
                        .width(TILE_WIDTH)
                        .heightIn(min = TILE_HEIGHT)
                        .border(
                            if (selected) 3.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            MaterialTheme.shapes.medium,
                        )
                        .semantics { this.selected = selected }
                        .combinedClickable(
                            onClickLabel = preset.name,
                            onClick = { actions.onPresetApplied(preset) },
                            onLongClick = if (preset.builtIn) null else ({ confirmDelete = preset }),
                        )
                        .padding(6.dp),
                ) {
                    Box(Modifier.fillMaxWidth().height(52.dp).background(swatchOf(preset), MaterialTheme.shapes.small)) {
                        // The applied preset also gets a tick, so the choice is not shown by colour alone.
                        if (selected) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .background(MaterialTheme.colorScheme.surface, CircleShape),
                            )
                        }
                    }
                    Text(
                        preset.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else null,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (category == yours && userPresets.isEmpty()) {
            Text(
                stringResource(R.string.presets_yours_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        if (applied != null) {
            ProSlider(
                stringResource(R.string.presets_amount, applied.preset.name),
                applied.amount,
                "${(applied.amount * 100).roundToInt()}%",
                actions::onPresetAmountChanged,
                actions::onEditFinished,
                range = 0f..PresetMath.MAX_AMOUNT,
                resetValue = 1f,
            )
        }
    }
}

private val TILE_WIDTH = 92.dp
private val TILE_HEIGHT = 96.dp

/**
 * A tile colour that hints at the preset's look without rendering it: warm or cool from
 * temperature, the grading tints as a gradient, grey for black and white.
 */
private fun swatchOf(preset: Preset): Brush {
    val settings = preset.settings
    val grading = settings.colorGrading
    if (grading.monochrome) {
        val tone = tintColor(grading.global, Color(0xFF8C8C8C))
        return Brush.linearGradient(listOf(Color(0xFF2A2A2A), tone, Color(0xFFE0E0E0)))
    }
    val temperature = settings.manual[ManualControl.TEMPERATURE]
    val base = when {
        temperature > 0.1f -> Color(0xFFC9925A)
        temperature < -0.1f -> Color(0xFF5A86C9)
        else -> Color(0xFF8E8E7E)
    }
    val saturation = (1f + settings.manual[ManualControl.SATURATION] + settings.manual[ManualControl.VIBRANCE]).coerceIn(0.2f, 1.6f)
    val shadows = tintColor(grading.shadows, base.darken(0.45f))
    val highlights = tintColor(grading.highlights, base.lighten(0.35f))
    return Brush.linearGradient(listOf(shadows.desaturate(saturation), base.desaturate(saturation), highlights.desaturate(saturation)))
}

private fun tintColor(wheel: GradeWheel, fallback: Color): Color =
    if (wheel.saturation <= 0f) fallback else Color.hsv(wheel.hue, (0.25f + wheel.saturation * 0.6f).coerceAtMost(1f), 0.75f)

private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)
private fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)
private fun Color.desaturate(saturation: Float): Color {
    val grey = 0.3f * red + 0.59f * green + 0.11f * blue
    fun mix(c: Float) = (grey + (c - grey) * saturation).coerceIn(0f, 1f)
    return Color(mix(red), mix(green), mix(blue), alpha)
}
