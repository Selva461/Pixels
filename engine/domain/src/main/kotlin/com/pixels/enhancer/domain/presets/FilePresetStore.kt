package com.pixels.enhancer.domain.presets

import com.pixels.enhancer.core.logging.EnhancerLogger
import com.pixels.enhancer.core.logging.NoOpLogger
import com.pixels.enhancer.domain.project.EditStateCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** User presets, one JSON file each, written atomically like projects. */
class FilePresetStore(
    private val directory: File,
    private val logger: EnhancerLogger = NoOpLogger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PresetStore {

    override suspend fun list(): List<Preset> = withContext(dispatcher) {
        directory.listFiles { file -> file.name.endsWith(EXTENSION) }.orEmpty().mapNotNull(::readOrNull).sortedBy { it.name.lowercase() }
    }

    override suspend fun save(preset: Preset) = withContext(dispatcher) {
        directory.mkdirs()
        val file = PresetFile(preset.id, preset.name, preset.category, EditStateCodec.encode(PresetMath.settingsOf(preset.settings)))
        val temp = File(directory, "${preset.id}$TEMP_SUFFIX")
        temp.writeText(json.encodeToString(PresetFile.serializer(), file))
        if (!temp.renameTo(fileFor(preset.id))) {
            temp.delete()
            throw IOException("Could not save preset")
        }
    }

    override suspend fun delete(id: String) {
        withContext(dispatcher) { fileFor(id).delete() }
    }

    private fun readOrNull(file: File): Preset? = try {
        val stored = json.decodeFromString(PresetFile.serializer(), file.readText())
        Preset(stored.id, stored.name, stored.category, EditStateCodec.decode(stored.settings), builtIn = false)
    } catch (error: IllegalArgumentException) {
        logger.error("PRESET_UNREADABLE", mapOf("reason" to error.message), error)
        null
    } catch (error: IOException) {
        logger.error("PRESET_UNREADABLE", mapOf("reason" to error.message), error)
        null
    }

    private fun fileFor(id: String): File {
        require(ID_PATTERN.matches(id)) { "Invalid preset id" }
        return File(directory, "$id$EXTENSION")
    }

    @Serializable
    private data class PresetFile(val id: String, val name: String, val category: String, val settings: String)

    private companion object {
        const val EXTENSION = ".preset.json"
        const val TEMP_SUFFIX = ".preset.tmp"
        val ID_PATTERN = Regex("[A-Za-z0-9-]{1,64}")
        val json = Json { ignoreUnknownKeys = true }

    }
}
