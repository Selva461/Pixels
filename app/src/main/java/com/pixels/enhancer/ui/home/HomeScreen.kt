package com.pixels.enhancer.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.pixels.enhancer.R
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.ui.ErrorMessages
import com.pixels.enhancer.ui.components.RecentSkeletonRow
import com.pixels.enhancer.ui.editor.RecentProject
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    recent: List<RecentProject>,
    recentLoaded: Boolean,
    onPickImage: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        Button(shape = MaterialTheme.shapes.small, onClick = onPickImage, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_pick)) }
        Spacer(Modifier.height(24.dp))
        when {
            !recentLoaded -> {
                Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                repeat(SKELETON_ROWS) { RecentSkeletonRow() }
                Spacer(Modifier.weight(1f))
            }
            recent.isNotEmpty() -> {
                Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.home_recent_hint), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(recent, key = { it.id }) { project -> RecentRow(project, onOpenProject, onDeleteProject) }
                }
            }
            else -> Spacer(Modifier.weight(1f))
        }
        Row(Modifier.padding(vertical = 8.dp)) {
            TextButton(onClick = onOpenGuide) { Text(stringResource(R.string.home_guide)) }
            TextButton(onClick = onOpenAbout) { Text(stringResource(R.string.home_about)) }
        }
    }
}

@Composable
private fun RecentRow(project: RecentProject, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(project.id) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbnailModifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
        if (project.thumbnail != null) {
            Image(project.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = thumbnailModifier)
        } else {
            Box(thumbnailModifier.background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(project.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(project.modifiedAtMillis)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = { onDelete(project.id) }) { Text(stringResource(R.string.home_remove)) }
    }
}

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
fun ErrorScreen(code: ErrorCode, onPickImage: () -> Unit, modifier: Modifier = Modifier) {
    CenteredColumn(modifier) {
        Text(stringResource(ErrorMessages.forCode(code)), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(shape = MaterialTheme.shapes.small, onClick = onPickImage) { Text(stringResource(R.string.error_retry)) }
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
