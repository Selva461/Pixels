package com.pixels.enhancer.harness

import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.domain.analysis.StatisticalImageAnalyzer
import com.pixels.enhancer.domain.debug.DebugReport
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.PixelResampler
import com.pixels.enhancer.domain.local.LocalAdjustments
import com.pixels.enhancer.domain.planning.EnhancementStrength
import com.pixels.enhancer.domain.planning.Look
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.processing.PipelineImageProcessor
import com.pixels.enhancer.domain.processing.StageConfig
import com.pixels.enhancer.domain.processing.stages.DefaultPipeline
import com.pixels.enhancer.domain.regions.RegionDetector
import com.pixels.enhancer.domain.regions.RegionKind
import com.pixels.enhancer.domain.regions.SmartEdit
import com.pixels.enhancer.domain.regions.SubjectHint
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.usecase.EnhanceRequest
import com.pixels.enhancer.domain.usecase.EnhancementOutcome
import com.pixels.enhancer.domain.usecase.EnhancementSession
import com.pixels.enhancer.domain.validation.NaturalOutputValidator
import com.pixels.enhancer.testing.GoldenScenario
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * Runs the real engine on desktop image files for fast visual iteration without a phone.
 *
 *   ./gradlew -p engine :harness:run --args="--synthetic --out /tmp/out"
 *   ./gradlew -p engine :harness:run --args="--strength 0.6 --disable sharpen photo.jpg"
 *
 * For each input it writes <name>_enhanced.png, <name>_compare.png (before | after) and
 * <name>_report.txt (the same debug report as the app's debug screen). With --smart it also runs
 * Smart edit and writes <name>_smart.png (before | Auto only | Smart edit) and <name>_regions.png
 * (sky tinted blue, subject tinted red). A greyscale `<name>.people.png` next to an input stands in
 * for the phone's people segmentation.
 */
fun main(args: Array<String>) {
    val options = HarnessOptions.parse(args) ?: run {
        println(HarnessOptions.USAGE)
        exitProcess(1)
    }
    ConsoleLogger.verbose = options.verbose
    options.outputDirectory.mkdirs()
    val inputs = options.inputs.map(::File) + if (options.synthetic) writeSyntheticScenes(options.outputDirectory) else emptyList()
    if (inputs.isEmpty()) {
        println(HarnessOptions.USAGE)
        exitProcess(1)
    }
    val failures = runBlocking { inputs.count { !processFile(it, options) } }
    exitProcess(if (failures == 0) 0 else 2)
}

private fun buildUseCase(outputDirectory: File, people: SubjectHint? = null) = EnhanceImageUseCase(
    imageRepository = FileImageRepository(),
    analyzer = StatisticalImageAnalyzer(),
    planner = NaturalEnhancementPlanner(),
    processor = PipelineImageProcessor(DefaultPipeline.stages(), logger = ConsoleLogger),
    validator = NaturalOutputValidator(),
    saver = DirectoryImageSaver(outputDirectory),
    logger = ConsoleLogger,
    subjectSegmenter = { people },
)

/** `<name>.people.png` next to an input: a greyscale people mask (white = person), standing in for the phone's model. */
private fun peopleHintFor(file: File): SubjectHint? {
    val mask = File(file.parentFile, "${file.nameWithoutExtension}.people.png").takeIf { it.exists() } ?: return null
    val image = ImageIoConversions.toPixelBuffer(ImageIO.read(mask))
    return SubjectHint(image.width, image.height, FloatArray(image.pixelCount) { i -> Argb.green(image.pixels[i]) / 255f })
}

private suspend fun processFile(file: File, options: HarnessOptions): Boolean {
    val useCase = buildUseCase(options.outputDirectory, peopleHintFor(file))
    val session = when (val opened = useCase.open(file.path)) {
        is OperationResult.Failure -> return reportFailure(file, opened)
        is OperationResult.Success -> opened.value
    }
    val request = EnhanceRequest(
        strength = options.strength,
        manual = Look.byId(options.look).adjustments,
        debugEnabled = true,
        stageConfigs = options.disabledStages.associateWith { StageConfig(enabled = false) },
        runUntilStageId = options.runUntil,
    )
    val outcome = when (val enhanced = useCase.enhance(session, request)) {
        is OperationResult.Failure -> return reportFailure(file, enhanced)
        is OperationResult.Success -> enhanced.value
    }
    if (options.smart && !writeSmartEdit(useCase, session, request, outcome, file, options)) return false
    useCase.save(session, request)
    val stem = file.nameWithoutExtension
    ImageIO.write(
        ImageIoConversions.sideBySide(outcome.originalView, outcome.output),
        "png",
        File(options.outputDirectory, "${stem}_compare.png"),
    )
    val report = DebugReport.format(session, outcome)
    File(options.outputDirectory, "${stem}_report.txt").writeText(report + "\n")
    println("== ${file.name} (${outcome.processingId})")
    if (options.verbose) {
        println(report)
    } else {
        outcome.plan.entries().filter { it.second.enabled }.forEach { (kind, adjustment) ->
            println("  ${kind.label.padEnd(14)} ${"%+.3f".format(adjustment.amount)}  ${adjustment.reason}")
        }
    }
    return true
}

private suspend fun writeSmartEdit(
    useCase: EnhanceImageUseCase,
    session: EnhancementSession,
    request: EnhanceRequest,
    auto: EnhancementOutcome,
    file: File,
    options: HarnessOptions,
): Boolean {
    val masks = when (val suggested = useCase.suggestSmartEdit(session, request)) {
        is OperationResult.Failure -> return reportFailure(file, suggested)
        is OperationResult.Success -> suggested.value
    }
    val smart = when (val enhanced = useCase.enhance(session, request.copy(localAdjustments = SmartEdit.merge(LocalAdjustments.NONE, masks)))) {
        is OperationResult.Failure -> return reportFailure(file, enhanced)
        is OperationResult.Success -> enhanced.value
    }
    val stem = file.nameWithoutExtension
    val autoAndSmart = ImageIoConversions.toPixelBuffer(ImageIoConversions.sideBySide(auto.output, smart.output))
    val before = PixelResampler.downscaleToFit(auto.originalView, maxOf(auto.output.width, auto.output.height))
    ImageIO.write(ImageIoConversions.sideBySide(before, autoAndSmart), "png", File(options.outputDirectory, "${stem}_smart.png"))
    val maps = RegionDetector.detect(auto.originalView, auto.subjectHint)
    val sky = maps.weights(RegionKind.SKY, auto.originalView.width, auto.originalView.height)
    val subject = maps.weights(RegionKind.SUBJECT, auto.originalView.width, auto.originalView.height)
    val tinted = PixelBuffer(
        auto.originalView.width,
        auto.originalView.height,
        IntArray(auto.originalView.pixelCount) { i ->
            val c = auto.originalView.pixels[i]
            fun mix(channel: Int, tint: Int, w: Float) = (channel + (tint - channel) * w * TINT).toInt()
            val r = mix(mix((c shr 16) and 0xFF, 255, subject[i]), 40, sky[i])
            val g = mix(mix((c shr 8) and 0xFF, 40, subject[i]), 90, sky[i])
            val b = mix(mix(c and 0xFF, 40, subject[i]), 255, sky[i])
            Argb.opaque(r, g, b)
        },
    )
    ImageIO.write(ImageIoConversions.toBufferedImage(tinted), "png", File(options.outputDirectory, "${stem}_regions.png"))
    println("  smart edit (subject confidence ${"%.2f".format(maps.subjectConfidence)}, sky ${"%.0f".format(maps.skyFraction * 100)} %):")
    masks.forEach { println("    $it") }
    return true
}

private const val TINT = 0.55f

private fun reportFailure(file: File, failure: OperationResult.Failure): Boolean {
    System.err.println("FAILED ${file.name}: ${failure.code} ${failure.message}")
    return false
}

private fun writeSyntheticScenes(directory: File): List<File> {
    val sceneDirectory = File(directory, "synthetic").apply { mkdirs() }
    return GoldenScenario.entries.map { scenario ->
        File(sceneDirectory, "${scenario.fileStem}.png").also { file ->
            ImageIO.write(ImageIoConversions.toBufferedImage(scenario.render()), "png", file)
        }
    }
}

private data class HarnessOptions(
    val inputs: List<String>,
    val outputDirectory: File,
    val strength: Float,
    val runUntil: String?,
    val disabledStages: List<String>,
    val synthetic: Boolean,
    val verbose: Boolean,
    val look: String?,
    val smart: Boolean,
) {
    companion object {
        const val USAGE = "usage: harness [--synthetic] [--out DIR] [--strength 0..1] [--until STAGE] " +
            "[--disable STAGE,STAGE] [--look ID] [--smart] [--verbose] [files...]"

        fun parse(args: Array<String>): HarnessOptions? {
            val inputs = mutableListOf<String>()
            var output = File("harness-output")
            var strength = EnhancementStrength.DEFAULT
            var until: String? = null
            var disabled = emptyList<String>()
            var synthetic = false
            var verbose = false
            var look: String? = null
            var smart = false
            val iterator = args.iterator()
            while (iterator.hasNext()) {
                when (val arg = iterator.next()) {
                    "--out" -> output = File(iterator.nextOrNull() ?: return null)
                    "--strength" -> strength = iterator.nextOrNull()?.toFloatOrNull() ?: return null
                    "--until" -> until = iterator.nextOrNull() ?: return null
                    "--disable" -> disabled = iterator.nextOrNull()?.split(',') ?: return null
                    "--synthetic" -> synthetic = true
                    "--verbose" -> verbose = true
                    "--smart" -> smart = true
                    "--look" -> look = iterator.nextOrNull() ?: return null
                    else -> if (arg.startsWith("--")) return null else inputs += arg
                }
            }
            return HarnessOptions(inputs, output, strength, until, disabled, synthetic, verbose, look, smart)
        }

        private fun Iterator<String>.nextOrNull(): String? = if (hasNext()) next() else null
    }
}
