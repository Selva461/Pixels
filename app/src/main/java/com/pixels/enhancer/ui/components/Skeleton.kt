package com.pixels.enhancer.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.ui.theme.OnPhotoCanvas
import com.pixels.enhancer.ui.theme.PhotoCanvas

private const val PULSE_MS = 900
private const val PULSE_MIN = 0.45f
private const val PULSE_MAX = 0.9f

/** Placeholder block with a slow, subtle pulse that marks where content will appear while it loads. */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val pulse by transition.animateFloat(
        initialValue = PULSE_MIN,
        targetValue = PULSE_MAX,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(modifier.alpha(pulse).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small))
}

/**
 * The editor's layout in placeholder form, shown while a photo is opened and first enhanced, so
 * the screen doesn't jump when the real editor appears. [progress] null = not started yet.
 */
@Composable
fun EditorSkeleton(message: String, progress: Float?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonBlock(Modifier.size(56.dp, 20.dp))
            Spacer(Modifier.weight(1f))
            SkeletonBlock(Modifier.size(48.dp, 20.dp))
            SkeletonBlock(Modifier.size(64.dp, 20.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(PhotoCanvas), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(0.5f))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.5f))
                }
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnPhotoCanvas,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(3) { SkeletonBlock(Modifier.size(84.dp, 28.dp)) } }
            repeat(3) {
                SkeletonBlock(Modifier.size(120.dp, 14.dp))
                SkeletonBlock(Modifier.fillMaxWidth().height(6.dp))
            }
        }
    }
}

/** Placeholder rows for the Recent edits list. */
@Composable
fun RecentSkeletonRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SkeletonBlock(Modifier.size(64.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(Modifier.fillMaxWidth(0.6f).height(14.dp))
            SkeletonBlock(Modifier.fillMaxWidth(0.4f).height(12.dp))
        }
    }
}
