package com.pixels.enhancer.domain.project

import com.pixels.enhancer.domain.analysis.SceneType
import com.pixels.enhancer.domain.editing.EditState
import com.pixels.enhancer.domain.export.Border
import com.pixels.enhancer.domain.export.ExportFormat
import com.pixels.enhancer.domain.export.Watermark
import com.pixels.enhancer.domain.export.WatermarkPosition
import com.pixels.enhancer.domain.planning.Calibration
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.export.ExportSize
import com.pixels.enhancer.domain.export.MetadataPolicy
import com.pixels.enhancer.domain.geometry.CropRect
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.local.LocalAdjustment
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.local.MaskShape
import com.pixels.enhancer.domain.local.BrushStroke
import com.pixels.enhancer.domain.local.RangeMask
import com.pixels.enhancer.domain.geometry.LensCorrection
import com.pixels.enhancer.domain.geometry.Perspective
import com.pixels.enhancer.domain.planning.ColorGrading
import com.pixels.enhancer.domain.planning.GradeWheel
import com.pixels.enhancer.domain.retouch.Retouch
import com.pixels.enhancer.domain.retouch.RetouchMode
import com.pixels.enhancer.domain.retouch.RetouchSpot
import com.pixels.enhancer.domain.planning.ColorMixer
import com.pixels.enhancer.domain.planning.CurveChannel
import com.pixels.enhancer.domain.planning.CurvePoint
import com.pixels.enhancer.domain.planning.CurvePoints
import com.pixels.enhancer.domain.planning.ToneCurves
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
    val versions: List<VersionFile> = emptyList(),
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
        versions = versions.map { EditVersion(it.name, it.createdAtMillis, it.edit.toEdit()) },
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
            versions = project.versions.map { VersionFile(it.name, it.createdAtMillis, EditFile.from(it.edit)) },
        )
    }
}

@Serializable
private data class VersionFile(val name: String, val createdAtMillis: Long, val edit: EditFile)

/** JSON for a bare [EditState] (presets, copied settings); same schema as inside project files. */
object EditStateCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(edit: EditState): String = json.encodeToString(EditFile.serializer(), EditFile.from(edit))

    fun decode(text: String): EditState = runCatching { json.decodeFromString(EditFile.serializer(), text).toEdit() }
        .getOrElse { throw IllegalArgumentException("Invalid edit settings", it) }
}

@Serializable
internal data class EditFile(
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
    /** Keyed by [CurveChannel] name; value = flattened [x0, y0, x1, y1, …]. */
    val curves: Map<String, List<Float>> = emptyMap(),
    val local: List<LocalFile> = emptyList(),
    /** [distortion, chromaticAberration, vignetting]. */
    val lens: List<Float> = emptyList(),
    /** [vertical, horizontal, rotate, aspect, scale, offsetX, offsetY]. */
    val perspective: List<Float> = emptyList(),
    val grading: GradingFile? = null,
    val retouch: List<RetouchFile> = emptyList(),
    /** [redHue, redSaturation, greenHue, greenSaturation, blueHue, blueSaturation, shadowsTint]. */
    val calibration: List<Float> = emptyList(),
    val autoWhiteBalance: Boolean = true,
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
                lens = if (lens.size == LENS_VALUES) LensCorrection(lens[0], lens[1], lens[2]).clamped() else LensCorrection.NONE,
                perspective = if (perspective.size == PERSPECTIVE_VALUES) {
                    Perspective(perspective[0], perspective[1], perspective[2], perspective[3], perspective[4], perspective[5], perspective[6]).clamped()
                } else {
                    Perspective.NONE
                },
            ),
            colorMixer = colorMixer.entries.fold(ColorMixer.NONE) { mixer, (name, values) ->
                val band = HueBand.entries.firstOrNull { it.name == name }
                if (band == null || values.size != HSL_VALUES) mixer else mixer.with(band, HslShift(values[0], values[1], values[2]))
            },
            sceneOverride = SceneType.entries.firstOrNull { it.name == sceneOverride },
            toneCurves = curves.entries.fold(ToneCurves.NONE) { acc, (name, flat) ->
                val channel = CurveChannel.entries.firstOrNull { it.name == name }
                if (channel == null || flat.size < 4 || flat.size % 2 != 0) acc
                else acc.with(channel, CurvePoints.of(flat.chunked(2) { CurvePoint(it[0], it[1]) }))
            },
            localAdjustments = LocalAdjustments(local.mapNotNull { it.toAdjustment() }.take(LocalAdjustments.MAX_ITEMS)),
            colorGrading = grading?.toGrading() ?: ColorGrading.NONE,
            retouch = Retouch(retouch.mapNotNull { it.toSpot() }.take(Retouch.MAX_SPOTS)),
            calibration = if (calibration.size == CALIBRATION_VALUES) {
                Calibration(calibration[0], calibration[1], calibration[2], calibration[3], calibration[4], calibration[5], calibration[6]).clamped()
            } else {
                Calibration.NONE
            },
            autoWhiteBalance = autoWhiteBalance,
        )
    }

    companion object {
        private const val CROP_VALUES = 4
        private const val HSL_VALUES = 3
        private const val QUARTER_TURNS = 4
        private const val LENS_VALUES = 3
        private const val PERSPECTIVE_VALUES = 7
        private const val CALIBRATION_VALUES = 7

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
            curves = edit.toneCurves.curves.entries.associate { (channel, points) -> channel.name to points.points.flatMap { listOf(it.x, it.y) } },
            local = edit.localAdjustments.items.map(LocalFile::from),
            lens = with(edit.geometry.lens) { if (isIdentity) emptyList() else listOf(distortion, chromaticAberration, vignetting) },
            perspective = with(edit.geometry.perspective) {
                if (isIdentity) emptyList() else listOf(vertical, horizontal, rotate, aspect, scale, offsetX, offsetY)
            },
            // Stored whenever anything differs from the default, so blending and balance survive even with no wheel set.
            grading = edit.colorGrading.takeUnless { it == ColorGrading.NONE }?.let(GradingFile::from),
            retouch = edit.retouch.spots.map(RetouchFile::from),
            calibration = with(edit.calibration) {
                if (isNeutral) emptyList() else listOf(redHue, redSaturation, greenHue, greenSaturation, blueHue, blueSaturation, shadowsTint)
            },
            autoWhiteBalance = edit.autoWhiteBalance,
        )
    }
}

