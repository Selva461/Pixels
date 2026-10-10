package com.pixels.enhancer.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixels.enhancer.R

/** How each tool works, readable offline. */
private val SECTIONS = listOf(
    R.string.guide_basics_title to R.string.guide_basics_body,
    R.string.guide_presets_title to R.string.guide_presets_body,
    R.string.guide_light_title to R.string.guide_light_body,
    R.string.guide_color_title to R.string.guide_color_body,
    R.string.guide_detail_title to R.string.guide_detail_body,
    R.string.guide_geometry_title to R.string.guide_geometry_body,
    R.string.guide_masking_title to R.string.guide_masking_body,
    R.string.guide_healing_title to R.string.guide_healing_body,
    R.string.guide_saving_title to R.string.guide_saving_body,
)

@Composable
fun GuideScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.about_back)) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.guide_lead), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SECTIONS.forEach { (title, body) ->
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
