package com.pixels.enhancer.domain.usecase

import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.planning.QualityPreset
import com.pixels.enhancer.domain.presets.PresetMath
import com.pixels.enhancer.domain.presets.SettingsGroup
import com.pixels.enhancer.domain.project.ProjectManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One photo's outcome in a batch. */
sealed interface BatchItemResult {
    val sourceId: String

    data class Saved(override val sourceId: String, val result: ExportResult) : BatchItemResult

    data class Failed(override val sourceId: String, val code: ErrorCode) : BatchItemResult
}

/**
 * Applies one set of settings to many photos and saves each as a new file — the originals are
 * never changed. Only the chosen settings groups are copied (never crop, masks or healing, which
 * belong to one photo). Photos are processed one at a time so memory stays bounded, and one
 * failure never stops the rest. With a [projects] manager, each photo also gets a project, so it
 * appears in Recent and can be fine-tuned later.
 */
class BatchExportUseCase(
    private val enhance: EnhanceImageUseCase,
    private val projects: ProjectManager? = null,
) {
    suspend fun run(
        sourceIds: List<String>,
        settings: EditState,
        groups: Set<SettingsGroup>,
        options: ExportOptions,
        preset: QualityPreset = QualityPreset.NATURAL,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<BatchItemResult> {
        require(sourceIds.size <= MAX_ITEMS) { "At most $MAX_ITEMS photos per batch" }
        val edit = editFor(settings, groups)
        val results = ArrayList<BatchItemResult>(sourceIds.size)
        onProgress(0, sourceIds.size)
        sourceIds.forEachIndexed { index, sourceId ->
            currentCoroutineContext().ensureActive()
            results += exportOne(sourceId, edit, options, preset)
            onProgress(index + 1, sourceIds.size)
        }
        return results
    }

    private suspend fun exportOne(sourceId: String, edit: EditState, options: ExportOptions, preset: QualityPreset): BatchItemResult {
        val session = when (val opened = enhance.open(sourceId, preset)) {
            is OperationResult.Failure -> return BatchItemResult.Failed(sourceId, opened.code)
            is OperationResult.Success -> opened.value
        }
        return when (val exported = enhance.export(session, edit.toRequest(), options)) {
            is OperationResult.Failure -> BatchItemResult.Failed(sourceId, exported.code)
            is OperationResult.Success -> {
                recordProject(session, edit, options)
                BatchItemResult.Saved(sourceId, exported.value)
            }
        }
    }

    /** A project failure never undoes a successful export; the file is already saved. */
    private suspend fun recordProject(session: EnhancementSession, edit: EditState, options: ExportOptions) {
        val manager = projects ?: return
        try {
            val project = manager.startOrResume(session.source, edit, options)
            val history = manager.historyOf(project).apply { commit(edit) }
            manager.recordExport(manager.recordHistory(project, history), options, edit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") ignored: Exception) {
            // Recording the project is a convenience; the exported file is what the user asked for.
        }
    }

    companion object {
        /** Keeps one batch to a few minutes on a phone. */
        const val MAX_ITEMS = 50

        /** The edit every photo in the batch gets: the chosen groups plus Auto strength. */
        fun editFor(settings: EditState, groups: Set<SettingsGroup>): EditState =
            PresetMath.paste(settings, EditState(strength = settings.strength), groups)
    }
}
