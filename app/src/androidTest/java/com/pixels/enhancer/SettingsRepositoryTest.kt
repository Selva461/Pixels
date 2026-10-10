package com.pixels.enhancer

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pixels.enhancer.data.preferences.SharedPreferencesSettingsRepository
import com.pixels.enhancer.domain.export.Border
import com.pixels.enhancer.domain.export.ExportFormat
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.export.ExportSize
import com.pixels.enhancer.domain.export.MetadataPolicy
import com.pixels.enhancer.domain.export.Watermark
import com.pixels.enhancer.domain.export.WatermarkPosition
import com.pixels.enhancer.domain.repository.EnhancerSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun repository() = SharedPreferencesSettingsRepository(context, FILE)

    @Before
    @After
    fun clear() {
        preferences.edit().clear().commit()
    }

    @Test
    fun everyFieldSurvivesSavingAndLoading() {
        val settings = EnhancerSettings(
            strength = 0.3f,
            export = ExportOptions(
                format = ExportFormat.WEBP,
                quality = 80,
                size = ExportSize.MEDIUM,
                metadata = MetadataPolicy.REMOVE_ALL,
                border = Border(Border.MEDIUM, Border.BLACK),
                watermark = Watermark("© Test", WatermarkPosition.TOP_LEFT),
            ),
            confirmBeforeLeaving = false,
            hapticFeedback = false,
            smartEditNewPhotos = false,
        )
        repository().save(settings)
        assertEquals(settings, repository().load())
    }

    @Test
    fun exportChoicesFromOlderVersionsAreMigrated() {
        preferences.edit()
            .putString("export_format", "PNG")
            .putInt("export_quality", 77)
            .putString("export_size", "SMALL")
            .putString("export_metadata", "KEEP_ALL")
            .commit()
        assertEquals(ExportOptions(ExportFormat.PNG, 77, ExportSize.SMALL, MetadataPolicy.KEEP_ALL), repository().load().export)
    }

    @Test
    fun damagedOrWronglyTypedValuesFallBackToDefaults() {
        preferences.edit()
            .putString("strength", "loud")
            .putFloat("preset", 1f)
            .putString("export_options", "{not json")
            .putInt("confirm_before_leaving", 3)
            .putString("haptic_feedback", "yes")
            .commit()
        assertEquals(EnhancerSettings(), repository().load())
    }

    private companion object {
        const val FILE = "settings_repository_test"
    }
}
