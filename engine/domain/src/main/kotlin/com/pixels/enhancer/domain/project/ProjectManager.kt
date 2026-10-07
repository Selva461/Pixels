package com.pixels.enhancer.domain.project

import com.pixels.enhancer.domain.editing.EditHistory
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.model.ImageSource
import java.util.UUID

/** Creates, resumes and updates projects. Every committed edit is autosaved for recovery. */
/** Most edits are named after their photo; a short cap keeps lists readable. */
private const val MAX_NAME_LENGTH = 80
private const val COPY_SUFFIX = " (copy)"

class ProjectManager(
    private val store: ProjectStore,
    private val clock: WallClock = WallClock.SYSTEM,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Reopening the same photo continues its existing project rather than starting over. */
    suspend fun startOrResume(source: ImageSource, initialEdit: EditState, defaultExport: ExportOptions): Project {
        store.list().firstOrNull { it.sourceId == source.id }?.let { return it }
        val now = clock.nowMillis()
        val project = Project(
            id = newId(),
            sourceId = source.id,
            displayName = source.displayName,
            createdAtMillis = now,
            modifiedAtMillis = now,
            edit = initialEdit,
            exportOptions = defaultExport,
        )
        store.save(project)
        return project
    }

    suspend fun recordHistory(project: Project, history: EditHistory): Project = save(
        project.copy(edit = history.current, undo = history.undoStates, redo = history.redoStates),
    )

    suspend fun recordExport(project: Project, options: ExportOptions, exported: EditState): Project =
        save(project.copy(exportOptions = options, lastExportedEdit = exported))

    /** Replaces the project's named versions. */
    suspend fun recordVersions(project: Project, versions: List<EditVersion>): Project = save(project.copy(versions = versions))

    /**
     * A second, independent project for the same photo ("virtual copy"): same edit, its own history
     * from here on. Returns null if [id] does not exist.
     */
    suspend fun duplicate(id: String): Project? {
        val original = store.load(id) ?: return null
        val now = clock.nowMillis()
        val copy = original.copy(
            id = newId(),
            displayName = copyName(original.displayName),
            createdAtMillis = now,
            modifiedAtMillis = now,
            undo = emptyList(),
            redo = emptyList(),
            lastExportedEdit = null,
        )
        store.save(copy)
        return copy
    }

    /** Renames a project in the Recent list (the photo file itself is never renamed). */
    suspend fun rename(id: String, name: String): Project? {
        val project = store.load(id) ?: return null
        val cleaned = cleanName(name) ?: return project
        return save(project.copy(displayName = cleaned))
    }

    suspend fun recent(): List<Project> = store.list()

    suspend fun load(id: String): Project? = store.load(id)

    /** Removes the project only; the original photo is untouched. */
    suspend fun delete(id: String) = store.delete(id)

    fun historyOf(project: Project) = EditHistory(project.edit, project.undo, project.redo)

    private fun copyName(name: String?): String = (cleanName(name ?: "") ?: "Photo").take(MAX_NAME_LENGTH - COPY_SUFFIX.length) + COPY_SUFFIX

    /** Control characters removed, trimmed, length capped; null if nothing is left. */
    private fun cleanName(name: String): String? = name.filterNot { it.isISOControl() }.trim().take(MAX_NAME_LENGTH).ifEmpty { null }

    private suspend fun save(project: Project): Project {
        val updated = project.copy(modifiedAtMillis = clock.nowMillis())
        store.save(updated)
        return updated
    }
}
