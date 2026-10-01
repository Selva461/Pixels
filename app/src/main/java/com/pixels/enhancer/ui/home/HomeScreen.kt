package com.pixels.enhancer.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.ui.ErrorMessages

@Composable
fun HomeScreen(onPickImage: () -> Unit, modifier: Modifier = Modifier) {
    CenteredColumn(modifier) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onPickImage) { Text(stringResource(R.string.home_pick)) }
    }
}

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
        Button(onClick = onPickImage) { Text(stringResource(R.string.error_retry)) }
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
