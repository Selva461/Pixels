package com.pixels.enhancer.harness

import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.domain.analysis.StatisticalImageAnalyzer
import com.pixels.enhancer.domain.debug.DebugReport
import com.pixels.enhancer.domain.planning.EnhancementStrength
import com.pixels.enhancer.domain.planning.Look
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.processing.PipelineImageProcessor
import com.pixels.enhancer.domain.processing.StageConfig
import com.pixels.enhancer.domain.processing.stages.DefaultPipeline
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.usecase.EnhanceRequest
import com.pixels.enhancer.domain.validation.NaturalOutputValidator
import com.pixels.enhancer.testing.GoldenScenario
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Runs the real engine on desktop image files for fast visual iteration without a phone.
 *
 *   ./gradlew -p engine :harness:run --args="--synthetic --out /tmp/out"
 *   ./gradlew -p engine :harness:run --args="--strength 0.6 --disable sharpen photo.jpg"
 *
 * For each input it writes <name>_enhanced.png, <name>_compare.png (before | after) and
 * <name>_report.txt (the same debug report as the app's debug screen).
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

private fun buildUseCase(outputDirectory: File) = EnhanceImageUseCase(
    imageRepository = FileImageRepository(),
    analyzer = StatisticalImageAnalyzer(),
    planner = NaturalEnhancementPlanner(),
    processor = PipelineImageProcessor(DefaultPipeline.stages(), logger = ConsoleLogger),
    validator = NaturalOutputValidator(),
    saver = DirectoryImageSaver(outputDirectory),
    logger = ConsoleLogger,
)

private suspend fun processFile(file: File, options: HarnessOptions): Boolean {
    val useCase = buildUseCase(options.outputDirectory)
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
    useCase.save(session, request)
    val stem = file.nameWithoutExtension
    ImageIO.write(
        ImageIoConversions.sideBySide(session.original, outcome.processed.image),
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
) {
    companion object {
        const val USAGE = "usage: harness [--synthetic] [--out DIR] [--strength 0..1] [--until STAGE] " +
            "[--disable STAGE,STAGE] [--look ID] [--verbose] [files...]"

        fun parse(args: Array<String>): HarnessOptions? {
            val inputs = mutableListOf<String>()
            var output = File("harness-output")
            var strength = EnhancementStrength.DEFAULT
            var until: String? = null
            var disabled = emptyList<String>()
            var synthetic = false
            var verbose = false
            var look: String? = null
            val iterator = args.iterator()
            while (iterator.hasNext()) {
                when (val arg = iterator.next()) {
                    "--out" -> output = File(iterator.nextOrNull() ?: return null)
                    "--strength" -> strength = iterator.nextOrNull()?.toFloatOrNull() ?: return null
                    "--until" -> until = iterator.nextOrNull() ?: return null
                    "--disable" -> disabled = iterator.nextOrNull()?.split(',') ?: return null
                    "--synthetic" -> synthetic = true
                    "--verbose" -> verbose = true
                    "--look" -> look = iterator.nextOrNull() ?: return null
                    else -> if (arg.startsWith("--")) return null else inputs += arg
                }
            }
            return HarnessOptions(inputs, output, strength, until, disabled, synthetic, verbose, look)
        }

        private fun Iterator<String>.nextOrNull(): String? = if (hasNext()) next() else null
    }
}
