package com.pixels.enhancer.data.preferences

import android.content.Context
import com.pixels.enhancer.domain.repository.EnhancerSettings
import com.pixels.enhancer.domain.repository.SettingsRepository

class SharedPreferencesSettingsRepository(context: Context) : SettingsRepository {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun load(): EnhancerSettings {
        val defaults = EnhancerSettings()
        return EnhancerSettings(
            strength = preferences.getFloat(KEY_STRENGTH, defaults.strength),
            presetId = preferences.getString(KEY_PRESET, defaults.presetId) ?: defaults.presetId,
        )
    }

    override fun save(settings: EnhancerSettings) {
        preferences.edit()
            .putFloat(KEY_STRENGTH, settings.strength)
            .putString(KEY_PRESET, settings.presetId)
            .apply()
    }

    private companion object {
        const val FILE_NAME = "enhancer_settings"
        const val KEY_STRENGTH = "strength"
        const val KEY_PRESET = "preset"
    }
}
