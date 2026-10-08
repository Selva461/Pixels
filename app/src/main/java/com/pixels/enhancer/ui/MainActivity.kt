package com.pixels.enhancer.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixels.enhancer.PixelsApplication
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.usecase.BatchExportUseCase
import com.pixels.enhancer.ui.about.AboutScreen
import com.pixels.enhancer.ui.components.EditorSkeleton
import com.pixels.enhancer.ui.components.LocalHapticsEnabled
import com.pixels.enhancer.ui.debug.DebugScreen
import com.pixels.enhancer.ui.editor.EditorEvent
import com.pixels.enhancer.ui.editor.EditorScreen
import com.pixels.enhancer.ui.editor.EditorUiState
import com.pixels.enhancer.ui.editor.EditorViewModel
import com.pixels.enhancer.ui.guide.GuideScreen
import com.pixels.enhancer.ui.home.ErrorScreen
import com.pixels.enhancer.ui.home.HomeScreen
import com.pixels.enhancer.ui.home.RecentActions
import com.pixels.enhancer.ui.settings.SettingsScreen
import com.pixels.enhancer.ui.settings.StorageActions
import com.pixels.enhancer.ui.theme.PixelsTheme

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels {
        EditorViewModel.factory((application as PixelsApplication).container)
    }

    private val incomingImages get() = (application as PixelsApplication).container.incomingImages

    /** Where the camera app is writing; kept across process death while the camera is open. */
    private var pendingCapture: Uri? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            keepAccess(uri)
            viewModel.onImagePicked(uri)
        }
    }

    private val pickBatch = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(BatchExportUseCase.MAX_ITEMS)) { uris ->
        uris.forEach(::keepAccess)
        viewModel.onBatchPhotosPicked(uris)
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCapture ?: return@registerForActivityResult
        pendingCapture = null
        if (saved) viewModel.onCaptured(uri) else viewModel.onCaptureCancelled(uri)
    }

    /**
     * Projects reopen the original later, so ask to keep read access across restarts. Not every
     * provider allows it; then the project still works until the app is closed.
     */
    private fun keepAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Access stays valid for this session; reopening after a restart may ask to pick again.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCapture = savedInstanceState?.let { readUri(it) }
        // The app is always dark, so system bar icons are always light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // A shared photo is handled once; after rotation the view model already has it.
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            val settings by viewModel.settingsState.collectAsStateWithLifecycle()
            PixelsTheme {
                CompositionLocalProvider(LocalHapticsEnabled provides settings.hapticFeedback) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        PixelsApp(
                            viewModel = viewModel,
                            launchers = Launchers(
                                pickImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                takePhoto = ::launchCamera,
                                pickBatch = { pickBatch.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            ),
                            modifier = Modifier.safeDrawingPadding(),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCapture?.let { outState.putParcelable(KEY_PENDING_CAPTURE, it) }
    }

    private fun readUri(bundle: Bundle): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelable(KEY_PENDING_CAPTURE, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            bundle.getParcelable(KEY_PENDING_CAPTURE)
        }

    private fun launchCamera() {
        val uri = incomingImages.newCaptureUri()
        pendingCapture = uri
        try {
            takePicture.launch(uri)
        } catch (_: ActivityNotFoundException) {
            pendingCapture = null
            incomingImages.discardCapture(uri)
            Toast.makeText(this, R.string.error_no_camera, Toast.LENGTH_LONG).show()
        }
    }

    /** "Share to Pixels" and "Edit with Pixels" from other apps; anything else is ignored. */
    private fun handleIncoming(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_EDIT -> intent.data
            else -> null
        } ?: return
        viewModel.onImportShared(uri)
    }

    private companion object {
        const val KEY_PENDING_CAPTURE = "pending_capture"
    }
}

/** Activity-owned launchers the screens trigger. */
class Launchers(val pickImage: () -> Unit, val takePhoto: () -> Unit, val pickBatch: () -> Unit)

private enum class Overlay { NONE, ABOUT, GUIDE, SETTINGS }

@Composable
private fun PixelsApp(viewModel: EditorViewModel, launchers: Launchers, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val storageUsed by viewModel.storageUsed.collectAsStateWithLifecycle()
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
    ShareEvents(viewModel)

    when (val current = state) {
        is EditorUiState.Idle -> when (overlay) {
            Overlay.ABOUT -> AboutScreen(onBack = { overlay = Overlay.NONE }, modifier = modifier)
            Overlay.GUIDE -> GuideScreen(onBack = { overlay = Overlay.NONE }, modifier = modifier)
            Overlay.SETTINGS -> SettingsScreen(
                settings = settings,
                onSettingsChanged = viewModel::onSettingsChanged,
                usedBytes = storageUsed,
                storage = StorageActions(
                    onRefresh = viewModel::onRefreshStorage,
                    onDeleteAllEdits = viewModel::onDeleteAllEdits,
                    onDeleteAllPresets = viewModel::onDeleteAllPresets,
                    onClearTemporaryFiles = viewModel::onClearTemporaryFiles,
                ),
                onBack = {
                    overlay = Overlay.NONE
                    viewModel.onHomeShown()
                },
                modifier = modifier,
            )
            Overlay.NONE -> HomeScreen(
                recent = current.recent,
                recentLoaded = current.recentLoaded,
                onPickImage = launchers.pickImage,
                onTakePhoto = launchers.takePhoto,
                onOpenAbout = { overlay = Overlay.ABOUT },
                onOpenGuide = { overlay = Overlay.GUIDE },
                onOpenSettings = { overlay = Overlay.SETTINGS },
                actions = RecentActions(
                    onOpen = viewModel::onOpenProject,
                    onRename = viewModel::onRenameProject,
                    onDuplicate = viewModel::onDuplicateProject,
                    onDelete = viewModel::onDeleteProject,
                ),
                modifier = modifier,
            )
        }
        EditorUiState.Loading -> EditorSkeleton(stringResource(R.string.loading_analysing), progress = null, modifier = modifier)
        is EditorUiState.Processing -> EditorSkeleton(stringResource(R.string.processing_enhancing), progress = current.progress, modifier = modifier)
        is EditorUiState.Error -> ErrorScreen(current.code, launchers.pickImage, onBack = viewModel::onClose, modifier = modifier)
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
                    actions = viewModel,
                    onOpenDebug = if (debug != null) ({ showDebug = true }) else null,
                    onPickBatchPhotos = launchers.pickBatch,
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
                    setDataAndType(event.uri, "image/*")
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
