package com.pixels.enhancer.domain.editing

import com.pixels.enhancer.domain.planning.ManualControl

/** Short, user-facing names for what changed between two edits — the labels of the History list. */
object EditDiff {
    private const val MAX_NAMED = 2

    fun describe(before: EditState, after: EditState): String {
        val changes = changes(before, after)
        return when {
            changes.isEmpty() -> "No change"
            changes.size <= MAX_NAMED -> changes.joinToString(", ")
            else -> changes.take(MAX_NAMED).joinToString(", ") + " +${changes.size - MAX_NAMED}"
        }
    }

    /** Every changed part, in the order the editor's tools appear. */
    fun changes(before: EditState, after: EditState): List<String> = buildList {
        if (before.lookId != after.lookId) add("Look")
        if (before.strength != after.strength) add("Auto strength")
        if (before.sceneOverride != after.sceneOverride) add("Scene")
        addAll(geometryChanges(before, after))
        ManualControl.entries.filter { before.manual[it] != after.manual[it] }.forEach { add(it.label) }
        if (before.toneCurves != after.toneCurves) add("Curve")
        if (before.autoWhiteBalance != after.autoWhiteBalance) add(if (after.autoWhiteBalance) "Auto white balance" else "As shot white balance")
        if (before.colorMixer != after.colorMixer) add("Colour mixer")
        if (before.colorGrading.monochrome != after.colorGrading.monochrome) add(if (after.colorGrading.monochrome) "Black & white" else "Colour")
        if (before.colorGrading.copy(monochrome = false) != after.colorGrading.copy(monochrome = false)) add("Colour grading")
        if (before.calibration != after.calibration) add("Calibration")
        addAll(maskChanges(before, after))
        addAll(retouchChanges(before, after))
    }

    private fun geometryChanges(before: EditState, after: EditState): List<String> = buildList {
        val a = before.geometry
        val b = after.geometry
        if (a.quarterTurns != b.quarterTurns) add("Rotate")
        if (a.flipHorizontal != b.flipHorizontal) add("Flip")
        if (a.straightenDegrees != b.straightenDegrees) add("Straighten")
        if (a.crop != b.crop && a.quarterTurns == b.quarterTurns && a.flipHorizontal == b.flipHorizontal) add("Crop")
        if (a.lens != b.lens) add("Lens corrections")
        if (a.perspective != b.perspective) add("Perspective")
    }

    private fun maskChanges(before: EditState, after: EditState): List<String> = buildList {
        val old = before.localAdjustments.items.associateBy { it.id }
        val new = after.localAdjustments.items.associateBy { it.id }
        if ((new.keys - old.keys).isNotEmpty()) add("Mask added")
        if ((old.keys - new.keys).isNotEmpty()) add("Mask removed")
        after.localAdjustments.items.forEachIndexed { index, item ->
            val previous = old[item.id]
            if (previous != null && previous != item) add(item.name.ifEmpty { "Mask ${index + 1}" })
        }
    }

    private fun retouchChanges(before: EditState, after: EditState): List<String> = buildList {
        val old = before.retouch.spots.associateBy { it.id }
        val new = after.retouch.spots.associateBy { it.id }
        if ((new.keys - old.keys).isNotEmpty()) add("Spot added")
        if ((old.keys - new.keys).isNotEmpty()) add("Spot removed")
        if (new.any { (id, spot) -> old[id] != null && old[id] != spot }) add("Spot adjusted")
    }
}
