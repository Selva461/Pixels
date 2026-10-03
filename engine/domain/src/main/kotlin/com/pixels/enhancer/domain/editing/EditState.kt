package com.pixels.enhancer.domain.editing

import com.pixels.enhancer.domain.analysis.SceneType
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.planning.ColorMixer
import com.pixels.enhancer.domain.planning.EnhancementStrength
import com.pixels.enhancer.domain.planning.Look
import com.pixels.enhancer.domain.planning.ManualAdjustments

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