/**
 * A local adjustment; [shape] is "linear" (x0, y0, x1, y1), "radial" (cx, cy, rx, ry, feather),
 * "brush" (strokes only) or "full" (whole photo, usually with a range).
 */
@Serializable
internal data class LocalFile(
    val id: Int,
    val shape: String,
    val geometry: List<Float> = emptyList(),
    val invert: Boolean = false,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val tint: Float = 0f,
    val clarity: Float = 0f,
    val sharpness: Float = 0f,
    val brush: List<StrokeFile> = emptyList(),
    /** "luminance" [low, high, smoothness] or "color" [r, g, b, tolerance]. */
    val rangeType: String? = null,
    val range: List<Float> = emptyList(),
    val name: String = "",
) {
    fun toAdjustment(): LocalAdjustment? {
        val mask = when {
            shape == LINEAR && geometry.size == LINEAR_VALUES -> MaskShape.Linear(geometry[0], geometry[1], geometry[2], geometry[3])
            shape == RADIAL && geometry.size == RADIAL_VALUES -> MaskShape.Radial(geometry[0], geometry[1], geometry[2], geometry[3], geometry[4])
            shape == BRUSH -> MaskShape.None
            shape == FULL -> MaskShape.Full
            else -> return null
        }
        val rangeMask = when {
            rangeType == LUMINANCE && range.size == 3 -> RangeMask.Luminance(range[0], range[1], range[2])
            rangeType == COLOR && range.size == 4 -> RangeMask.Color(range[0].toInt(), range[1].toInt(), range[2].toInt(), range[3])
            else -> null
        }
        return LocalAdjustment(
            id, mask, invert, exposure, contrast, saturation, temperature, highlights, shadows, tint, clarity, sharpness,
            brush.map { it.toStroke() }, rangeMask, name,
        ).clamped()
    }

    companion object {
        private const val LINEAR = "linear"
        private const val RADIAL = "radial"
        private const val BRUSH = "brush"
        private const val FULL = "full"
        private const val LUMINANCE = "luminance"
        private const val COLOR = "color"
        private const val LINEAR_VALUES = 4
        private const val RADIAL_VALUES = 5

        fun from(item: LocalAdjustment): LocalFile {
            val (shape, values) = when (val mask = item.shape) {
                is MaskShape.Linear -> LINEAR to listOf(mask.startX, mask.startY, mask.endX, mask.endY)
                is MaskShape.Radial -> RADIAL to listOf(mask.centerX, mask.centerY, mask.radiusX, mask.radiusY, mask.feather)
                MaskShape.None -> BRUSH to emptyList()
                MaskShape.Full -> FULL to emptyList()
            }
            val (rangeType, range) = when (val r = item.range) {
                is RangeMask.Luminance -> LUMINANCE to listOf(r.low, r.high, r.smoothness)
                is RangeMask.Color -> COLOR to listOf(r.red.toFloat(), r.green.toFloat(), r.blue.toFloat(), r.tolerance)
                null -> null to emptyList()
            }
            return LocalFile(
                item.id, shape, values, item.invert, item.exposure, item.contrast, item.saturation, item.temperature,
                item.highlights, item.shadows, item.tint, item.clarity, item.sharpness,
                item.brush.map(StrokeFile::from), rangeType, range, item.name,
            )
        }
    }
}

