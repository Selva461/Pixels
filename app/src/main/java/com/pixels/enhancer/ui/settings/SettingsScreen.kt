package com.pixels.enhancer.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.repository.EnhancerSettings
import com.pixels.enhancer.ui.export.ExportSettingsSections
import java.util.Locale
import kotlin.math.roundToInt

/** The Settings screen's storage figure and its clean-up actions (each re-measures when done). */
class StorageActions(
    val onRefresh: () -> Unit,
    val onDeleteAllEdits: () -> Unit,
    val onDeleteAllPresets: () -> Unit,
    val onClearTemporaryFiles: () -> Unit,
)

private enum class Confirm { EDITS, PRESETS }

@Composable
fun SettingsScreen(
    settings: EnhancerSettings,
    onSettingsChanged: (EnhancerSettings) -> Unit,
    usedBytes: Long?,
    storage: StorageActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    LaunchedEffect(Unit) { storage.onRefresh() }

    confirm?.let { which ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(if (which == Confirm.EDITS) R.string.settings_delete_edits_title else R.string.settings_delete_presets_title)) },
            text = { Text(stringResource(if (which == Confirm.EDITS) R.string.settings_delete_edits_message else R.string.settings_delete_presets_message)) },
            confirmButton = {
                TextButton(onClick = {
                    if (which == Confirm.EDITS) storage.onDeleteAllEdits() else storage.onDeleteAllPresets()
                    confirm = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.about_back)) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

            SectionTitle(stringResource(R.string.settings_editing))
            Text(stringResource(R.string.settings_default_strength, (settings.strength * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium)
            Slider(value = settings.strength, onValueChange = { onSettingsChanged(settings.copy(strength = it)) }, valueRange = 0f..1f)
            Toggle(stringResource(R.string.settings_haptics), settings.hapticFeedback) { onSettingsChanged(settings.copy(hapticFeedback = it)) }
            Toggle(stringResource(R.string.settings_confirm_leave), settings.confirmBeforeLeaving) { onSettingsChanged(settings.copy(confirmBeforeLeaving = it)) }

            SectionTitle(stringResource(R.string.settings_export_defaults))
            ExportSettingsSections(settings.export) { onSettingsChanged(settings.copy(export = it)) }

            SectionTitle(stringResource(R.string.settings_storage))
            Text(
                if (usedBytes == null) stringResource(R.string.settings_storage_measuring) else stringResource(R.string.settings_storage_used, megabytes(usedBytes)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.settings_privacy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = storage.onClearTemporaryFiles, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.settings_clear_temporary))
            }
            OutlinedButton(onClick = { confirm = Confirm.PRESETS }, shape = MaterialTheme.shapes.small) {
                Text(stringResource(R.string.settings_delete_presets))
            }
            OutlinedButton(onClick = { confirm = Confirm.EDITS }, shape = MaterialTheme.shapes.small) {
                Text(stringResource(R.string.settings_delete_edits), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider(Modifier.padding(vertical = 16.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
}

/** A whole-row switch, so the label is part of the touch target and read with the state. */
@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / BYTES_PER_MB)

private const val BYTES_PER_MB = 1024.0 * 1024.0
