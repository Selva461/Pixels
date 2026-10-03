package com.pixels.enhancer.domain.project

import com.pixels.enhancer.domain.analysis.SceneType
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.export.ExportFormat
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.export.ExportSize
import com.pixels.enhancer.domain.export.MetadataPolicy
import com.pixels.enhancer.domain.geometry.CropRect
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.planning.ColorMixer
import com.pixels.enhancer.domain.planning.HslShift
import com.pixels.enhancer.domain.planning.HueBand
import com.pixels.enhancer.domain.planning.ManualAdjustments
import com.pixels.enhancer.domain.planning.ManualControl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Versioned JSON for project files. Domain classes stay free of serialization annotations; these
 * DTOs are the on-disk schema. To change the schema: add fields with defaults (no bump needed), or
 * bump [CURRENT_SCHEMA_VERSION] and add a step to [migrate].
 */
object ProjectCodec {
    const val CURRENT_SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(project: Project): String = json.encodeToString(ProjectFile.serializer(), ProjectFile.from(project))

    /** Throws [IllegalArgumentException] for corrupt files or schemas newer than this app understands. */
    fun decode(text: String): Project {
        val element = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw IllegalArgumentException("Not a project file", it) }
        val version = element["schemaVersion"]?.jsonPrimitive?.int ?: throw IllegalArgumentException("Missing schemaVersion")
        require(version <= CURRENT_SCHEMA_VERSION) { "Project schema $version is newer than supported $CURRENT_SCHEMA_VERSION" }
        val migrated = migrate(element, version)
        return runCatching { json.decodeFromJsonElement(ProjectFile.serializer(), migrated).toProject() }
            .getOrElse { throw IllegalArgumentException("Invalid project file", it) }
    }

    /** Upgrades older schemas step by step. Version 1 is the first, so there is nothing to do yet. */
    private fun migrate(element: kotlinx.serialization.json.JsonObject, fromVersion: Int) = when (fromVersion) {
        CURRENT_SCHEMA_VERSION -> element
        else -> throw IllegalArgumentException("No migration from schema $fromVersion")
    }
}

@Serializable
private data class ProjectFile(
    val schemaVersion: Int = ProjectCodec.CURRENT_SCHEMA_VERSION,
    val id: String,
    val sourceId: String,
    val displayName: String? = null,
    val createdAtMillis: Long,
    val modifiedAtMillis: Long,
    val engineVersion: String,
    val edit: EditFile,
    val undo: List<EditFile> = emptyList(),
    val redo: List<EditFile> = emptyList(),
    val export: ExportFile = ExportFile(),
    val lastExportedEdit: EditFile? = null,
) {
    fun toProject() = Project(
        id = id,
        sourceId = sourceId,
        displayName = displayName,
        createdAtMillis = createdAtMillis,
        modifiedAtMillis = modifiedAtMillis,
        edit = edit.toEdit(),
        undo = undo.map { it.toEdit() },
        redo = redo.map { it.toEdit() },
        exportOptions = export.toOptions(),
        lastExportedEdit = lastExportedEdit?.toEdit(),
        engineVersion = engineVersion,
    )

    companion object {
        fun from(project: Project) = ProjectFile(
            id = project.id,
            sourceId = project.sourceId,
            displayName = project.displayName,
            createdAtMillis = project.createdAtMillis,
            modifiedAtMillis = project.modifiedAtMillis,
            engineVersion = project.engineVersion,
            edit = EditFile.from(project.edit),
            undo = project.undo.map(EditFile::from),
            redo = project.redo.map(EditFile::from),
            export = ExportFile.from(project.exportOptions),
            lastExportedEdit = project.lastExportedEdit?.let(EditFile::from),
        )
    }
}

@Serializable
private data class EditFile(
    val strength: Float,
    /** Keyed by [ManualControl] name; unknown names (from newer builds) are ignored. */
    val manual: Map<String, Float> = emptyMap(),
    val lookId: String = "none",
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val straightenDegrees: Float = 0f,
    val crop: List<Float> = listOf(0f, 0f, 1f, 1f),
    /** Keyed by [HueBand] name, value = [hue, saturation, luminance]. Added without a schema bump (defaults to none). */
    val colorMixer: Map<String, List<Float>> = emptyMap(),
    val sceneOverride: String? = null,
) {
    fun toEdit(): EditState {
        val controls = manual.mapNotNull { (name, value) -> ManualControl.entries.firstOrNull { it.name == name }?.let { it to value } }
        val adjustments = controls.fold(ManualAdjustments.NONE) { acc, (control, value) -> acc.with(control, value) }
        require(crop.size == CROP_VALUES) { "Crop needs $CROP_VALUES values" }
        return EditState(
            strength = strength.coerceIn(0f, 1f),
            manual = adjustments,
            lookId = lookId,
            geometry = Geometry(
                quarterTurns = Math.floorMod(quarterTurns, QUARTER_TURNS),
                flipHorizontal = flipHorizontal,
                straightenDegrees = straightenDegrees.coerceIn(-Geometry.MAX_STRAIGHTEN_DEGREES, Geometry.MAX_STRAIGHTEN_DEGREES),
                crop = CropRect.of(crop[0], crop[1], crop[2], crop[3]),
            ),
            colorMixer = colorMixer.entries.fold(ColorMixer.NONE) { mixer, (name, values) ->
                val band = HueBand.entries.firstOrNull { it.name == name }
                if (band == null || values.size != HSL_VALUES) mixer else mixer.with(band, HslShift(values[0], values[1], values[2]))
            },
            sceneOverride = SceneType.entries.firstOrNull { it.name == sceneOverride },
        )
    }

    companion object {
        private const val CROP_VALUES = 4
        private const val HSL_VALUES = 3
        private const val QUARTER_TURNS = 4

        fun from(edit: EditState) = EditFile(
            strength = edit.strength,
            manual = edit.manual.values.mapKeys { it.key.name },
            lookId = edit.lookId,
            quarterTurns = edit.geometry.quarterTurns,
            flipHorizontal = edit.geometry.flipHorizontal,
            straightenDegrees = edit.geometry.straightenDegrees,
            crop = with(edit.geometry.crop) { listOf(left, top, right, bottom) },
            colorMixer = edit.colorMixer.shifts.entries.associate { (band, shift) -> band.name to listOf(shift.hue, shift.saturation, shift.luminance) },
            sceneOverride = edit.sceneOverride?.name,
        )
    }
}

@Serializable
private data class ExportFile(
    val format: String = ExportFormat.JPEG.name,
    val quality: Int = ExportOptions.DEFAULT_QUALITY,
    val size: String = ExportSize.FULL.name,
    val metadata: String = MetadataPolicy.REMOVE_LOCATION.name,
) {
    fun toOptions() = ExportOptions(
        format = ExportFormat.entries.firstOrNull { it.name == format } ?: ExportFormat.JPEG,
        quality = quality.coerceIn(ExportOptions.MIN_QUALITY, ExportOptions.MAX_QUALITY),
        size = ExportSize.entries.firstOrNull { it.name == size } ?: ExportSize.FULL,
        metadata = MetadataPolicy.entries.firstOrNull { it.name == metadata } ?: MetadataPolicy.REMOVE_LOCATION,
    )

    companion object {
        fun from(options: ExportOptions) = ExportFile(options.format.name, options.quality, options.size.name, options.metadata.name)
    }
}
