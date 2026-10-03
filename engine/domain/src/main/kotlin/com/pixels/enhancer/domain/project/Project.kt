package com.pixels.enhancer.domain.project

import com.pixels.enhancer.core.constants.ENHANCEMENT_ALGORITHM_VERSION
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.export.ExportOptions

/**
 * An editable project: a reference to the original plus edit instructions. Never contains the
 * original's pixels, so projects are small and the original is never duplicated.
 */
data class Project(
    val id: String,
    /** Platform reference to the original (a content URI on Android). */
    val sourceId: String,
    val displayName: String?,
    val createdAtMillis: Long,
    val modifiedAtMillis: Long,
    val edit: EditState,
    val undo: List<EditState> = emptyList(),
    val redo: List<EditState> = emptyList(),
    val exportOptions: ExportOptions = ExportOptions(),
    /** The edit as it was at the last successful export; null if never exported. */
    val lastExportedEdit: EditState? = null,
    val engineVersion: String = ENHANCEMENT_ALGORITHM_VERSION,
) {
    /** True when the current edit has not been exported yet. */
    val hasUnexportedChanges: Boolean get() = lastExportedEdit != edit && edit != EditState()
}

interface ProjectStore {
    suspend fun save(project: Project)
    suspend fun load(id: String): Project?

    /** Most recently modified first. Unreadable files are skipped, never fatal. */
    suspend fun list(): List<Project>
    suspend fun delete(id: String)
}

fun interface WallClock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = WallClock { System.currentTimeMillis() }
    }
}
