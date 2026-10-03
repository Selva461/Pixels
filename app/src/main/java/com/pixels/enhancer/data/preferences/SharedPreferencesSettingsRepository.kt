package com.pixels.enhancer.data.preferences

import android.content.Context
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.repository.EnhancerSettings
import com.pixels.enhancer.domain.repository.SettingsRepository

class SharedPreferencesSettingsRepository(context: Context) : SettingsRepository {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun load(): EnhancerSettings {
        val defaults = EnhancerSettings()
        return EnhancerSettings(
            strength = preferences.getFloat(KEY_STRENGTH, defaults.strength),
            presetId = preferences.getString(KEY_PRESET, defaults.presetId) ?: defaults.presetId,
            export = loadExport(defaults.export),
        )
    }

    override fun save(settings: EnhancerSettings) {
        preferences.edit()
            .putFloat(KEY_STRENGTH, settings.strength)
            .putString(KEY_PRESET, settings.presetId)
            .putString(KEY_EXPORT_FORMAT, settings.export.format.name)
            .putInt(KEY_EXPORT_QUALITY, settings.export.quality)
            .putString(KEY_EXPORT_SIZE, settings.export.size.name)
            .putString(KEY_EXPORT_METADATA, settings.export.metadata.name)
            .apply()
    }

    /** Unknown or out-of-range stored values fall back to defaults instead of crashing. */
    private fun loadExport(defaults: ExportOptions) = ExportOptions(
        format = enumOrDefault(preferences.getString(KEY_EXPORT_FORMAT, null), defaults.format),
        quality = preferences.getInt(KEY_EXPORT_QUALITY, defaults.quality).coerceIn(ExportOptions.MIN_QUALITY, ExportOptions.MAX_QUALITY),
        size = enumOrDefault(preferences.getString(KEY_EXPORT_SIZE, null), defaults.size),
        metadata = enumOrDefault(preferences.getString(KEY_EXPORT_METADATA, null), defaults.metadata),
    )

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val FILE_NAME = "enhancer_settings"
        const val KEY_STRENGTH = "strength"
        const val KEY_PRESET = "preset"
        const val KEY_EXPORT_FORMAT = "export_format"
        const val KEY_EXPORT_QUALITY = "export_quality"
        const val KEY_EXPORT_SIZE = "export_size"
        const val KEY_EXPORT_METADATA = "export_metadata"
    }
}

