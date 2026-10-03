package com.pixels.enhancer.usecase

import com.pixels.enhancer.domain.editing.EditHistory
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.export.ExportFormat
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.geometry.CropRect
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.model.ImageSource
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ManualControl
import com.pixels.enhancer.domain.project.FileProjectStore
import com.pixels.enhancer.domain.project.Project
import com.pixels.enhancer.domain.project.ProjectCodec
import com.pixels.enhancer.domain.project.ProjectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditHistoryTest {
    private val a = EditState(strength = 0.1f)
    private val b = EditState(strength = 0.2f)
    private val c = EditState(strength = 0.3f)

    @Test
    fun `undo and redo walk the committed steps`() {
        val history = EditHistory(a)
        history.commit(b)
        history.commit(c)
        assertEquals(b, history.undo())
        assertEquals(a, history.undo())
        assertNull(history.undo())
        assertEquals(b, history.redo())
        assertEquals(b, history.current)
    }

    @Test
    fun `a new commit clears redo and duplicates are ignored`() {
        val history = EditHistory(a)
        history.commit(b)
        history.undo()
        assertTrue(history.canRedo)
        assertFalse(history.commit(a))
        history.commit(c)
        assertFalse(history.canRedo)
    }

    @Test
    fun `history is bounded`() {
        val history = EditHistory(a, limit = 3)
        repeat(10) { history.commit(EditState(strength = it / 10f)) }
        assertEquals(3, history.undoStates.size)
    }
}

class ProjectPersistenceTest {
    private val edit = EditState(
        strength = 0.6f,
        manual = ManualAdjustments.of(ManualControl.EXPOSURE to 0.3f, ManualControl.GRAIN to 0.2f),
        lookId = "film",
        geometry = Geometry(quarterTurns = 1, flipHorizontal = true, straightenDegrees = -3.5f, crop = CropRect.of(0.1f, 0.2f, 0.9f, 0.8f)),
    )
    private val project = Project(
        id = "3f1e8c2a-0000-4000-8000-000000000001",
        sourceId = "content://media/picker/0/1234",
        displayName = "IMG_1234.jpg",
        createdAtMillis = 1_000,
        modifiedAtMillis = 2_000,
        edit = edit,
        undo = listOf(EditState(), EditState(strength = 0.3f)),
        exportOptions = ExportOptions(format = ExportFormat.PNG, quality = 80),
        lastExportedEdit = EditState(strength = 0.3f),
    )

    @Test
    fun `codec round-trips every field`() {
        assertEquals(project, ProjectCodec.decode(ProjectCodec.encode(project)))
    }

    @Test
    fun `codec rejects corrupt and future files and ignores unknown fields`() {
        assertFailsWith<IllegalArgumentException> { ProjectCodec.decode("{not json") }
        assertFailsWith<IllegalArgumentException> { ProjectCodec.decode(ProjectCodec.encode(project).replace("\"schemaVersion\":1", "\"schemaVersion\":99")) }
        val withExtra = ProjectCodec.encode(project).replaceFirst("{", "{\"futureField\":true,")
        assertEquals(project, ProjectCodec.decode(withExtra))
    }

    @Test
    fun `store saves, lists newest first, skips corrupt files and deletes`() = runTest {
        val directory = Files.createTempDirectory("projects").toFile()
        val store = FileProjectStore(directory, dispatcher = Dispatchers.Unconfined)
        val older = project.copy(id = "a-1", modifiedAtMillis = 10)
        val newer = project.copy(id = "a-2", modifiedAtMillis = 20)
        store.save(older)
        store.save(newer)
        directory.resolve("broken.json").writeText("garbage")
        assertEquals(listOf("a-2", "a-1"), store.list().map { it.id })
        store.delete("a-1")
        assertNull(store.load("a-1"))
        assertFailsWith<IllegalArgumentException> { store.load("../../etc/passwd") }
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `manager resumes the same photo and records history and exports`() = runTest {
        val store = FileProjectStore(Files.createTempDirectory("projects").toFile(), dispatcher = Dispatchers.Unconfined)
        var now = 100L
        val manager = ProjectManager(store, clock = { now }, newId = { "p-${now}" })
        val source = ImageSource("content://x/1", "IMG.jpg", "image/jpeg", 100, 80, 0, false)

        val created = manager.startOrResume(source, EditState(), ExportOptions())
        val history = manager.historyOf(created)
        history.commit(edit)
        now = 200
        val saved = manager.recordHistory(created, history)
        assertTrue(saved.hasUnexportedChanges)

        val resumed = manager.startOrResume(source, EditState(), ExportOptions())
        assertEquals(created.id, resumed.id)
        assertEquals(edit, resumed.edit)
        assertTrue(manager.historyOf(resumed).canUndo)

        val exported = manager.recordExport(resumed, ExportOptions(quality = 90), resumed.edit)
        assertFalse(exported.hasUnexportedChanges)
        assertEquals(1, manager.recent().size)
    }
}
