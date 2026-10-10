package com.pixels.enhancer.domain.editing

import com.pixels.enhancer.domain.analysis.SceneType
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.planning.Calibration
import com.pixels.enhancer.domain.planning.ColorGrading
import com.pixels.enhancer.domain.planning.ColorMixer
import com.pixels.enhancer.domain.planning.EnhancementStrength
import com.pixels.enhancer.domain.planning.Look
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ToneCurves
import com.pixels.enhancer.domain.retouch.Retouch

/**
 * Everything the user controls for one photo — the nondestructive edit. The original pixels are
 * never part of it; rendering = original + EditState.
 */
data class EditState(
    val strength: Float = EnhancementStrength.DEFAULT,
    val manual: ManualAdjustments = ManualAdjustments.NONE,
    val lookId: String = Look.NONE.id,
    val geometry: Geometry = Geometry.NONE,
    val colorMixer: ColorMixer = ColorMixer.NONE,
    val toneCurves: ToneCurves = ToneCurves.NONE,
    val localAdjustments: LocalAdjustments = LocalAdjustments.NONE,
    val colorGrading: ColorGrading = ColorGrading.NONE,
    val retouch: Retouch = Retouch.NONE,
    val calibration: Calibration = Calibration.NONE,
    /** False = "As shot": the automatic white-balance correction is skipped. */
    val autoWhiteBalance: Boolean = true,
    /** Overrides the detected scene for Auto Enhance; null = use detection. */
    val sceneOverride: SceneType? = null,
) {
    companion object {
        /** Strength 0 and nothing else: renders the original appearance. */
        val ORIGINAL = EditState(strength = 0f)
    }
}

/**
 * Undo/redo over committed edits. Slider drags are not committed until the finger lifts, so one
 * drag is one history step. Bounded so a long session cannot grow memory without limit.
 */
class EditHistory(
    initial: EditState,
    undo: List<EditState> = emptyList(),
    redo: List<EditState> = emptyList(),
    private val limit: Int = DEFAULT_LIMIT,
) {
    private val undoStack = ArrayDeque(undo.takeLast(limit))
    private val redoStack = ArrayDeque(redo.takeLast(limit))

    var current: EditState = initial
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoStates: List<EditState> get() = undoStack.toList()
    val redoStates: List<EditState> get() = redoStack.toList()

    /** Records [state] as a new step. Returns false (and records nothing) if it equals the current state. */
    fun commit(state: EditState): Boolean {
        if (state == current) return false
        push(undoStack, current)
        current = state
        redoStack.clear()
        return true
    }

    /** Every state oldest first: the undo steps, the current edit, then the redo steps. */
    val timeline: List<EditState> get() = undoStack.toList() + current + redoStack.reversed()

    /** Index of [current] in [timeline]. */
    val position: Int get() = undoStack.size

    /** Moves to [index] in [timeline] by undoing or redoing; returns the new current edit. */
    fun jumpTo(index: Int): EditState {
        require(index in 0 until undoStack.size + 1 + redoStack.size) { "No history step $index" }
        while (position > index) undo()
        while (position < index) redo()
        return current
    }

    fun undo(): EditState? {
        val previous = undoStack.removeLastOrNull() ?: return null
        push(redoStack, current)
        current = previous
        return previous
    }

    fun redo(): EditState? {
        val next = redoStack.removeLastOrNull() ?: return null
        push(undoStack, current)
        current = next
        return next
    }

    private fun push(stack: ArrayDeque<EditState>, state: EditState) {
        stack.addLast(state)
        if (stack.size > limit) stack.removeFirst()
    }

    companion object {
        const val DEFAULT_LIMIT = 30
    }
}
