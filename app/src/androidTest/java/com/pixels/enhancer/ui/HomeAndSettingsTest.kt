package com.pixels.enhancer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pixels.enhancer.R
import com.pixels.enhancer.domain.repository.EnhancerSettings
import com.pixels.enhancer.ui.editor.RecentProject
import com.pixels.enhancer.ui.home.HomeScreen
import com.pixels.enhancer.ui.home.RecentActions
import com.pixels.enhancer.ui.settings.SettingsScreen
import com.pixels.enhancer.ui.settings.StorageActions
import com.pixels.enhancer.ui.theme.PixelsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeAndSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun home(recent: List<RecentProject>, actions: RecentActions, onOpenSettings: () -> Unit = {}) {
        compose.setContent {
            PixelsTheme {
                HomeScreen(
                    recent = recent,
                    recentLoaded = true,
                    onPickImage = {},
                    onTakePhoto = {},
                    onOpenAbout = {},
                    onOpenGuide = {},
                    onOpenSettings = onOpenSettings,
                    actions = actions,
                )
            }
        }
    }

    private val noActions = RecentActions(onOpen = {}, onRename = { _, _ -> }, onDuplicate = {}, onDelete = {})

    @Test
    fun homeOffersBothWaysToStartAndOpensSettings() {
        var settingsOpened = false
        home(emptyList(), noActions) { settingsOpened = true }
        compose.onNodeWithText(string(R.string.home_pick)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.home_take_photo)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.home_settings)).performClick()
        compose.runOnIdle { assertTrue(settingsOpened) }
    }

    @Test
    fun removingARecentEditAsksFirst() {
        val removed = mutableListOf<String>()
        home(listOf(RecentProject("p1", "Beach", 0L, null)), noActions.let { RecentActions(it.onOpen, it.onRename, it.onDuplicate) { id -> removed += id } })
        compose.onNodeWithContentDescription(string(R.string.home_edit_options, "Beach")).performClick()
        compose.onNodeWithText(string(R.string.home_remove)).performClick()
        compose.onNodeWithText(string(R.string.home_remove_title, "Beach")).assertIsDisplayed()
        compose.runOnIdle { assertTrue("nothing is removed before confirming", removed.isEmpty()) }
        compose.onNodeWithText(string(R.string.home_remove)).performClick()
        compose.runOnIdle { assertEquals(listOf("p1"), removed) }
    }

    @Test
    fun settingsToggleAndDeleteAllNeedsConfirmation() {
        var settings by mutableStateOf(EnhancerSettings())
        var deletedEdits = 0
        compose.setContent {
            PixelsTheme {
                SettingsScreen(
                    settings = settings,
                    onSettingsChanged = { settings = it },
                    usedBytes = 3L * 1024 * 1024,
                    storage = StorageActions(onRefresh = {}, onDeleteAllEdits = { deletedEdits++ }, onDeleteAllPresets = {}, onClearTemporaryFiles = {}),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText(string(R.string.settings_storage_used, "3.0")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_haptics)).performScrollTo().performClick()
        compose.runOnIdle { assertFalse(settings.hapticFeedback) }

        compose.onNodeWithText(string(R.string.settings_delete_edits)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, deletedEdits) }
        compose.onNodeWithText(string(R.string.delete)).performClick()
        compose.runOnIdle { assertEquals(1, deletedEdits) }
    }
}
