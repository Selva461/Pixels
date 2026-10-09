package com.pixels.enhancer.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.ui.ErrorMessages
import com.pixels.enhancer.ui.components.RecentSkeletonRow
import com.pixels.enhancer.ui.editor.RecentProject
import java.text.DateFormat
import java.util.Date

/** Callbacks for the Recent list's per-edit menu. */
class RecentActions(
    val onOpen: (String) -> Unit,
    val onRename: (String, String) -> Unit,
    val onDuplicate: (String) -> Unit,
    val onDelete: (String) -> Unit,
)

@Composable
fun HomeScreen(
    recent: List<RecentProject>,
    recentLoaded: Boolean,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenSettings: () -> Unit,
    actions: RecentActions,
    modifier: Modifier = Modifier,
) {
    val links: @Composable () -> Unit = {
        // Wraps onto a second line with large text instead of squeezing the last link.
        FlowRow(Modifier.padding(vertical = 8.dp)) {
            TextButton(onClick = onOpenGuide) { Text(stringResource(R.string.home_guide)) }
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.home_settings)) }
            TextButton(onClick = onOpenAbout) { Text(stringResource(R.string.home_about)) }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            // Phones on their side: start on the left, recent edits on the right, nothing clipped.
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Intro(onPickImage, onTakePhoto)
                    links()
                }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(top = 32.dp)) {
                    recentItems(recent, recentLoaded, actions)
                }
            }
        } else {
            // One scrolling list, so everything stays reachable with large text on a small screen.
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                item {
                    Column {
                        Intro(onPickImage, onTakePhoto)
                        links()
                        Spacer(Modifier.height(8.dp))
                    }
                }
                recentItems(recent, recentLoaded, actions)
            }
        }
    }
}

@Composable
private fun Intro(onPickImage: () -> Unit, onTakePhoto: () -> Unit) {
    Spacer(Modifier.height(32.dp))
    Text(stringResource(R.string.home_title), style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(24.dp))
    Button(shape = MaterialTheme.shapes.small, onClick = onPickImage, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_pick)) }
    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onTakePhoto, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(R.string.home_take_photo))
    }
}

/** The Recent edits heading and rows (placeholders until the list has loaded). */
private fun LazyListScope.recentItems(recent: List<RecentProject>, recentLoaded: Boolean, actions: RecentActions) {
    when {
        !recentLoaded -> {
            item { RecentHeader(withHint = false) }
            items(SKELETON_ROWS) { RecentSkeletonRow() }
        }
        recent.isNotEmpty() -> {
            item { RecentHeader(withHint = true) }
            items(recent, key = { it.id }) { project -> RecentRow(project, actions) }
        }
        else -> Unit
    }
}

@Composable
private fun RecentHeader(withHint: Boolean) {
    Column {
        Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (withHint) Text(stringResource(R.string.home_recent_hint), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun RecentRow(project: RecentProject, actions: RecentActions) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (renaming) {
        var name by remember { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.home_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(MAX_NAME) }, singleLine = true, label = { Text(stringResource(R.string.name_label)) }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    actions.onRename(project.id, name)
                    renaming = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.home_remove_title, project.name)) },
            text = { Text(stringResource(R.string.home_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDelete(project.id)
                    confirmDelete = false
                }) { Text(stringResource(R.string.home_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.home_open_edit)) { actions.onOpen(project.id) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbnailModifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
        if (project.thumbnail != null) {
            Image(project.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = thumbnailModifier)
        } else {
            Box(thumbnailModifier.background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            // Wraps rather than cutting the name off at large text sizes.
            Text(project.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(project.modifiedAtMillis)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.home_edit_options, project.name))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.home_rename)) }, onClick = { menu = false; renaming = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.home_duplicate)) }, onClick = { menu = false; actions.onDuplicate(project.id) })
                DropdownMenuItem(text = { Text(stringResource(R.string.home_remove)) }, onClick = { menu = false; confirmDelete = true })
            }
        }
    }
}

private const val MAX_NAME = 80
private const val SKELETON_ROWS = 3

/** [progress] null shows an indeterminate spinner. */
@Composable
fun ProgressScreen(message: String, progress: Float?, modifier: Modifier = Modifier) {
    CenteredColumn(modifier) {
        if (progress == null) {
            CircularProgressIndicator()
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(0.6f))
        }
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun ErrorScreen(code: ErrorCode, onPickImage: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    CenteredColumn(modifier) {
        Text(stringResource(ErrorMessages.forCode(code)), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(shape = MaterialTheme.shapes.small, onClick = onPickImage) { Text(stringResource(R.string.error_retry)) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.error_home)) }
    }
}

@Composable
private fun CenteredColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
