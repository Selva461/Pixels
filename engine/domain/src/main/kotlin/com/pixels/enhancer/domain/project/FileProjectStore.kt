package com.pixels.enhancer.domain.project

import com.pixels.enhancer.core.logging.EnhancerLogger
import com.pixels.enhancer.core.logging.NoOpLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * One JSON file per project in [directory]. Writes go to a temp file that is then renamed over the
 * old one, so a crash mid-write leaves the previous version intact — never a half-written project.
 */
class FileProjectStore(
    private val directory: File,
    private val logger: EnhancerLogger = NoOpLogger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProjectStore {

    override suspend fun save(project: Project) = withContext(dispatcher) {
        directory.mkdirs()
        val target = fileFor(project.id)
        val temp = File(directory, "${project.id}$TEMP_SUFFIX")
        temp.writeText(ProjectCodec.encode(project))
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Could not replace project file")
        }
    }

    override suspend fun load(id: String): Project? = withContext(dispatcher) {
        val file = fileFor(id)
        if (!file.exists()) return@withContext null
        readOrNull(file)
    }

    override suspend fun list(): List<Project> = withContext(dispatcher) {
        directory.listFiles { file -> file.name.endsWith(EXTENSION) }.orEmpty()
            .mapNotNull(::readOrNull)
            .sortedByDescending { it.modifiedAtMillis }
    }

    override suspend fun delete(id: String) {
        withContext(dispatcher) { fileFor(id).delete() }
    }

    /** A corrupt file is logged and skipped so one bad project cannot break the recent list. */
    private fun readOrNull(file: File): Project? = try {
        ProjectCodec.decode(file.readText())
    } catch (error: IllegalArgumentException) {
        logger.error("PROJECT_UNREADABLE", mapOf("reason" to error.message), error)
        null
    } catch (error: IOException) {
        logger.error("PROJECT_UNREADABLE", mapOf("reason" to error.message), error)
        null
    }

    /** IDs are generated UUIDs; anything else is rejected so a bad id can never escape [directory]. */
    private fun fileFor(id: String): File {
        require(ID_PATTERN.matches(id)) { "Invalid project id" }
        return File(directory, "$id$EXTENSION")
    }

    private companion object {
        const val EXTENSION = ".json"
        const val TEMP_SUFFIX = ".json.tmp"
        val ID_PATTERN = Regex("[A-Za-z0-9-]{1,64}")
    }
}