@Serializable
internal data class StrokeFile(val points: List<Float>, val radius: Float, val feather: Float, val flow: Float = 1f, val erase: Boolean = false) {
    fun toStroke() = BrushStroke(points, radius, feather, flow, erase)

    companion object {
        fun from(stroke: BrushStroke) = StrokeFile(stroke.points, stroke.radius, stroke.feather, stroke.flow, stroke.erase)
    }
}

/** Wheels as [hue, saturation, luminance]. */
@Serializable
internal data class GradingFile(
    val shadows: List<Float> = emptyList(),
    val midtones: List<Float> = emptyList(),
    val highlights: List<Float> = emptyList(),
    val global: List<Float> = emptyList(),
    val blending: Float = ColorGrading.DEFAULT_BLENDING,
    val balance: Float = 0f,
    val monochrome: Boolean = false,
) {
    fun toGrading() = ColorGrading(wheel(shadows), wheel(midtones), wheel(highlights), wheel(global), blending, balance, monochrome).clamped()

    private fun wheel(values: List<Float>) = if (values.size == 3) GradeWheel(values[0], values[1], values[2]) else GradeWheel.NONE

    companion object {
        private fun list(w: GradeWheel) = if (w == GradeWheel.NONE) emptyList() else listOf(w.hue, w.saturation, w.luminance)

        fun from(g: ColorGrading) = GradingFile(list(g.shadows), list(g.midtones), list(g.highlights), list(g.global), g.blending, g.balance, g.monochrome)
    }
}

@Serializable
internal data class RetouchFile(
    val id: Int,
    val target: List<Float>,
    val source: List<Float>,
    val radius: Float,
    val feather: Float = RetouchSpot.DEFAULT_FEATHER,
    val opacity: Float = 1f,
    val mode: String = RetouchMode.HEAL.name,
) {
    fun toSpot(): RetouchSpot? {
        if (target.size != 2 || source.size != 2) return null
        val retouchMode = RetouchMode.entries.firstOrNull { it.name == mode } ?: RetouchMode.HEAL
        return RetouchSpot(id, target[0], target[1], source[0], source[1], radius, feather, opacity, retouchMode).clamped()
    }

    companion object {
        fun from(spot: RetouchSpot) =
            RetouchFile(spot.id, listOf(spot.targetX, spot.targetY), listOf(spot.sourceX, spot.sourceY), spot.radius, spot.feather, spot.opacity, spot.mode.name)
    }
}

/** Export choices; also used by the app's settings store, so it is not private. */
@Serializable
internal data class ExportFile(
    val format: String = ExportFormat.JPEG.name,
    val quality: Int = ExportOptions.DEFAULT_QUALITY,
    val size: String = ExportSize.FULL.name,
    val metadata: String = MetadataPolicy.REMOVE_LOCATION.name,
    val borderWidth: Float = 0f,
    val borderColor: Int = Border.WHITE,
    val watermarkText: String = "",
    val watermarkPosition: String = WatermarkPosition.BOTTOM_RIGHT.name,
    val watermarkSize: Float = Watermark.DEFAULT_SIZE,
    val watermarkOpacity: Float = Watermark.DEFAULT_OPACITY,
) {
    fun toOptions() = ExportOptions(
        format = ExportFormat.entries.firstOrNull { it.name == format } ?: ExportFormat.JPEG,
        quality = quality.coerceIn(ExportOptions.MIN_QUALITY, ExportOptions.MAX_QUALITY),
        size = ExportSize.entries.firstOrNull { it.name == size } ?: ExportSize.FULL,
        metadata = MetadataPolicy.entries.firstOrNull { it.name == metadata } ?: MetadataPolicy.REMOVE_LOCATION,
        border = Border(borderWidth, borderColor).clamped(),
        watermark = Watermark(
            text = watermarkText,
            position = WatermarkPosition.entries.firstOrNull { it.name == watermarkPosition } ?: WatermarkPosition.BOTTOM_RIGHT,
            size = watermarkSize,
            opacity = watermarkOpacity,
        ).clamped(),
    )

    companion object {
        fun from(options: ExportOptions) = ExportFile(
            format = options.format.name,
            quality = options.quality,
            size = options.size.name,
            metadata = options.metadata.name,
            borderWidth = options.border.widthFraction,
            borderColor = options.border.color,
            watermarkText = options.watermark.text,
            watermarkPosition = options.watermark.position.name,
            watermarkSize = options.watermark.size,
            watermarkOpacity = options.watermark.opacity,
        )
    }
}

/** JSON for [ExportOptions] on its own (the app's remembered export choices). */
object ExportOptionsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(options: ExportOptions): String = json.encodeToString(ExportFile.serializer(), ExportFile.from(options))

    /** Unknown or damaged input falls back to the defaults rather than failing. */
    fun decode(text: String?): ExportOptions = text?.let { runCatching { json.decodeFromString(ExportFile.serializer(), it).toOptions() }.getOrNull() } ?: ExportOptions()
}
