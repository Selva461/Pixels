package com.pixels.enhancer.data.preferences

import android.content.Context
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.planning.EnhancementStrength
import com.pixels.enhancer.domain.project.ExportOptionsCodec
import com.pixels.enhancer.domain.repository.EnhancerSettings
import com.pixels.enhancer.domain.repository.SettingsRepository

/**
 * Settings in app-private SharedPreferences. Export choices are stored as one JSON value (the
 * same codec as project files); older installs that stored them as separate keys are migrated on
 * first read. Unknown, damaged or wrongly typed values fall back to defaults instead of crashing.
 */
class SharedPreferencesSettingsRepository(context: Context, fileName: String = FILE_NAME) : SettingsRepository {
    private val preferences = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override fun load(): EnhancerSettings {
        val defaults = EnhancerSettings()
        return EnhancerSettings(
            strength = float(KEY_STRENGTH, defaults.strength).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: EnhancementStrength.DEFAULT,
            presetId = string(KEY_PRESET) ?: defaults.presetId,
            export = loadExport(),
            confirmBeforeLeaving = boolean(KEY_CONFIRM_LEAVE, defaults.confirmBeforeLeaving),
            hapticFeedback = boolean(KEY_HAPTICS, defaults.hapticFeedback),
            smartEditNewPhotos = boolean(KEY_SMART_EDIT, defaults.smartEditNewPhotos),
        )
    }

    override fun save(settings: EnhancerSettings) {
        preferences.edit()
            .putFloat(KEY_STRENGTH, settings.strength)
            .putString(KEY_PRESET, settings.presetId)
            .putString(KEY_EXPORT_JSON, ExportOptionsCodec.encode(settings.export))
            .putBoolean(KEY_CONFIRM_LEAVE, settings.confirmBeforeLeaving)
            .putBoolean(KEY_HAPTICS, settings.hapticFeedback)
            .putBoolean(KEY_SMART_EDIT, settings.smartEditNewPhotos)
            .remove(LEGACY_FORMAT)
            .remove(LEGACY_QUALITY)
            .remove(LEGACY_SIZE)
            .remove(LEGACY_METADATA)
            .apply()
    }

    private fun loadExport(): ExportOptions {
        string(KEY_EXPORT_JSON)?.let { return ExportOptionsCodec.decode(it) }
        val defaults = ExportOptions()
        return ExportOptions(
            format = enumOrDefault(string(LEGACY_FORMAT), defaults.format),
            quality = int(LEGACY_QUALITY, defaults.quality).coerceIn(ExportOptions.MIN_QUALITY, ExportOptions.MAX_QUALITY),
            size = enumOrDefault(string(LEGACY_SIZE), defaults.size),
            metadata = enumOrDefault(string(LEGACY_METADATA), defaults.metadata),
        )
    }

    private fun float(key: String, default: Float) = typed(default) { preferences.getFloat(key, default) }

    private fun int(key: String, default: Int) = typed(default) { preferences.getInt(key, default) }

    private fun boolean(key: String, default: Boolean) = typed(default) { preferences.getBoolean(key, default) }

    private fun string(key: String): String? = typed(null) { preferences.getString(key, null) }

    /** A value stored with another type (a damaged or hand-edited file) reads as the default. */
    private inline fun <T> typed(default: T, read: () -> T): T = try {
        read()
    } catch (_: ClassCastException) {
        default
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    companion object {
        const val FILE_NAME = "enhancer_settings"
        private const val KEY_STRENGTH = "strength"
        private const val KEY_PRESET = "preset"
        private const val KEY_EXPORT_JSON = "export_options"
        private const val KEY_CONFIRM_LEAVE = "confirm_before_leaving"
        private const val KEY_HAPTICS = "haptic_feedback"
        private const val KEY_SMART_EDIT = "smart_edit_new_photos"
        private const val LEGACY_FORMAT = "export_format"
        private const val LEGACY_QUALITY = "export_quality"
        private const val LEGACY_SIZE = "export_size"
        private const val LEGACY_METADATA = "export_metadata"
    }
}
