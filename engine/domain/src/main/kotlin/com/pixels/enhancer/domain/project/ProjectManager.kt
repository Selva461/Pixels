package com.pixels.enhancer.domain.project

import com.pixels.enhancer.domain.editing.EditHistory
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.model.ImageSource
import java.util.UUID

/** Creates, resumes and updates projects. Every committed edit is autosaved for recovery. */
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

    suspend fun recent(): List<Project> = store.list()

    suspend fun load(id: String): Project? = store.load(id)

    /** Removes the project only; the original photo is untouched. */
    suspend fun delete(id: String) = store.delete(id)

    fun historyOf(project: Project) = EditHistory(project.edit, project.undo, project.redo)

    private suspend fun save(project: Project): Project {
        val updated = project.copy(modifiedAtMillis = clock.nowMillis())
        store.save(updated)
        return updated
    }
}
