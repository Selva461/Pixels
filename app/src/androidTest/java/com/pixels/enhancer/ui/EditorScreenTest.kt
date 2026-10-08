package com.pixels.enhancer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.ui.editor.BatchProgress
import com.pixels.enhancer.ui.editor.CropAspect
import com.pixels.enhancer.ui.editor.EditorActivity
import com.pixels.enhancer.ui.editor.EditorScreen
import com.pixels.enhancer.ui.editor.EditorTool
import com.pixels.enhancer.ui.editor.EditorUiState
import com.pixels.enhancer.ui.editor.toolTag
import com.pixels.enhancer.ui.panels.PanelReset
import com.pixels.enhancer.ui.theme.PixelsTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The editor screen against a recording fake: every control reaches the right action. */
@RunWith(AndroidJUnit4::class)
class EditorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val recorder = RecordingActions()

    private fun state(edit: EditState = EditState()) = EditorUiState.Success(
        original = ImageBitmap(160, 120),
        enhanced = ImageBitmap(160, 120),
        edit = edit,
        canUndo = true,
        canRedo = false,
        cropMode = false,
        cropAspect = CropAspect.FREE,
        activity = EditorActivity.None,
        debug = null,
    )

    private fun show(state: EditorUiState.Success = state()) {
        compose.setContent {
            PixelsTheme { EditorScreen(state, recorder.actions, onOpenDebug = null, onPickBatchPhotos = {}) }
        }
    }

    private fun openTool(tool: EditorTool) {
        compose.onNodeWithTag(toolTag(tool)).performScrollTo().performClick()
    }

    @Test
    fun everyToolOpensItsPanel() {
        show()
        EditorTool.entries.forEach { tool ->
            openTool(tool)
            compose.onNodeWithTag(toolTag(tool)).assertIsSelected()
        }
        assertTrue("crop mode follows the Crop tool", recorder.called("onCropModeChanged", true))
    }

    @Test
    fun sliderChangesReachTheEditAndFinishOneUndoStep() {
        show()
        openTool(EditorTool.LIGHT)
        compose.onNodeWithContentDescription(ManualControl.EXPOSURE.label)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.runOnIdle {
            assertTrue(recorder.called("onControlChanged", ManualControl.EXPOSURE, 0.5f))
            assertTrue(recorder.called("onEditFinished"))
        }
    }

    @Test
    fun topBarButtonsCallTheirActions() {
        show()
        compose.onNodeWithContentDescription(string(R.string.editor_redo)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(string(R.string.editor_undo)).performClick()
        compose.onNodeWithText(string(R.string.editor_save)).performClick()
        compose.onNodeWithContentDescription(string(R.string.editor_close)).performClick()
        compose.runOnIdle {
            assertTrue(recorder.called("onUndo"))
            assertTrue(recorder.called("onSave"))
            assertTrue(recorder.called("onCloseRequested"))
        }
    }

    @Test
    fun panelResetIsOfferedOnlyWhenThePanelIsEdited() {
        show(state(EditState(manual = ManualAdjustments.NONE.with(ManualControl.CLARITY, 0.3f))))
        openTool(EditorTool.DETAIL)
        compose.onNodeWithText(string(R.string.panel_reset)).performScrollTo().assertIsNotEnabled()
        openTool(EditorTool.EFFECTS)
        compose.onNodeWithText(string(R.string.panel_reset)).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(recorder.called("onResetPanel", PanelReset.EFFECTS))
            assertFalse(recorder.called("onResetPanel", PanelReset.DETAIL))
        }
    }

    @Test
    fun historyListsEveryStepAndJumpsBack() {
        show(state().copy(historyLabels = listOf("Opened", "Exposure", "Contrast +2"), historyPosition = 2))
        openTool(EditorTool.HISTORY)
        compose.onNodeWithText("3. Contrast +2").assertIsDisplayed()
        compose.onNodeWithText("2. Exposure").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(recorder.called("onJumpToHistory", 1)) }
    }

    @Test
    fun batchSummaryReportsCountsAndDismisses() {
        show(state().copy(batch = BatchProgress(total = 3, done = 3, saved = 2, failed = 1, running = false)))
        compose.onNodeWithText(string(R.string.batch_summary, 2, 1)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.batch_ok)).performClick()
        compose.runOnIdle { assertTrue(recorder.called("onDismissBatch")) }
    }

    @Test
    fun runningBatchCanBeCancelled() {
        show(state().copy(batch = BatchProgress(total = 4, done = 1, saved = 1, failed = 0, running = true)))
        compose.onNodeWithText(plural(R.plurals.batch_progress, 4, 1, 4)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.cancel)).performClick()
        compose.runOnIdle { assertTrue(recorder.called("onCancelBatch")) }
    }
}
