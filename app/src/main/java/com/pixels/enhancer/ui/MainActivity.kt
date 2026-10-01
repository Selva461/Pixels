package com.pixels.enhancer.ui

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixels.enhancer.PixelsApplication
import com.pixels.enhancer.R
import com.pixels.enhancer.ui.debug.DebugScreen
import com.pixels.enhancer.ui.editor.EditorEvent
import com.pixels.enhancer.ui.editor.EditorScreen
import com.pixels.enhancer.ui.editor.EditorUiState
import com.pixels.enhancer.ui.editor.EditorViewModel
import com.pixels.enhancer.ui.home.ErrorScreen
import com.pixels.enhancer.ui.home.HomeScreen
import com.pixels.enhancer.ui.home.ProgressScreen
import com.pixels.enhancer.ui.theme.PixelsTheme

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels {
        EditorViewModel.factory((application as PixelsApplication).container)
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onImagePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PixelsTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PixelsApp(viewModel, onPickImage = { launchPicker() }, modifier = Modifier.safeDrawingPadding())
                }
            }
        }
    }

    private fun launchPicker() {
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

@Composable
private fun PixelsApp(viewModel: EditorViewModel, onPickImage: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDebug by rememberSaveable { mutableStateOf(false) }
    ShareEvents(viewModel)

    when (val current = state) {
        EditorUiState.Idle -> HomeScreen(onPickImage, modifier)
        EditorUiState.Loading -> ProgressScreen(stringResource(R.string.loading_opening), progress = null, modifier = modifier)
        is EditorUiState.Processing -> ProgressScreen(
            stringResource(R.string.processing_stage, current.stageName),
            progress = current.progress,
            modifier = modifier,
        )
        is EditorUiState.Error -> ErrorScreen(current.code, onPickImage, modifier)
        is EditorUiState.Success -> {
            val debug = current.debug
            if (showDebug && debug != null) {
                DebugScreen(
                    debug = debug,
                    onBack = { showDebug = false },
                    onStageToggled = viewModel::onStageToggled,
                    onRunUntilSelected = viewModel::onRunUntilSelected,
                    onExport = viewModel::onExportDebugReport,
                    modifier = modifier,
                )
            } else {
                EditorScreen(
                    state = current,
                    onClose = viewModel::onClose,
                    onStrengthChanged = viewModel::onStrengthChanged,
                    onStrengthChangeFinished = viewModel::onStrengthChangeFinished,
                    onUndo = viewModel::onUndo,
                    onReset = viewModel::onReset,
                    onSave = viewModel::onSave,
                    onShare = viewModel::onShare,
                    onOpenDebug = if (debug != null) ({ showDebug = true }) else null,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun ShareEvents(viewModel: EditorViewModel) {
    val context = LocalContext.current
    val imageChooserTitle = stringResource(R.string.share_chooser)
    val reportChooserTitle = stringResource(R.string.share_report_chooser)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val intent = when (event) {
                is EditorEvent.ShareImage -> Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        clipData = ClipData.newRawUri(null, event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    imageChooserTitle,
                )
                is EditorEvent.ShareText -> Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, event.text)
                    },
                    reportChooserTitle,
                )
            }
            context.startActivity(intent)
        }
    }
}
