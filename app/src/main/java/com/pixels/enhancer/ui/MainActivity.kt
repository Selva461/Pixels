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
import com.pixels.enhancer.ui.about.AboutScreen
import com.pixels.enhancer.ui.components.EditorSkeleton
import com.pixels.enhancer.ui.debug.DebugScreen
import com.pixels.enhancer.ui.editor.ColorActions
import com.pixels.enhancer.ui.editor.CropActions
import com.pixels.enhancer.ui.editor.ExportActions
import com.pixels.enhancer.ui.editor.EditorEvent
import com.pixels.enhancer.ui.editor.EditorScreen
import com.pixels.enhancer.ui.editor.EditorUiState
import com.pixels.enhancer.ui.editor.EditorViewModel
import com.pixels.enhancer.ui.home.ErrorScreen
import com.pixels.enhancer.ui.home.HomeScreen
import com.pixels.enhancer.ui.theme.PixelsTheme

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels {
        EditorViewModel.factory((application as PixelsApplication).container)
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            keepAccess(uri)
            viewModel.onImagePicked(uri)
        }
    }

    /**
     * Projects reopen the original later, so ask to keep read access across restarts. Not every
     * provider allows it; then the project still works until the app is closed.
     */
    private fun keepAccess(uri: android.net.Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Access stays valid for this session; reopening after a restart may ask to pick again.
        }
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
    var showAbout by rememberSaveable { mutableStateOf(false) }
    ShareEvents(viewModel)

    when (val current = state) {
        is EditorUiState.Idle -> if (showAbout) {
            AboutScreen(onBack = { showAbout = false }, modifier = modifier)
        } else {
            HomeScreen(
                recent = current.recent,
                recentLoaded = current.recentLoaded,
                onPickImage = onPickImage,
                onOpenAbout = { showAbout = true },
                onOpenProject = viewModel::onOpenProject,
                onDeleteProject = viewModel::onDeleteProject,
                modifier = modifier,
            )
        }
        EditorUiState.Loading -> EditorSkeleton(stringResource(R.string.loading_analysing), progress = null, modifier = modifier)
        is EditorUiState.Processing -> EditorSkeleton(stringResource(R.string.processing_enhancing), progress = current.progress, modifier = modifier)
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
                    onClose = viewModel::onCloseRequested,
                    onConfirmLeave = viewModel::onClose,
                    onDismissLeave = viewModel::onLeaveDismissed,
                    onRedo = viewModel::onRedo,
                    onShowOriginalEdit = viewModel::onShowOriginalEdit,
                    onStrengthChanged = viewModel::onStrengthChanged,
                    onControlChanged = viewModel::onControlChanged,
                    onEditFinished = viewModel::onEditFinished,
                    onLookSelected = viewModel::onLookSelected,
                    onResetControl = viewModel::onResetControl,
                    onResetAll = viewModel::onResetAll,
                    onUndo = viewModel::onUndo,
                    onSave = viewModel::onSave,
                    onShare = viewModel::onShare,
                    onViewSaved = viewModel::onViewSaved,
                    crop = CropActions(
                        onRotateClockwise = viewModel::onRotateClockwise,
                        onRotateCounterClockwise = viewModel::onRotateCounterClockwise,
                        onFlip = viewModel::onFlip,
                        onStraightenChanged = viewModel::onStraightenChanged,
                        onCropChanged = viewModel::onCropChanged,
                        onAspectSelected = viewModel::onCropAspectSelected,
                        onReset = viewModel::onResetGeometry,
                        onCropModeChanged = viewModel::onCropModeChanged,
                        ratioFor = viewModel::aspectRatioFor,
                    ),
                    color = ColorActions(
                        onShiftChanged = viewModel::onColorMixerChanged,
                        onResetAll = viewModel::onResetColorMixer,
                        onSceneSelected = viewModel::onSceneSelected,
                    ),
                    export = ExportActions(
                        onOptionsChanged = viewModel::onExportOptionsChanged,
                        onConfirm = viewModel::onExportConfirmed,
                        onDismiss = viewModel::onExportDismissed,
                        onCancel = viewModel::onCancelExport,
                    ),
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
                is EditorEvent.ViewImage -> Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(event.uri, "image/jpeg")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                is EditorEvent.ShareText -> Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, event.text)
                    },
                    reportChooserTitle,
                )
            }
            // No gallery/viewer installed is not worth crashing over.
            runCatching { context.startActivity(intent) }
        }
    }
}
