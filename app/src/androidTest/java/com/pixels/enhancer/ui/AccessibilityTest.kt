package com.pixels.enhancer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.analysis.Histogram
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.export.Border
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.export.Watermark
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.local.MaskShape
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.presets.PresetLibrary
import com.pixels.enhancer.domain.project.EditVersion
import com.pixels.enhancer.domain.repository.EnhancerSettings
import com.pixels.enhancer.domain.retouch.Retouch
import com.pixels.enhancer.domain.retouch.RetouchSpot
import com.pixels.enhancer.ui.about.AboutScreen
import com.pixels.enhancer.ui.editor.AppliedPreset
import com.pixels.enhancer.ui.editor.BatchProgress
import com.pixels.enhancer.ui.editor.CropAspect
import com.pixels.enhancer.ui.editor.EditorActivity
import com.pixels.enhancer.ui.editor.EditorScreen
import com.pixels.enhancer.ui.editor.EditorTool
import com.pixels.enhancer.ui.editor.EditorUiState
import com.pixels.enhancer.ui.editor.ExportDialogState
import com.pixels.enhancer.ui.editor.RecentProject
import com.pixels.enhancer.ui.export.ExportSettingsSections
import com.pixels.enhancer.ui.guide.GuideScreen
import com.pixels.enhancer.ui.home.HomeScreen
import com.pixels.enhancer.ui.home.RecentActions
import com.pixels.enhancer.ui.settings.SettingsScreen
import com.pixels.enhancer.ui.settings.StorageActions
import com.pixels.enhancer.ui.theme.PixelsTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Release checks for FEATURE_SPEC A-01, A-04 and A-05 on every screen and editor tool, laid out at
 * the size of a small phone (320 × 560 dp):
 * - every control has a name a screen reader can speak;
 * - every tap target is at least 48 × 48 dp;
 * - with text at 200 per cent, no text is cut off or ellipsised.
 *
 * Each test collects every problem before failing, so one run lists them all.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private val recorder = RecordingActions()

    private enum class Page { HOME, SETTINGS, GUIDE, ABOUT, EXPORT_OPTIONS }

    /** A small phone: fixed size, chosen text scale, the app theme. */
    @Composable
    private fun Phone(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            PixelsTheme {
                Surface(Modifier.requiredSize(PHONE_WIDTH, PHONE_HEIGHT), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }

    @Composable
    private fun PageContent(page: Page) {
        when (page) {
            Page.HOME -> HomeScreen(
                recent = listOf(
                    RecentProject("p1", "Beach at dusk", 1_700_000_000_000L, null),
                    RecentProject("p2", "Mountain lake", 1_700_100_000_000L, null),
                ),
                recentLoaded = true,
                onPickImage = {},
                onTakePhoto = {},
                onOpenAbout = {},
                onOpenGuide = {},
                onOpenSettings = {},
                actions = RecentActions(onOpen = {}, onRename = { _, _ -> }, onDuplicate = {}, onDelete = {}),
            )
            Page.SETTINGS -> SettingsScreen(
                settings = EnhancerSettings(),
                onSettingsChanged = {},
                usedBytes = 3L * 1024 * 1024,
                storage = StorageActions(onRefresh = {}, onDeleteAllEdits = {}, onDeleteAllPresets = {}, onClearTemporaryFiles = {}),
                onBack = {},
            )
            Page.GUIDE -> GuideScreen(onBack = {})
            Page.ABOUT -> AboutScreen(onBack = {})
            // The export dialog's body, inline: dialogs open in their own window, outside the 200 % text scale.
            Page.EXPORT_OPTIONS -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                ExportSettingsSections(EXPORT_OPTIONS) {}
            }
        }
    }

    /** An editor state that fills every panel: edits, masks, spots, history, versions, presets. */
    private fun editorState(): EditorUiState.Success {
        val mask = LocalAdjustment(id = 1, shape = MaskShape.Linear(0.5f, 0.1f, 0.5f, 0.4f), exposure = -0.3f, name = "Sky")
        val edit = EditState(
            manual = ManualAdjustments.NONE.with(ManualControl.EXPOSURE, 0.35f).with(ManualControl.CONTRAST, 0.12f),
            localAdjustments = LocalAdjustments(listOf(mask, LocalAdjustment(id = 2, shape = MaskShape.Full))),
            retouch = Retouch(listOf(RetouchSpot(id = 1, targetX = 0.3f, targetY = 0.6f, sourceX = 0.4f, sourceY = 0.6f))),
        )
        val bins = IntArray(Histogram.DEFAULT_BINS) { it * (Histogram.DEFAULT_BINS - it) }
        return EditorUiState.Success(
            original = ImageBitmap(160, 120),
            enhanced = ImageBitmap(160, 120),
            edit = edit,
            canUndo = true,
            canRedo = false,
            cropMode = false,
            cropAspect = CropAspect.FREE,
            histogram = Histogram(bins, bins, bins, bins),
            activity = EditorActivity.None,
            debug = null,
            appliedPreset = AppliedPreset(PresetLibrary.ALL.first(), 1f),
            versions = listOf(EditVersion("Warmer", 1_700_000_000_000L, edit)),
            selectedMaskId = 1,
            selectedSpotId = 1,
            historyLabels = listOf("Opened", "Exposure", "Contrast +12", "Sky mask"),
            historyPosition = 2,
        )
    }

    @Test
    fun everyScreenHasNamedControlsAndLargeTargets() {
        var page by mutableStateOf(Page.HOME)
        compose.setContent { Phone(fontScale = 1f) { key(page) { PageContent(page) } } }
        val problems = mutableListOf<String>()
        Page.entries.forEach { next ->
            compose.runOnIdle { page = next }
            compose.waitForIdle()
            problems += controlProblems(next.name.lowercase(Locale.ROOT))
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun everyScreenFitsTextAt200Percent() {
        var page by mutableStateOf(Page.HOME)
        compose.setContent { Phone(fontScale = LARGE_TEXT) { key(page) { PageContent(page) } } }
        val problems = mutableListOf<String>()
        Page.entries.forEach { next ->
            compose.runOnIdle { page = next }
            compose.waitForIdle()
            problems += textProblems(next.name.lowercase(Locale.ROOT))
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun everyEditorViewHasNamedControlsAndLargeTargets() {
        val problems = eachEditorView(fontScale = 1f) { controlProblems(it) }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun everyEditorViewFitsTextAt200Percent() {
        val problems = eachEditorView(fontScale = LARGE_TEXT) { textProblems(it) }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Opens every tool, and every sub-view of the tools that have them, and runs [check] on each. */
    private fun eachEditorView(fontScale: Float, check: (String) -> List<String>): List<String> {
        val state = editorState()
        var tool by mutableStateOf(EditorTool.PRESETS)
        compose.setContent { Phone(fontScale) { key(tool) { EditorScreen(state, recorder.actions, null, {}, initialTool = tool) } } }
        val problems = mutableListOf<String>()
        EditorTool.entries.forEach { next ->
            compose.runOnIdle { tool = next }
            compose.waitForIdle()
            val where = "editor ${next.name.lowercase(Locale.ROOT)}"
            problems += check(where)
            SUB_VIEWS[next].orEmpty().forEach { view ->
                compose.onNodeWithText(string(view)).performScrollTo().performClick()
                compose.waitForIdle()
                problems += check("$where, ${string(view)}")
            }
        }
        return problems
    }

    @Test
    fun editorDialogsHaveNamedControlsAndLargeTargets() {
        val base = editorState()
        val dialogs = listOf(
            "leave" to base.copy(confirmLeave = true),
            "export" to base.copy(exportDialog = ExportDialogState(EXPORT_OPTIONS, 4032, 3024)),
            "batch running" to base.copy(batch = BatchProgress(total = 4, done = 1, saved = 1, failed = 0, running = true)),
            "batch summary" to base.copy(batch = BatchProgress(total = 4, done = 4, saved = 3, failed = 1, running = false)),
        )
        var current by mutableStateOf(dialogs.first().second)
        compose.setContent { Phone(fontScale = 1f) { EditorScreen(current, recorder.actions, null, {}) } }
        val problems = mutableListOf<String>()
        dialogs.forEach { (name, state) ->
            compose.runOnIdle { current = state }
            compose.waitForIdle()
            problems += controlProblems("dialog $name")
        }
        // The settings-groups dialog is opened from the More menu.
        compose.runOnIdle { current = base }
        compose.onNodeWithContentDescription(string(R.string.editor_more)).performClick()
        problems += controlProblems("more menu")
        compose.onNodeWithText(string(R.string.menu_apply_to_others)).performClick()
        compose.waitForIdle()
        problems += controlProblems("dialog settings groups")
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Controls without a spoken name, and tap targets smaller than 48 × 48 dp. */
    private fun controlProblems(where: String): List<String> {
        val minimum = with(compose.density) { MIN_TARGET.toPx() } - 1f
        val controls = compose.onAllNodes(hasClickAction() or SET_PROGRESS or isToggleable()).fetchSemanticsNodes()
        val unnamed = controls.filter { spokenName(it).isBlank() }.map { "$where: no spoken name: ${it.config}" }
        val small = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.size.width > 0 && it.size.height > 0 }
            .filter { it.size.width < minimum || it.size.height < minimum }
            .map { node ->
                val (w, h) = with(compose.density) { node.size.width.toDp().value to node.size.height.toDp().value }
                String.format(Locale.ROOT, "%s: \"%s\" is %.0f × %.0f dp", where, spokenName(node), w, h)
            }
        return unnamed + small
    }

    /** Texts whose layout is cut off or ellipsised. */
    private fun textProblems(where: String): List<String> {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        val problems = mutableListOf<String>()
        compose.runOnUiThread {
            nodes.forEach { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                val layout = results.firstOrNull() ?: return@forEach
                if (layout.hasVisualOverflow) problems += "$where: text cut off at 200 %: \"${layout.layoutInput.text}\""
            }
        }
        return problems
    }

    private fun spokenName(node: SemanticsNode): String {
        val config = node.config
        val parts = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text)
        return parts.joinToString(" ").trim()
    }

    private companion object {
        val PHONE_WIDTH = 320.dp
        val PHONE_HEIGHT = 560.dp
        val MIN_TARGET = 48.dp
        const val LARGE_TEXT = 2f
        val SET_PROGRESS = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

        /** Tools whose panel switches between views; the first view is shown when the tool opens. */
        val SUB_VIEWS = mapOf(
            EditorTool.LIGHT to listOf(R.string.panel_curve),
            EditorTool.COLOR to listOf(R.string.panel_mixer, R.string.panel_grading, R.string.panel_calibration),
            EditorTool.HISTORY to listOf(R.string.versions_title),
        )
        val EXPORT_OPTIONS = ExportOptions(border = Border(widthFraction = Border.MEDIUM), watermark = Watermark(text = "© Pixels"))
    }
}
